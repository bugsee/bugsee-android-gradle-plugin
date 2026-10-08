package com.bugsee.publish;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Drops checksum sidecars of PGP signature files ({@code *.asc.md5}, {@code *.asc.sha1},
 * {@code *.asc.sha256}, {@code *.asc.sha512}) and uploads the remaining Maven repository tree.
 *
 * <p>Maven Central does not require checksums of {@code .asc} files. Gradle 8.7 still writes
 * them, because it checksums every artifact it publishes and a signature is just another
 * artifact. There is no switch for that until Gradle 9.7. Callers stage the publication into
 * a local Maven directory (where Gradle does write the checksums), delete the signature
 * checksums, then {@link #upload} the rest. Snapshot uploads also PUT a new
 * {@code .sha256} and {@code .sha512} of {@code maven-metadata.xml} before replacing
 * that file. The snapshot repository rejects DELETE of those sidecars (HTTP 403), and
 * leaving the previous checksums would no longer match. Release uploads do not: a fresh
 * staging repository has no leftovers, and its API allows only PUT, GET, and HEAD.
 */
public final class MavenSignatureChecksums {

    private static final int MAX_ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 60_000;
    private static final int READ_TIMEOUT_MS = 180_000;
    private static final int COPY_BUFFER = 8192;

    private MavenSignatureChecksums() {
    }

    /** True when {@code fileName} is a checksum of a {@code .asc} signature, not of an artifact. */
    public static boolean isSignatureChecksum(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.endsWith(".asc.md5")
                || name.endsWith(".asc.sha1")
                || name.endsWith(".asc.sha256")
                || name.endsWith(".asc.sha512");
    }

    /**
     * Deletes signature-checksum files under {@code repoRoot}. Other files, including the
     * {@code .asc} signatures and the artifacts' own {@code .md5}/{@code .sha1}, stay.
     *
     * @return how many files were deleted
     */
    public static int deleteSignatureChecksums(File repoRoot) {
        if (repoRoot == null || !repoRoot.isDirectory()) {
            return 0;
        }
        int removed = 0;
        List<File> files = listFiles(repoRoot);
        for (File file : files) {
            if (isSignatureChecksum(file.getName()) && file.delete()) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Relative paths, using {@code /}, of every regular file under {@code repoRoot} that
     * {@link #upload} will PUT. Signature checksums are omitted even if they are still on disk.
     */
    public static List<String> filesToUpload(File repoRoot) {
        List<String> paths = new ArrayList<>();
        if (repoRoot == null || !repoRoot.isDirectory()) {
            return paths;
        }
        String rootPath = repoRoot.getAbsolutePath();
        String prefix = rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;
        for (File file : listFiles(repoRoot)) {
            if (isSignatureChecksum(file.getName())) {
                continue;
            }
            String absolute = file.getAbsolutePath();
            if (!absolute.startsWith(prefix)) {
                throw new IllegalStateException("File is outside the repository: " + absolute);
            }
            paths.add(absolute.substring(prefix.length()).replace('\\', '/'));
        }
        Collections.sort(paths);
        return paths;
    }

    /**
     * Copies the snapshot repository's existing {@code maven-metadata.xml} files into
     * {@code repoRoot} so a later local publish merges with them.
     *
     * <p>Gradle reads that metadata from whichever repository it is publishing to. A release
     * goes to a fresh staging repository, so there is nothing to merge. A snapshot goes to the
     * shared snapshot repository. Publishing into an empty directory makes Gradle rebuild the
     * metadata with only this version and build number 1, which drops every older snapshot
     * version and resets the unique-version counter. A missing file (HTTP 404) is the first
     * publish of that module and is left absent. Any other failure aborts, because continuing
     * would publish that reset metadata.
     */
    public static void seedSnapshotMetadata(
            File repoRoot,
            URI remoteBase,
            String groupId,
            String artifactId,
            String version,
            String username,
            String password) throws IOException {
        if (version == null || !version.endsWith("SNAPSHOT")) {
            return;
        }
        if (repoRoot == null || !repoRoot.isDirectory()) {
            throw new IllegalArgumentException("Repository directory does not exist: " + repoRoot);
        }
        String base = httpBase(remoteBase);
        if (username == null || username.isEmpty()) {
            throw new IllegalArgumentException("Repository username is missing");
        }
        String authorization = basicAuth(username, password == null ? "" : password);
        downloadOptional(repoRoot, base, metadataPath(groupId, artifactId, null), authorization);
        downloadOptional(repoRoot, base, metadataPath(groupId, artifactId, version), authorization);
    }

    /**
     * PUTs {@link #filesToUpload} to {@code remoteBase}. Does not delete metadata checksums.
     * Release publishes must use this: the OSSRH staging API rejects DELETE.
     *
     * @return how many files were uploaded
     */
    public static int upload(File repoRoot, URI remoteBase, String username, String password)
            throws IOException {
        return upload(repoRoot, remoteBase, username, password, false);
    }

    /**
     * PUTs {@link #filesToUpload} to {@code remoteBase}, which is the staging or snapshot
     * repository root (no trailing path of its own beyond the repo).
     *
     * <p>When {@code dropStaleMetadataChecksums} is true, a new {@code .sha256} and
     * {@code .sha512} of each {@code maven-metadata.xml} are PUT before that file is
     * replaced. Snapshot metadata is overwritten in place. The snapshot repository keeps
     * the previous stronger checksums and answers 403 to DELETE, so those files have to be
     * replaced with the digest of the new XML. The checksums go out first: a failure there
     * does not leave new XML beside the old digest. Release staging repositories are empty
     * and their HTTP API does not allow DELETE, so callers pass false and these extra
     * checksums are not uploaded.
     *
     * @return how many files were uploaded
     */
    public static int upload(
            File repoRoot,
            URI remoteBase,
            String username,
            String password,
            boolean dropStaleMetadataChecksums) throws IOException {
        if (repoRoot == null || !repoRoot.isDirectory()) {
            throw new IllegalArgumentException("Repository directory does not exist: " + repoRoot);
        }
        if (remoteBase == null) {
            throw new IllegalArgumentException("Remote repository URL is missing");
        }
        String base = httpBase(remoteBase);
        if (username == null || username.isEmpty()) {
            throw new IllegalArgumentException("Repository username is missing");
        }
        String authorization = basicAuth(username, password == null ? "" : password);
        List<String> paths = filesToUpload(repoRoot);
        for (String relative : paths) {
            if (relative.startsWith("/") || relative.contains("..")) {
                throw new IllegalArgumentException("Refusing to upload path: " + relative);
            }
            File body = new File(repoRoot, relative);
            if (dropStaleMetadataChecksums && isMavenMetadata(relative)) {
                putBytesWithRetry(
                        URI.create(base + relative + ".sha256"), checksum(body, "SHA-256"), authorization);
                putBytesWithRetry(
                        URI.create(base + relative + ".sha512"), checksum(body, "SHA-512"), authorization);
            }
            putWithRetry(URI.create(base + relative), body, authorization);
        }
        return paths.size();
    }

    private static boolean isMavenMetadata(String relative) {
        return "maven-metadata.xml".equals(relative) || relative.endsWith("/maven-metadata.xml");
    }

    /**
     * Lower-case hex digest with no trailing newline, which is what Gradle 8.7 writes
     * and what the snapshot repository already serves for these sidecars.
     */
    private static byte[] checksum(File file, String algorithm) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("Missing digest " + algorithm, ex);
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[COPY_BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] raw = digest.digest();
        byte[] hex = new byte[raw.length * 2];
        for (int i = 0; i < raw.length; i++) {
            int value = raw[i] & 0xff;
            hex[i * 2] = (byte) HEX[value >>> 4];
            hex[i * 2 + 1] = (byte) HEX[value & 0x0f];
        }
        return hex;
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static void putBytesWithRetry(URI target, byte[] body, String authorization) throws IOException {
        File temp = File.createTempFile("maven-checksum-", ".bin");
        try {
            Files.write(temp.toPath(), body);
            putWithRetry(target, temp, authorization);
        } finally {
            if (!temp.delete()) {
                temp.deleteOnExit();
            }
        }
    }

    private static String httpBase(URI remoteBase) {
        if (remoteBase == null) {
            throw new IllegalArgumentException("Remote repository URL is missing");
        }
        String scheme = remoteBase.getScheme();
        if (scheme == null
                || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                    "Remote repository URL must be http(s): " + remoteBase);
        }
        String base = remoteBase.toString();
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return base;
    }

    /**
     * {@code groupId}/{@code artifactId}/maven-metadata.xml, or the same path under
     * {@code version} when {@code version} is non-null. Those are the two files Gradle 8.7
     * reads back while publishing ({@code maven-metadata.xml}, not {@code maven-metadata-local.xml},
     * because a plain file repository is not mavenLocal).
     */
    private static String metadataPath(String groupId, String artifactId, String version) {
        rejectPathSegment(groupId, "groupId");
        rejectPathSegment(artifactId, "artifactId");
        if (version != null) {
            rejectPathSegment(version, "version");
        }
        StringBuilder path = new StringBuilder(groupId.length() + artifactId.length() + 32);
        for (int i = 0; i < groupId.length(); i++) {
            char c = groupId.charAt(i);
            path.append(c == '.' ? '/' : c);
        }
        path.append('/').append(artifactId).append('/');
        if (version != null) {
            path.append(version).append('/');
        }
        path.append("maven-metadata.xml");
        return path.toString();
    }

    private static void rejectPathSegment(String value, String label) {
        if (value == null || value.isEmpty()
                || value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || value.indexOf("..") >= 0) {
            throw new IllegalArgumentException("Refusing metadata " + label + ": " + value);
        }
    }

    private static void downloadOptional(
            File repoRoot, String base, String relative, String authorization) throws IOException {
        byte[] body = getOptional(URI.create(base + relative), authorization);
        if (body == null) {
            return;
        }
        File target = new File(repoRoot, relative);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        Files.write(target.toPath(), body);
    }

    /** @return the body, or null when the server has no such file (HTTP 404) */
    private static byte[] getOptional(URI target, String authorization) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            final int code;
            final byte[] body;
            try {
                HttpResult result = getOnce(target, authorization);
                code = result.code;
                body = result.body;
            } catch (IOException ex) {
                last = ex;
                if (attempt == MAX_ATTEMPTS) {
                    throw ex;
                }
                continue;
            }
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return null;
            }
            if (code >= 200 && code < 300) {
                return body == null ? new byte[0] : body;
            }
            IOException failure = new IOException("GET " + target + " failed with HTTP " + code);
            if (code < 500 || attempt == MAX_ATTEMPTS) {
                throw failure;
            }
            last = failure;
        }
        throw last == null ? new IOException("GET " + target + " failed") : last;
    }

    private static HttpResult getOnce(URI target, String authorization) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) target.toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", authorization);
        try {
            int code = connection.getResponseCode();
            InputStream response = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new HttpResult(code, readFully(response));
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readFully(InputStream response) throws IOException {
        if (response == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[COPY_BUFFER];
            int read;
            while ((read = response.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            response.close();
        }
    }

    private static final class HttpResult {
        final int code;
        final byte[] body;

        HttpResult(int code, byte[] body) {
            this.code = code;
            this.body = body;
        }
    }

    private static void putWithRetry(URI target, File body, String authorization) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            final int code;
            try {
                code = putOnce(target, body, authorization);
            } catch (IOException ex) {
                // Connection failures are worth repeating. An HTTP status is handled below
                // so a 401 is not retried as if the socket had died.
                last = ex;
                if (attempt == MAX_ATTEMPTS) {
                    throw ex;
                }
                continue;
            }
            if (code >= 200 && code < 300) {
                return;
            }
            IOException failure = new IOException(
                    "PUT " + target + " failed with HTTP " + code + " (" + body.length() + " bytes)");
            if (code < 500 || attempt == MAX_ATTEMPTS) {
                throw failure;
            }
            last = failure;
        }
        throw last == null ? new IOException("PUT " + target + " failed") : last;
    }

    private static int putOnce(URI target, File body, String authorization) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) target.toURL().openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setDoOutput(true);
        connection.setRequestMethod("PUT");
        connection.setFixedLengthStreamingMode(body.length());
        connection.setRequestProperty("Authorization", authorization);
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        try {
            try (InputStream in = new FileInputStream(body);
                 OutputStream out = connection.getOutputStream()) {
                byte[] buffer = new byte[COPY_BUFFER];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            int code = connection.getResponseCode();
            InputStream response = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            drain(response);
            return code;
        } finally {
            connection.disconnect();
        }
    }

    private static void drain(InputStream response) throws IOException {
        if (response == null) {
            return;
        }
        try {
            byte[] ignore = new byte[256];
            while (response.read(ignore) != -1) {
                // discard
            }
        } finally {
            response.close();
        }
    }

    private static String basicAuth(String username, String password) {
        String token = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.ISO_8859_1));
        return "Basic " + encoded;
    }

    private static List<File> listFiles(File root) {
        List<File> files = new ArrayList<>();
        collect(root, files);
        return files;
    }

    private static void collect(File dir, List<File> files) {
        File[] children = dir.listFiles();
        if (children == null) {
            throw new IllegalStateException("Cannot list " + dir);
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, files);
            } else if (child.isFile()) {
                files.add(child);
            }
        }
    }
}
