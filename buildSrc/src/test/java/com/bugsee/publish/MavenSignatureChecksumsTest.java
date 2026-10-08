package com.bugsee.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class MavenSignatureChecksumsTest {

    @Test
    public void signatureChecksumNamesAreOnlyTheAscSidecars() {
        assertTrue(MavenSignatureChecksums.isSignatureChecksum(
                "bugsee-android-7.3.0.pom.asc.md5"));
        assertTrue(MavenSignatureChecksums.isSignatureChecksum(
                "bugsee-android-7.3.0.aar.asc.sha1"));
        assertTrue(MavenSignatureChecksums.isSignatureChecksum(
                "bugsee-android-7.3.0.module.asc.sha256"));
        assertTrue(MavenSignatureChecksums.isSignatureChecksum(
                "com/bugsee/bugsee-android/7.3.0/bugsee-android-7.3.0-sources.jar.asc.sha512"));

        assertFalse(MavenSignatureChecksums.isSignatureChecksum("bugsee-android-7.3.0.pom"));
        assertFalse(MavenSignatureChecksums.isSignatureChecksum("bugsee-android-7.3.0.pom.asc"));
        assertFalse(MavenSignatureChecksums.isSignatureChecksum("bugsee-android-7.3.0.pom.md5"));
        assertFalse(MavenSignatureChecksums.isSignatureChecksum("bugsee-android-7.3.0.pom.sha1"));
        assertFalse(MavenSignatureChecksums.isSignatureChecksum("bugsee-android-7.3.0.pom.sha256"));
        assertFalse(MavenSignatureChecksums.isSignatureChecksum("maven-metadata.xml.md5"));
        assertFalse(MavenSignatureChecksums.isSignatureChecksum(null));
        assertFalse(MavenSignatureChecksums.isSignatureChecksum(""));
    }

    @Test
    public void deleteRemovesSignatureChecksumsAndLeavesRequiredFiles() throws Exception {
        File root = Files.createTempDirectory("sig-checksums").toFile();
        try {
            write(root, "com/bugsee/demo/1.0/demo-1.0.pom", "<project/>");
            write(root, "com/bugsee/demo/1.0/demo-1.0.pom.md5", "abc");
            write(root, "com/bugsee/demo/1.0/demo-1.0.pom.sha1", "def");
            write(root, "com/bugsee/demo/1.0/demo-1.0.pom.asc", "sig");
            write(root, "com/bugsee/demo/1.0/demo-1.0.pom.asc.md5", "1");
            write(root, "com/bugsee/demo/1.0/demo-1.0.pom.asc.sha1", "2");
            write(root, "com/bugsee/demo/1.0/demo-1.0.pom.asc.sha256", "3");
            write(root, "com/bugsee/demo/1.0/demo-1.0.pom.asc.sha512", "4");
            write(root, "com/bugsee/demo/maven-metadata.xml", "<metadata/>");
            write(root, "com/bugsee/demo/maven-metadata.xml.sha1", "meta");

            assertEquals(4, MavenSignatureChecksums.deleteSignatureChecksums(root));
            assertEquals(0, MavenSignatureChecksums.deleteSignatureChecksums(root));

            assertTrue(new File(root, "com/bugsee/demo/1.0/demo-1.0.pom").isFile());
            assertTrue(new File(root, "com/bugsee/demo/1.0/demo-1.0.pom.md5").isFile());
            assertTrue(new File(root, "com/bugsee/demo/1.0/demo-1.0.pom.sha1").isFile());
            assertTrue(new File(root, "com/bugsee/demo/1.0/demo-1.0.pom.asc").isFile());
            assertTrue(new File(root, "com/bugsee/demo/maven-metadata.xml").isFile());
            assertFalse(new File(root, "com/bugsee/demo/1.0/demo-1.0.pom.asc.md5").exists());
            assertFalse(new File(root, "com/bugsee/demo/1.0/demo-1.0.pom.asc.sha1").exists());
            assertFalse(new File(root, "com/bugsee/demo/1.0/demo-1.0.pom.asc.sha256").exists());
            assertFalse(new File(root, "com/bugsee/demo/1.0/demo-1.0.pom.asc.sha512").exists());

            List<String> upload = MavenSignatureChecksums.filesToUpload(root);
            assertEquals(6, upload.size());
            assertTrue(upload.contains("com/bugsee/demo/1.0/demo-1.0.pom.asc"));
            assertTrue(upload.contains("com/bugsee/demo/1.0/demo-1.0.pom.md5"));
            assertFalse(upload.contains("com/bugsee/demo/1.0/demo-1.0.pom.asc.md5"));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void uploadSkipsSignatureChecksumsEvenWhenTheyAreStillOnDisk() throws Exception {
        File root = Files.createTempDirectory("sig-upload").toFile();
        RecordingServer server = new RecordingServer();
        try {
            write(root, "com/bugsee/demo/1.0/demo-1.0.pom", "<project/>");
            write(root, "com/bugsee/demo/1.0/demo-1.0.pom.sha1", "deadbeef");
            write(root, "com/bugsee/demo/1.0/demo-1.0.pom.asc", "SIG");
            write(root, "com/bugsee/demo/1.0/demo-1.0.pom.asc.md5", "nope");
            write(root, "com/bugsee/demo/1.0/demo-1.0.pom.asc.sha1", "nope");

            String base = server.url();
            assertFalse(base.endsWith("/"));
            int count = MavenSignatureChecksums.upload(
                    root, URI.create(base), "token-user", "token-pass");

            assertEquals(3, count);
            List<String> paths = server.paths();
            assertEquals(3, paths.size());
            assertTrue(paths.contains("/com/bugsee/demo/1.0/demo-1.0.pom"));
            assertTrue(paths.contains("/com/bugsee/demo/1.0/demo-1.0.pom.sha1"));
            assertTrue(paths.contains("/com/bugsee/demo/1.0/demo-1.0.pom.asc"));
            for (String path : paths) {
                assertFalse(path.contains(".asc.md5"));
                assertFalse(path.contains(".asc.sha1"));
            }
            assertEquals("Basic dG9rZW4tdXNlcjp0b2tlbi1wYXNz", server.authorizations().get(0));
            assertEquals("<project/>", server.bodyFor("/com/bugsee/demo/1.0/demo-1.0.pom"));
            // The sidecars are still on disk: upload filters them, it does not have to delete first.
            assertTrue(new File(root, "com/bugsee/demo/1.0/demo-1.0.pom.asc.md5").isFile());
        } finally {
            server.stop();
            deleteTree(root);
        }
    }

    @Test
    public void uploadDoesNotRetryUnauthorized() throws Exception {
        File root = Files.createTempDirectory("sig-401").toFile();
        RecordingServer server = new RecordingServer();
        server.failWith = 401;
        try {
            write(root, "a.pom", "<project/>");
            try {
                MavenSignatureChecksums.upload(root, URI.create(server.url() + "/"), "u", "p");
                fail("401 must fail the upload");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("HTTP 401"));
            }
            assertEquals(1, server.paths().size());
        } finally {
            server.stop();
            deleteTree(root);
        }
    }

    @Test
    public void uploadRejectsANonHttpRepository() throws Exception {
        File root = Files.createTempDirectory("sig-scheme").toFile();
        try {
            write(root, "a.pom", "<project/>");
            try {
                MavenSignatureChecksums.upload(root, new File(root, "nope").toURI(), "u", "p");
                fail("a file: repository must be rejected");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("http"));
            }
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void uploadRejectsAMissingUsername() throws Exception {
        File root = Files.createTempDirectory("sig-user").toFile();
        try {
            write(root, "a.pom", "<project/>");
            try {
                MavenSignatureChecksums.upload(root, URI.create("https://example.invalid/repo"), "", "p");
                fail("an empty username must be rejected");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("username"));
            }
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void uploadReplacesStaleStrongChecksumsBeforeReplacingMetadata() throws Exception {
        File root = Files.createTempDirectory("sig-meta-put").toFile();
        RecordingServer server = new RecordingServer();
        try {
            String moduleXml = "<metadata>new</metadata>";
            String versionXml = "<metadata>ver</metadata>";
            write(root, "com/bugsee/demo/maven-metadata.xml", moduleXml);
            write(root, "com/bugsee/demo/maven-metadata.xml.md5", "abc");
            write(root, "com/bugsee/demo/maven-metadata.xml.sha1", "def");
            write(root, "com/bugsee/demo/1.0-SNAPSHOT/maven-metadata.xml", versionXml);
            write(root, "com/bugsee/demo/1.0-SNAPSHOT/demo-1.0-20261001.000000-8.pom", "<project/>");

            assertEquals(5, MavenSignatureChecksums.upload(
                    root, URI.create(server.url()), "u", "p", true));

            List<String> trace = server.trace();
            assertEquals(0, countPrefix(trace, "DELETE "));
            assertTrue(indexOf(trace, "PUT /com/bugsee/demo/maven-metadata.xml.sha256")
                    < indexOf(trace, "PUT /com/bugsee/demo/maven-metadata.xml"));
            assertTrue(indexOf(trace, "PUT /com/bugsee/demo/maven-metadata.xml.sha512")
                    < indexOf(trace, "PUT /com/bugsee/demo/maven-metadata.xml"));
            assertTrue(indexOf(trace, "PUT /com/bugsee/demo/1.0-SNAPSHOT/maven-metadata.xml.sha512")
                    < indexOf(trace, "PUT /com/bugsee/demo/1.0-SNAPSHOT/maven-metadata.xml"));
            assertEquals(hex("SHA-256", moduleXml),
                    server.bodyFor("/com/bugsee/demo/maven-metadata.xml.sha256"));
            assertEquals(hex("SHA-512", moduleXml),
                    server.bodyFor("/com/bugsee/demo/maven-metadata.xml.sha512"));
            assertEquals(64, server.bodyFor("/com/bugsee/demo/maven-metadata.xml.sha256").length());
            assertEquals(128, server.bodyFor("/com/bugsee/demo/maven-metadata.xml.sha512").length());
            assertEquals(hex("SHA-256", versionXml),
                    server.bodyFor("/com/bugsee/demo/1.0-SNAPSHOT/maven-metadata.xml.sha256"));
            for (String event : trace) {
                assertFalse(event, event.contains(".pom.sha"));
                assertFalse(event, event.contains("maven-metadata.xml.md5.sha"));
                assertFalse(event, event.contains("maven-metadata.xml.sha1.sha"));
            }
            assertEquals("Basic dTpw", server.authorizations().get(0));
        } finally {
            server.stop();
            deleteTree(root);
        }
    }

    @Test
    public void uploadDoesNotRetryAFailedMetadataChecksumPut() throws Exception {
        File root = Files.createTempDirectory("sig-meta-401").toFile();
        RecordingServer server = new RecordingServer();
        server.failWith = 401;
        try {
            write(root, "com/bugsee/demo/maven-metadata.xml", "<metadata/>");
            try {
                MavenSignatureChecksums.upload(root, URI.create(server.url()), "u", "p", true);
                fail("401 on the checksum PUT must fail before the metadata is replaced");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("HTTP 401"));
            }
            List<String> trace = server.trace();
            assertEquals(Collections.singletonList("PUT /com/bugsee/demo/maven-metadata.xml.sha256"), trace);
        } finally {
            server.stop();
            deleteTree(root);
        }
    }

    @Test
    public void uploadRetriesAMetadataChecksumPutThenSucceeds() throws Exception {
        File root = Files.createTempDirectory("sig-meta-500").toFile();
        RecordingServer server = new RecordingServer();
        server.failTimes = 1;
        server.failWith = 500;
        try {
            write(root, "com/bugsee/demo/maven-metadata.xml", "<metadata/>");
            assertEquals(1, MavenSignatureChecksums.upload(
                    root, URI.create(server.url()), "u", "p", true));
            List<String> trace = server.trace();
            // sha256 fails once, then succeeds; sha512 succeeds. The XML is PUT after that.
            assertEquals(2, countPrefix(trace, "PUT /com/bugsee/demo/maven-metadata.xml.sha256"));
            assertTrue(trace.contains("PUT /com/bugsee/demo/maven-metadata.xml.sha512"));
            assertEquals(trace.size() - 1, indexOf(trace, "PUT /com/bugsee/demo/maven-metadata.xml"));
        } finally {
            server.stop();
            deleteTree(root);
        }
    }

    @Test
    public void releaseUploadDoesNotReplaceMetadataChecksums() throws Exception {
        File root = Files.createTempDirectory("sig-release").toFile();
        RecordingServer server = new RecordingServer();
        try {
            write(root, "com/bugsee/demo/1.0/demo-1.0.pom", "<project/>");
            write(root, "com/bugsee/demo/maven-metadata.xml", "<metadata>release</metadata>");
            assertEquals(2, MavenSignatureChecksums.upload(root, URI.create(server.url()), "u", "p", false));
            List<String> trace = server.trace();
            assertEquals(0, countPrefix(trace, "DELETE "));
            for (String event : trace) {
                assertFalse(event, event.contains(".sha256"));
                assertFalse(event, event.contains(".sha512"));
            }
            assertTrue(trace.contains("PUT /com/bugsee/demo/maven-metadata.xml"));
            assertTrue(trace.contains("PUT /com/bugsee/demo/1.0/demo-1.0.pom"));
        } finally {
            server.stop();
            deleteTree(root);
        }
    }

    @Test
    public void uploadRetriesAServerErrorThenSucceeds() throws Exception {
        File root = Files.createTempDirectory("sig-500").toFile();
        RecordingServer server = new RecordingServer();
        server.failTimes = 1;
        server.failWith = 500;
        try {
            write(root, "a.pom", "<project/>");
            assertEquals(1, MavenSignatureChecksums.upload(root, URI.create(server.url()), "u", "p"));
            assertEquals(2, server.paths().size());
        } finally {
            server.stop();
            deleteTree(root);
        }
    }

    @Test
    public void seedCopiesSnapshotMetadataAndSkipsAMissingFile() throws Exception {
        File root = Files.createTempDirectory("sig-seed").toFile();
        RecordingServer server = new RecordingServer();
        try {
            root.mkdirs();
            server.respond(
                    "/com/bugsee/demo/maven-metadata.xml",
                    "<metadata><version>0.9-SNAPSHOT</version></metadata>");
            server.status("/com/bugsee/demo/1.0-SNAPSHOT/maven-metadata.xml", 404);

            MavenSignatureChecksums.seedSnapshotMetadata(
                    root, URI.create(server.url()), "com.bugsee", "demo", "1.0-SNAPSHOT", "u", "p");

            File module = new File(root, "com/bugsee/demo/maven-metadata.xml");
            assertTrue(module.isFile());
            assertEquals(
                    "<metadata><version>0.9-SNAPSHOT</version></metadata>",
                    new String(Files.readAllBytes(module.toPath()), StandardCharsets.UTF_8));
            assertFalse(new File(root, "com/bugsee/demo/1.0-SNAPSHOT/maven-metadata.xml").exists());
            assertEquals("Basic dTpw", server.authorizations().get(0));
            assertEquals(2, server.paths().size());
        } finally {
            server.stop();
            deleteTree(root);
        }
    }

    @Test
    public void seedDoesNotTouchAReleaseVersion() throws Exception {
        File root = Files.createTempDirectory("sig-seed-release").toFile();
        RecordingServer server = new RecordingServer();
        try {
            MavenSignatureChecksums.seedSnapshotMetadata(
                    root, URI.create(server.url()), "com.bugsee", "demo", "1.0", "u", "p");
            assertEquals(0, server.paths().size());
            assertEquals(0, MavenSignatureChecksums.filesToUpload(root).size());
        } finally {
            server.stop();
            deleteTree(root);
        }
    }

    @Test
    public void seedDoesNotRetryUnauthorized() throws Exception {
        File root = Files.createTempDirectory("sig-seed-401").toFile();
        RecordingServer server = new RecordingServer();
        server.failWith = 401;
        try {
            try {
                MavenSignatureChecksums.seedSnapshotMetadata(
                        root, URI.create(server.url() + "/"), "com.bugsee", "demo", "1.0-SNAPSHOT", "u", "p");
                fail("401 must fail the seed so snapshot metadata is not rebuilt empty");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("HTTP 401"));
            }
            assertEquals(1, server.paths().size());
            assertEquals(0, MavenSignatureChecksums.filesToUpload(root).size());
        } finally {
            server.stop();
            deleteTree(root);
        }
    }

    private static void write(File root, String relative, String contents) throws IOException {
        File target = new File(root, relative);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        Files.write(target.toPath(), contents.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }

    private static String hex(String algorithm, String contents) throws Exception {
        byte[] raw = MessageDigest.getInstance(algorithm).digest(contents.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(raw.length * 2);
        for (byte value : raw) {
            int unsigned = value & 0xff;
            out.append("0123456789abcdef".charAt(unsigned >>> 4));
            out.append("0123456789abcdef".charAt(unsigned & 0x0f));
        }
        return out.toString();
    }

    private static int indexOf(List<String> trace, String event) {
        int index = trace.indexOf(event);
        assertTrue("missing " + event + " in " + trace, index >= 0);
        return index;
    }

    private static int countPrefix(List<String> trace, String prefix) {
        int count = 0;
        for (String event : trace) {
            if (event.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static final class RecordingServer {
        private final HttpServer server;
        private final List<String> paths = Collections.synchronizedList(new ArrayList<String>());
        private final List<String> methods = Collections.synchronizedList(new ArrayList<String>());
        private final List<String> authorizations = Collections.synchronizedList(new ArrayList<String>());
        private final List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        private final AtomicInteger remainingFailures = new AtomicInteger();
        volatile int failWith;
        volatile int failTimes;
        private final java.util.concurrent.ConcurrentHashMap<String, Integer> statusByPath =
                new java.util.concurrent.ConcurrentHashMap<String, Integer>();
        private final java.util.concurrent.ConcurrentHashMap<String, byte[]> responseByPath =
                new java.util.concurrent.ConcurrentHashMap<String, byte[]>();

        RecordingServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        void handle(HttpExchange exchange) throws IOException {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            paths.add(path);
            methods.add(method);
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(body, StandardCharsets.UTF_8));
            int code = 201;
            if (remainingFailures.get() < failTimes) {
                remainingFailures.incrementAndGet();
                code = failWith == 0 ? 500 : failWith;
            } else if (failTimes == 0 && failWith != 0) {
                code = failWith;
            }
            Integer override = statusByPath.get(path);
            if (override != null && code == 201) {
                code = override.intValue();
            }
            byte[] response = code >= 400 ? null : responseByPath.get(path);
            if (response == null) {
                exchange.sendResponseHeaders(code, -1);
                exchange.getResponseBody().close();
            } else {
                exchange.sendResponseHeaders(code, response.length);
                OutputStream out = exchange.getResponseBody();
                out.write(response);
                out.close();
            }
        }

        void respond(String path, String body) {
            responseByPath.put(path, body.getBytes(StandardCharsets.UTF_8));
            statusByPath.put(path, Integer.valueOf(200));
        }

        void status(String path, int code) {
            statusByPath.put(path, Integer.valueOf(code));
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<String> paths() {
            return new ArrayList<>(paths);
        }

        List<String> trace() {
            List<String> events = new ArrayList<String>();
            for (int i = 0; i < paths.size(); i++) {
                events.add(methods.get(i) + " " + paths.get(i));
            }
            return events;
        }

        List<String> authorizations() {
            return new ArrayList<>(authorizations);
        }

        String bodyFor(String path) {
            for (int i = 0; i < paths.size(); i++) {
                if (path.equals(paths.get(i))) {
                    return bodies.get(i);
                }
            }
            fail("no body for " + path);
            return "";
        }

        void stop() {
            server.stop(0);
        }
    }
}
