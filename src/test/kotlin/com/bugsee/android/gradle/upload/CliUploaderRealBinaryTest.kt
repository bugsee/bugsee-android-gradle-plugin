package com.bugsee.android.gradle.upload

import org.gradle.api.internal.project.ProjectInternal
import org.gradle.process.ExecOperations
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * REAL-BINARY integration test for the plugin↔CLI contract. Unlike
 * [CliUploaderTest] (which only pins the *argv* the plugin constructs), this
 * class actually EXECS a real `bugsee-cli` binary and asserts the observable
 * outcome:
 *
 *  - `pack` produces a valid normalized upload ZIP (artefact STORED, mapping
 *    entry present) — purely local, no network.
 *  - `pack` fails (returns false) for a missing artefact.
 *  - [CliBinaryResolver.resolve] accepts an explicit executable path and
 *    rejects a non-executable one.
 *  - `upload build` performs the full two-stage converged upload (registration
 *    POST → presigned artefact PUT) against an in-process [MockBuildsServer].
 *
 * This is the test a Gerrit CI lane runs with `BUGSEE_CLI_BIN` pointed at a
 * freshly-built `bugsee-cli` (see `scripts/integration-test.sh`). It STAYS
 * GREEN offline: when no real binary is provided every test `Assume`-skips, so
 * the default `test` task is never red just because the CLI wasn't built.
 *
 * Provide the binary via the `bugsee.cli.bin` system property or the
 * `BUGSEE_CLI_BIN` environment variable; the path must point at a regular,
 * executable file.
 *
 * ZIP verification is done with a hand-rolled central-directory parser
 * ([zipEntryMethods] / [storedEntryBytes]) rather than `java.util.zip.ZipFile`:
 * the CLI compresses the `mapping.txt` entry with zstd (method 93), and the
 * JDK's `ZipFile`/`ZipInputStream` reject the whole archive on an unknown
 * compression method. The parser reads the ZIP structure directly so it can
 * see every entry's name + method and extract STORED (verbatim) entries.
 */
class CliUploaderRealBinaryTest {

    private val logger = ProjectBuilder.builder().build().logger

    /** Real [ExecOperations] from a throwaway Gradle project. */
    private fun realExecOps(): ExecOperations {
        val project = ProjectBuilder.builder().build()
        return (project as ProjectInternal).services.get(ExecOperations::class.java)
    }

    /**
     * The real `bugsee-cli` binary to test against, or `null` if none was
     * provided / the path isn't an executable file. Tests `Assume`-skip on
     * `null` so the suite stays green offline.
     */
    private fun realBinary(): File? {
        val raw = System.getProperty("bugsee.cli.bin") ?: System.getenv("BUGSEE_CLI_BIN")
        return raw?.let(::File)?.takeIf { it.isFile && it.canExecute() }
    }

    private fun requireBinary(): File {
        val bin = realBinary()
        assumeTrue("no bugsee-cli binary; set BUGSEE_CLI_BIN or -Dbugsee.cli.bin=", bin != null)
        return bin!!
    }

    // ── a. pack happy path (offline) ─────────────────────────────────

    @Test
    fun `packUploadZip produces a valid upload ZIP with the artefact STORED and a mapping entry`() {
        val bin = requireBinary()
        val tmp = Files.createTempDirectory("bugsee-pack-ok").toFile()
        try {
            val artifactBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "fake apk payload".toByteArray()
            val artifact = File(tmp, "app.apk").apply { writeBytes(artifactBytes) }
            val mapping = File(tmp, "mapping.txt").apply {
                writeText("a -> b:\nc.d.E -> a.b.C:\nf() -> g:\n")
            }
            val outZip = File(tmp, "upload.zip")

            val ok = CliUploader.packUploadZip(
                execOps = realExecOps(),
                cliBinary = bin,
                artifactFile = artifact,
                mappingFile = mapping,
                outZip = outZip,
                logger = logger,
                debug = true,
            )

            assertTrue(ok, "packUploadZip must return true for a real CLI pack of valid inputs")
            assertTrue(outZip.isFile && outZip.length() > 0L, "outZip must exist and be non-empty")

            val methods = zipEntryMethods(outZip.readBytes())
            // The artefact must be STORED (method 0): it's already a compressed
            // container, re-deflating only burns CPU/size.
            assertEquals(
                METHOD_STORED,
                methods["app.apk"],
                "upload ZIP must contain the artefact 'app.apk' STORED (method 0); methods=$methods",
            )
            // The mapping entry must be present (the CLI zstd-compresses it,
            // method 93 — we only require its presence, not a specific method).
            assertTrue(
                methods.containsKey("mapping.txt"),
                "upload ZIP must contain a 'mapping.txt' entry; methods=$methods",
            )
            // The STORED artefact bytes must be the verbatim input.
            assertContentEquals(
                artifactBytes,
                storedEntryBytes(outZip.readBytes(), "app.apk"),
                "STORED artefact bytes must equal the input",
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ── b. pack sad path (offline) ───────────────────────────────────

    @Test
    fun `packUploadZip returns false when the artefact does not exist`() {
        val bin = requireBinary()
        val tmp = Files.createTempDirectory("bugsee-pack-bad").toFile()
        try {
            val missingArtifact = File(tmp, "does-not-exist.apk")
            assertFalse(missingArtifact.exists(), "precondition: artefact must be absent")
            val outZip = File(tmp, "upload.zip")

            val ok = CliUploader.packUploadZip(
                execOps = realExecOps(),
                cliBinary = bin,
                artifactFile = missingArtifact,
                mappingFile = null,
                outZip = outZip,
                logger = logger,
                debug = true,
            )

            assertFalse(ok, "packUploadZip must return false when the CLI exits non-zero")
            assertFalse(outZip.isFile, "no output ZIP should be produced for a failed pack")
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ── c. CliBinaryResolver.resolve with an explicit path ───────────

    @Test
    fun `resolve returns the binary for an explicit executable cliPath and null for a non-executable one`() {
        val bin = requireBinary()
        val tmp = Files.createTempDirectory("bugsee-resolve").toFile()
        try {
            val gradleUserHome = File(tmp, "gradle-home").apply { mkdirs() }

            // Executable path → returned verbatim.
            val resolved = CliBinaryResolver.resolve(
                cliVersion = null,
                cliPath = bin.absolutePath,
                execOps = realExecOps(),
                gradleUserHome = gradleUserHome,
                logger = logger,
                debug = true,
            )
            assertNotNull(resolved, "explicit executable cliPath must resolve to a File")
            assertEquals(
                bin.absoluteFile,
                resolved.absoluteFile,
                "resolved binary must equal the cliPath provided",
            )

            // Non-executable path → null (so the task falls back, not fails).
            val nonExec = File(tmp, "not-executable.bin").apply {
                writeText("#!/bin/sh\necho hi\n")
                setExecutable(false, false)
            }
            assumeTrue(
                "filesystem could not clear the executable bit; cannot exercise the non-exec branch",
                !nonExec.canExecute(),
            )
            val resolvedNonExec = CliBinaryResolver.resolve(
                cliVersion = null,
                cliPath = nonExec.absolutePath,
                execOps = realExecOps(),
                gradleUserHome = gradleUserHome,
                logger = logger,
                debug = true,
            )
            assertNull(resolvedNonExec, "a non-executable cliPath must resolve to null")
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ── d. uploadBuild full real round-trip (the key integration) ────

    @Test
    fun `uploadBuild performs the full registration POST plus presigned artefact PUT against the mock`() {
        val bin = requireBinary()
        val tmp = Files.createTempDirectory("bugsee-upload-build").toFile()
        val server = MockBuildsServer(appToken = "tok")
        server.start()
        try {
            val payload = File(tmp, "payload.json").apply {
                writeText("""{"version":"1.0","build":"1"}""")
            }
            val artifactBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "real round-trip artefact".toByteArray()
            val artifact = File(tmp, "app.aab").apply { writeBytes(artifactBytes) }
            val mapping = File(tmp, "mapping.txt").apply {
                writeText("a -> b:\nfoo.Bar -> a.b:\n")
            }

            val result = CliUploader.uploadBuild(
                execOps = realExecOps(),
                cliBinary = bin,
                endpoint = server.baseUrl,
                appToken = "tok",
                payloadJsonFile = payload,
                artifactFile = artifact,
                mappingFile = mapping,
                depsJsonFile = null,
                timingsJsonFile = null,
                chunked = false,
                logger = logger,
                debug = true,
            )

            assertTrue(result.success, "uploadBuild must succeed against the mock; result=$result")
            assertEquals(0, result.exitCode, "a successful CLI run exits 0; result=$result")
            assertFalse(result.shouldFallback, "success must not request a fallback; result=$result")

            // Stage 1: the registration POST landed, on the right path, with the
            // CLI-injected request_artifact_upload flag and the producer payload.
            val recorded = server.recordedRequests()
            val registration = recorded.singleOrNull {
                it.method == "POST" && it.path == "/v2/apps/tok/builds"
            }
            assertNotNull(
                registration,
                "exactly one registration POST to /v2/apps/tok/builds expected; got: " +
                    recorded.joinToString { "${it.method} ${it.path}" },
            )
            val regBody = String(registration.body, Charsets.UTF_8)
            assertTrue(
                regBody.contains("request_artifact_upload"),
                "registration body must carry the CLI-injected request_artifact_upload flag; body=$regBody",
            )
            assertTrue(
                regBody.contains("\"version\""),
                "registration body must preserve the producer's payload; body=$regBody",
            )

            // Stage 2: exactly one presigned artefact PUT landed.
            val puts = recorded.filter { it.method == "PUT" && it.path.startsWith("/single-put/") }
            assertEquals(
                1,
                puts.size,
                "exactly one presigned artefact PUT expected; got: " +
                    recorded.joinToString { "${it.method} ${it.path}" },
            )
            val uploadedZipBytes = server.storedSinglePuts()["artefact"]
            assertNotNull(uploadedZipBytes, "the mock must have captured the presigned artefact PUT body")
            assertTrue(uploadedZipBytes.isNotEmpty(), "the uploaded ZIP body must be non-empty")

            // The PUT body is the normalized upload ZIP: confirm the artefact
            // arrived verbatim (STORED) and the mapping entry is present, proving
            // the bytes traversed the real pack → register → PUT pipeline.
            val methods = zipEntryMethods(uploadedZipBytes)
            assertEquals(
                METHOD_STORED,
                methods["app.aab"],
                "uploaded ZIP must contain the artefact 'app.aab' STORED (method 0); methods=$methods",
            )
            assertTrue(
                methods.containsKey("mapping.txt"),
                "uploaded ZIP must contain the 'mapping.txt' entry; methods=$methods",
            )
            assertContentEquals(
                artifactBytes,
                storedEntryBytes(uploadedZipBytes, "app.aab"),
                "the artefact bytes that reached the mock must equal what was uploaded",
            )
        } finally {
            server.stop()
            tmp.deleteRecursively()
        }
    }

    // ── ZIP central-directory parser (tolerates unknown methods) ─────

    private companion object {
        private const val METHOD_STORED = 0

        private const val EOCD_SIG = 0x06054b50L
        private const val CDH_SIG = 0x02014b50L

        private fun u16(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

        private fun u32(b: ByteArray, o: Int): Long =
            (b[o].toLong() and 0xff) or
                ((b[o + 1].toLong() and 0xff) shl 8) or
                ((b[o + 2].toLong() and 0xff) shl 16) or
                ((b[o + 3].toLong() and 0xff) shl 24)

        /** Offset of the End-Of-Central-Directory record (scanned from the end). */
        private fun findEocd(data: ByteArray): Int {
            var i = data.size - 22
            while (i >= 0) {
                if (u32(data, i) == EOCD_SIG) return i
                i--
            }
            error("ZIP has no EOCD record")
        }

        /**
         * Maps every central-directory entry name to its compression method
         * (0 = STORED, 8 = DEFLATE, 93 = zstd, …). Reads the central directory
         * directly, so unknown methods don't abort the parse the way
         * `java.util.zip.ZipFile` does.
         */
        fun zipEntryMethods(data: ByteArray): Map<String, Int> {
            val eocd = findEocd(data)
            val total = u16(data, eocd + 10)
            var p = u32(data, eocd + 16).toInt()
            val out = LinkedHashMap<String, Int>(total)
            repeat(total) {
                require(u32(data, p) == CDH_SIG) { "bad central-directory header at $p" }
                val method = u16(data, p + 10)
                val nlen = u16(data, p + 28)
                val elen = u16(data, p + 30)
                val clen = u16(data, p + 32)
                val name = String(data, p + 46, nlen, Charsets.UTF_8)
                out[name] = method
                p += 46 + nlen + elen + clen
            }
            return out
        }

        /**
         * Returns the verbatim bytes of a STORED central-directory entry named
         * [name]. Fails if the entry is absent or not STORED (a non-STORED entry
         * can't be returned verbatim by this parser).
         */
        fun storedEntryBytes(data: ByteArray, name: String): ByteArray {
            val eocd = findEocd(data)
            val total = u16(data, eocd + 10)
            var p = u32(data, eocd + 16).toInt()
            repeat(total) {
                require(u32(data, p) == CDH_SIG) { "bad central-directory header at $p" }
                val method = u16(data, p + 10)
                val compSize = u32(data, p + 20).toInt()
                val nlen = u16(data, p + 28)
                val elen = u16(data, p + 30)
                val clen = u16(data, p + 32)
                val lho = u32(data, p + 42).toInt()
                val entryName = String(data, p + 46, nlen, Charsets.UTF_8)
                if (entryName == name) {
                    require(method == METHOD_STORED) { "entry '$name' is not STORED (method=$method)" }
                    // Local file header: 30 bytes fixed + local name + local extra.
                    val lnlen = u16(data, lho + 26)
                    val lelen = u16(data, lho + 28)
                    val dataStart = lho + 30 + lnlen + lelen
                    return data.copyOfRange(dataStart, dataStart + compSize)
                }
                p += 46 + nlen + elen + clen
            }
            error("ZIP has no entry named '$name'")
        }
    }
}
