package com.bugsee.android.gradle.upload

import org.gradle.api.logging.Logger
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Resolves a `bugsee-cli` binary path for [MappingUploadTask] /
 * [NativeUploadTask] to exec.
 *
 * Precedence (highest first):
 *   1. **Explicit `cliPath`** — the path is used verbatim. Lets developers
 *      build the CLI from source or point at a pre-installed binary.
 *      Returns `null` if the path exists but isn't executable so the task
 *      can fall back to the Kotlin uploader rather than fail outright.
 *   2. **Auto-download** from `https://download.bugsee.com/cli/v<version>/`
 *      into a per-user Gradle cache at
 *      `${gradleUserHome}/caches/bugsee-cli/<version>/<triple>/`. SHA-256
 *      verified against the published sidecar. Subsequent builds (and
 *      subsequent task invocations within one build) reuse the cache.
 *
 * Any failure during auto-download (network unreachable, unsupported host
 * triple, checksum mismatch, extraction error) returns `null` and logs a
 * warning. The task then falls back to the Kotlin uploader — the build
 * still ships symbols, just via the legacy path.
 *
 * Concurrency: a per-triple lock file under the cache root prevents two
 * parallel builds from racing the download. The second build to enter
 * blocks on the lock, then sees the cache hit and skips.
 */
internal object CliBinaryResolver {

    /**
     * FLOOR (minimum) CLI version the plugin pins to. Bumped in lock-step
     * with `bugsee-cli` releases that introduce wire-format or argv changes
     * the plugin needs to keep up with.
     *
     * `0.6.0` is the FIRST release that ships the `update` self-update command
     * the plugin relies on below — so the floor must be at least this. It also
     * carries `upload build` (converged registration + artefact single/chunked
     * + build-info) and the `pack`/zstd-mapping path, so it satisfies both
     * [UPLOAD_BUILD_MIN_VERSION] and [PACK_MIN_VERSION].
     *
     * This is the version the plugin downloads. Keeping the CLI current is
     * the CLI's own job: once a binary is on disk, the plugin invokes
     * `bugsee-cli update --max-age 12h`, which discovers the newest
     * same-major release, caps at the same major (non-breaking), downloads +
     * verifies, and self-replaces in place — all throttled and best-effort.
     * The plugin no longer re-implements any version discovery.
     */
    const val DEFAULT_VERSION: String = "0.6.0"

    /**
     * Lowest CLI version that ships the `pack` subcommand (the normalized
     * upload-ZIP packer with a zstd-compressed mapping). `BundleUploadTask`
     * only attempts CLI packing when the pinned version is at least this, so
     * the feature stays INERT until [DEFAULT_VERSION] is bumped to a release
     * that has `pack` — no size-analysis build pays a CLI auto-download just to
     * have an older binary usage-error on an unknown subcommand.
     */
    const val PACK_MIN_VERSION: String = "0.2.0"

    /**
     * Lowest CLI version that ships `upload build` — the converged build upload
     * (registration + artefact single/chunked + build-info in one invocation)
     * that lets the plugin route ALL artefact uploads through the CLI. Gated
     * the same way as [PACK_MIN_VERSION]: `BundleUploadTask` only delegates to
     * the CLI when the pinned version is at least this, so the migration stays
     * INERT (native Bundle/ChunkedBundleUploader path runs) until
     * [DEFAULT_VERSION] is bumped to a release that has `upload build`.
     */
    const val UPLOAD_BUILD_MIN_VERSION: String = "0.3.0"

    /**
     * `true` iff [version] >= [min] by numeric-component comparison. Each
     * dot/dash/plus-separated segment contributes its leading digits (so a
     * prerelease like `0.2.0-rc1` compares on its numeric core `0.2.0`, the
     * conservative choice — we'd rather attempt `pack` on a 0.2.0 prerelease
     * than skip it). Missing trailing components compare as 0.
     *
     * Visible for testing.
     */
    internal fun versionAtLeast(version: String, min: String): Boolean {
        fun parts(v: String): List<Int> = v
            .split('.', '-', '+')
            .map { seg -> seg.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(version)
        val b = parts(min)
        for (i in 0 until maxOf(a.size, b.size)) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai > bi
        }
        return true
    }

    /** Mirror root. The same bytes also exist on GitHub Releases under
     *  `github.com/bugsee/bugsee-cli/releases/download/v<version>/`. */
    private const val DOWNLOAD_BASE: String = "https://download.bugsee.com/cli"

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 120_000

    /**
     * Returns an executable `bugsee-cli` binary, or `null` if none could
     * be obtained. Side effect: may write into [gradleUserHome] under
     * `caches/bugsee-cli/`.
     */
    fun resolve(
        cliVersion: String?,
        cliPath: String?,
        execOps: ExecOperations,
        gradleUserHome: File,
        logger: Logger,
        debug: Boolean,
        autoUpdate: Boolean = true,
    ): File? {
        // Layer 1: explicit path. If set, trust the user — but verify the
        // file is actually executable so the eventual exec fails loudly
        // here rather than mid-task.
        if (!cliPath.isNullOrBlank()) {
            val f = File(cliPath)
            return when {
                !f.isFile -> {
                    logger.warn(
                        "Bugsee: cliPath ${f.absolutePath} does not exist or is not a file; " +
                            "using the Kotlin uploader.",
                    )
                    null
                }
                !f.canExecute() -> {
                    logger.warn(
                        "Bugsee: cliPath ${f.absolutePath} is not executable; " +
                            "using the Kotlin uploader.",
                    )
                    null
                }
                else -> f
            }
        }

        // Layer 2: auto-download the pinned floor version, then let the CLI
        // keep itself current. We download exactly [DEFAULT_VERSION] (or the
        // configured `cliVersion`); discovering and adopting a newer
        // same-major release is delegated to the CLI's own
        // `update --max-age 12h` (see maybeSelfUpdate) — the plugin no longer
        // re-implements any version discovery.
        val version = cliVersion?.takeIf { it.isNotBlank() } ?: DEFAULT_VERSION
        val triple = detectHostTriple()
        if (triple == null) {
            logger.warn(
                "Bugsee: no published bugsee-cli binary for host OS=${os()} arch=${arch()}; " +
                    "set bugsee.cliPath to a locally-built binary, or fall back to the " +
                    "Kotlin uploader.",
            )
            return null
        }

        val cacheRoot = File(gradleUserHome, "caches/bugsee-cli/$version/$triple")
        val binaryName = if (triple.contains("windows")) "bugsee-cli.exe" else "bugsee-cli"
        val cachedBinary = File(cacheRoot, binaryName)

        // Fast path: cache hit.
        if (cachedBinary.isFile && cachedBinary.canExecute()) {
            if (debug) logger.warn("Bugsee: bugsee-cli cache hit at ${cachedBinary.absolutePath}")
            return maybeSelfUpdate(cachedBinary, autoUpdate, execOps, logger, debug)
        }

        return try {
            val binary = downloadAndExtract(
                version, triple, cacheRoot, binaryName, execOps, logger, debug,
            )
            maybeSelfUpdate(binary, autoUpdate, execOps, logger, debug)
        } catch (e: Throwable) {
            logger.warn(
                "Bugsee: failed to download bugsee-cli v$version for $triple: ${e.message}; " +
                    "using the Kotlin uploader.",
            )
            null
        }
    }

    /**
     * Let the CLI keep itself current. When [autoUpdate] is on, runs
     * `bugsee-cli update --max-age 12h` on [binary]. The CLI owns
     * EVERYTHING about updating: it discovers the newest same-major version,
     * caps at the same major (non-breaking), downloads + SHA-256-verifies,
     * and atomically self-replaces in place — so after this returns the SAME
     * [binary] path holds the (possibly newer) version. `--max-age 12h` makes
     * the CLI throttle internally (records a last-check timestamp next to the
     * binary and no-ops if checked within the window) and treat ANY failure
     * (offline, missing pointer, download/permission error) as best-effort,
     * exiting 0.
     *
     * Because the CLI is the single source of truth for the throttle /
     * best-effort / self-replace semantics, the plugin just invokes it. The
     * call is wrapped so it can NEVER fail resolution: a thrown exec error
     * (e.g. the binary went away mid-build) is swallowed at DEBUG and the
     * original [binary] is still returned.
     */
    private fun maybeSelfUpdate(
        binary: File,
        autoUpdate: Boolean,
        execOps: ExecOperations,
        logger: Logger,
        debug: Boolean,
    ): File {
        if (!autoUpdate) return binary
        try {
            if (debug) {
                logger.warn("Bugsee: bugsee-cli self-update check (${binary.absolutePath} update --max-age 12h)")
            }
            execOps.exec { spec ->
                spec.executable = binary.absolutePath
                spec.args = listOf("update", "--max-age", "12h")
                spec.isIgnoreExitValue = true
            }
        } catch (e: Throwable) {
            // Self-update is strictly best-effort — never let it break
            // resolution. DEBUG only so quiet/offline builds stay quiet.
            if (debug) {
                logger.warn("Bugsee: bugsee-cli self-update check failed: ${e.message}")
            }
        }
        return binary
    }

    /**
     * Maps the running JVM's `os.name` + `os.arch` to a Rust target triple
     * that matches what `bugsee-cli` is published for. Returns `null` for
     * unsupported combinations (Linux musl, Windows ARM64, anything else).
     */
    internal fun detectHostTriple(): String? = detectHostTriple(os(), arch())

    internal fun detectHostTriple(os: String, arch: String): String? {
        val osLower = os.lowercase()
        val archLower = arch.lowercase()
        val isMac = osLower.contains("mac") || osLower.contains("darwin")
        val isLinux = osLower.contains("linux")
        val isWindows = osLower.contains("windows")
        val isArm64 = archLower in listOf("aarch64", "arm64")
        val isAmd64 = archLower in listOf("x86_64", "amd64", "x64")

        return when {
            isMac && isArm64 -> "aarch64-apple-darwin"
            isMac && isAmd64 -> "x86_64-apple-darwin"
            isLinux && isArm64 -> "aarch64-unknown-linux-gnu"
            isLinux && isAmd64 -> "x86_64-unknown-linux-gnu"
            isWindows && isAmd64 -> "x86_64-pc-windows-msvc"
            else -> null
        }
    }

    private fun os(): String = System.getProperty("os.name") ?: ""
    private fun arch(): String = System.getProperty("os.arch") ?: ""

    /**
     * Returns the artifact URL the plugin should download for a given
     * `(version, triple)` tuple. Visible for testing so a CLI-name rename
     * surfaces as a unit-test failure before any plugin consumer hits it.
     */
    internal fun artifactUrl(version: String, triple: String): String {
        val ext = if (triple.contains("windows")) "zip" else "tar.xz"
        return "$DOWNLOAD_BASE/v$version/bugsee-cli-$triple.$ext"
    }

    private fun downloadAndExtract(
        version: String,
        triple: String,
        cacheRoot: File,
        binaryName: String,
        execOps: ExecOperations,
        logger: Logger,
        debug: Boolean,
    ): File {
        cacheRoot.mkdirs()
        val parentDir = cacheRoot.parentFile ?: error("cache root has no parent: $cacheRoot")
        parentDir.mkdirs()

        val lockFile = File(parentDir, "$triple.lock")
        lockFile.parentFile.mkdirs()

        // File-lock the parent directory of the cache. A second build that
        // enters here while the first is downloading blocks here, then
        // sees the cache hit on re-check.
        java.io.RandomAccessFile(lockFile, "rw").use { raf ->
            raf.channel.lock().use {
                val cachedBinary = File(cacheRoot, binaryName)
                if (cachedBinary.isFile && cachedBinary.canExecute()) {
                    if (debug) logger.warn("Bugsee: cache hit after lock acquire: $cachedBinary")
                    return cachedBinary
                }

                val artifactUrl = artifactUrl(version, triple)
                val sha256Url = "$artifactUrl.sha256"
                val tarballName = artifactUrl.substringAfterLast('/')
                val tarballFile = File(cacheRoot, tarballName)

                logger.warn("Bugsee: downloading bugsee-cli v$version for $triple from $artifactUrl")
                downloadToFile(artifactUrl, tarballFile)

                val expectedSha = parseSha256Sidecar(downloadAsString(sha256Url))
                val actualSha = sha256Hex(tarballFile)
                if (!expectedSha.equals(actualSha, ignoreCase = true)) {
                    tarballFile.delete()
                    throw IOException(
                        "SHA-256 mismatch for $tarballName: expected $expectedSha, got $actualSha",
                    )
                }

                // Extract via system `tar` — handles tar.xz on macOS/Linux and zip on
                // Windows 10+ (libarchive-based bsdtar). Strip the wrapper directory
                // (`bugsee-cli-<triple>/...`) so the binary lands directly in cacheRoot.
                val extractResult = execOps.exec { spec ->
                    spec.executable = "tar"
                    spec.args = listOf(
                        "-xf",
                        tarballFile.absolutePath,
                        "-C",
                        cacheRoot.absolutePath,
                        "--strip-components=1",
                    )
                    spec.isIgnoreExitValue = true
                }
                if (extractResult.exitValue != 0) {
                    throw IOException(
                        "tar -xf $tarballName failed with exit code ${extractResult.exitValue}",
                    )
                }

                tarballFile.delete()

                if (!cachedBinary.isFile) {
                    throw IOException(
                        "extraction completed but $binaryName not found in $cacheRoot",
                    )
                }
                cachedBinary.setExecutable(true)
                logger.warn("Bugsee: bugsee-cli v$version installed at ${cachedBinary.absolutePath}")
                return cachedBinary
            }
        }
    }

    /**
     * Parse a sha256sum-style sidecar (`<hex>  <filename>` or `<hex> *<filename>`).
     * Returns just the hex digest.
     */
    internal fun parseSha256Sidecar(text: String): String {
        return text.trim().substringBefore(' ').substringBefore('\t').trim()
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun downloadToFile(url: String, dest: File) {
        val conn = openConnection(url)
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun downloadAsString(url: String): String {
        val conn = openConnection(url)
        val bos = ByteArrayOutputStream()
        conn.inputStream.use { it.copyTo(bos) }
        return bos.toString(Charsets.UTF_8)
    }

    private fun openConnection(url: String): java.net.HttpURLConnection {
        val conn = URI(url).toURL().openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        if (conn.responseCode !in 200..299) {
            val body = conn.errorStream?.bufferedReader()?.readText()?.take(200).orEmpty()
            throw IOException("GET $url returned ${conn.responseCode}: $body")
        }
        return conn
    }

    @Suppress("unused") // for future use if we ever need to bound wait time on the lock
    private val lockAcquireTimeoutMs: Long = TimeUnit.MINUTES.toMillis(2)
}
