package com.bugsee.android.gradle.upload

import org.gradle.api.internal.project.ProjectInternal
import org.gradle.process.ExecOperations
import org.gradle.testfixtures.ProjectBuilder
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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

    // ── e. uploadBuild --chunked full real round-trip ────────────────

    @Test
    fun `uploadBuild chunked drives the chunked protocol and reconstructs the artefact from chunk PUTs`() {
        val bin = requireBinary()
        val tmp = Files.createTempDirectory("bugsee-upload-chunked").toFile()
        val server = MockBuildsServer(appToken = "tok")
        // Tiny chunk size so a small artefact still splits into multiple chunks,
        // exercising the chunk-slice / per-chunk-PUT / reconstruct path. (S3's
        // 5 MiB floor doesn't apply to this mock — it just stores bytes.)
        server.setChunkOptions(chunkSize = 16, maxChunks = 4096)
        server.setBuildIdResponse("chunked-build-99")
        server.start()
        try {
            val payload = File(tmp, "payload.json").apply {
                writeText("""{"version":"2.0","build":"7"}""")
            }
            // Make the artefact comfortably larger than one chunk so we get
            // several chunk PUTs to reconstruct.
            val artifactBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) +
                ("chunked artefact payload that spans many sixteen-byte chunks " +
                    "to force a multi-chunk upload").toByteArray()
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
                chunked = true,
                logger = logger,
                debug = true,
            )

            assertTrue(result.success, "chunked uploadBuild must succeed against the mock; result=$result")
            assertEquals(0, result.exitCode, "a successful CLI run exits 0; result=$result")
            assertFalse(result.shouldFallback, "success must not request a fallback; result=$result")

            val recorded = server.recordedRequests()
            // Chunked protocol markers: chunk-options GET + chunks/check POST +
            // the final chunked submit POST all landed on the v2 build paths.
            assertEquals(
                1,
                recorded.countMatching("GET", "/builds/chunk-options"),
                "exactly one chunk-options GET expected; got: " +
                    recorded.joinToString { "${it.method} ${it.path}" },
            )
            assertEquals(
                1,
                recorded.countMatching("POST", "/builds/chunks/check"),
                "exactly one chunks/check POST expected; got: " +
                    recorded.joinToString { "${it.method} ${it.path}" },
            )
            val submit = recorded.singleOrNull {
                it.method == "POST" && it.path == "/v2/apps/tok/builds/chunked"
            }
            assertNotNull(
                submit,
                "exactly one chunked submit POST to /v2/apps/tok/builds/chunked expected; got: " +
                    recorded.joinToString { "${it.method} ${it.path}" },
            )

            // The submit body carries the producer payload + the CLI-injected
            // artifact-upload flag + the ordered `chunks` sha1 list.
            val submitJson = JSONObject(String(submit.body, Charsets.UTF_8))
            assertTrue(
                submitJson.optBoolean("request_artifact_upload", false),
                "chunked submit must carry request_artifact_upload=true; body=${String(submit.body)}",
            )
            assertEquals(
                "2.0",
                submitJson.optString("version"),
                "chunked submit must preserve the producer payload; body=${String(submit.body)}",
            )
            val chunksArray = submitJson.getJSONArray("chunks")
            assertTrue(chunksArray.length() > 1, "artefact must have split into >1 chunk; chunks=$chunksArray")

            // At least one chunk PUT landed (a fresh server has no present chunks,
            // so every chunk is missing and uploaded).
            val chunkPuts = recorded.filter { it.method == "PUT" && it.path.startsWith("/chunk-store/") }
            assertTrue(chunkPuts.isNotEmpty(), "at least one chunk PUT expected; got: " +
                recorded.joinToString { "${it.method} ${it.path}" })

            // Reconstruct the artefact ZIP from the captured chunk bytes, in the
            // exact order the submit body declared, and prove it is the real
            // normalized upload ZIP: artefact STORED verbatim + mapping present.
            val stored = server.storedChunks()
            val reconstructed = ByteArrayOutputStream().use { out ->
                for (i in 0 until chunksArray.length()) {
                    val sha = chunksArray.getString(i)
                    val chunk = stored[sha]
                    assertNotNull(chunk, "chunk $sha referenced by submit body was never PUT")
                    out.write(chunk)
                }
                out.toByteArray()
            }
            val methods = zipEntryMethods(reconstructed)
            assertEquals(
                METHOD_STORED,
                methods["app.aab"],
                "reconstructed upload ZIP must contain the artefact 'app.aab' STORED; methods=$methods",
            )
            assertTrue(
                methods.containsKey("mapping.txt"),
                "reconstructed upload ZIP must contain the 'mapping.txt' entry; methods=$methods",
            )
            assertContentEquals(
                artifactBytes,
                storedEntryBytes(reconstructed, "app.aab"),
                "the artefact bytes reconstructed from chunk PUTs must equal the input",
            )
        } finally {
            server.stop()
            tmp.deleteRecursively()
        }
    }

    // ── f. uploadBuild with deps + timings sidecars ──────────────────

    @Test
    fun `uploadBuild with deps and timings ships the build-info bundle from the same registration`() {
        val bin = requireBinary()
        val tmp = Files.createTempDirectory("bugsee-upload-build-info-side").toFile()
        val server = MockBuildsServer(appToken = "tok")
        server.start()
        try {
            val payload = File(tmp, "payload.json").apply {
                writeText("""{"version":"3.0","build":"11"}""")
            }
            val artifactBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "sidecar artefact".toByteArray()
            val artifact = File(tmp, "app.aab").apply { writeBytes(artifactBytes) }
            val deps = File(tmp, "dependencies.json").apply {
                writeText("""{"dependencies":[{"name":"foo","version":"1.0"}]}""")
            }
            val timings = File(tmp, "timings.json").apply {
                writeText("""{"tasks":[{"name":":app:compile","durationMs":1234}]}""")
            }

            val result = CliUploader.uploadBuild(
                execOps = realExecOps(),
                cliBinary = bin,
                endpoint = server.baseUrl,
                appToken = "tok",
                payloadJsonFile = payload,
                artifactFile = artifact,
                mappingFile = null,
                depsJsonFile = deps,
                timingsJsonFile = timings,
                chunked = false,
                logger = logger,
                debug = true,
            )

            assertTrue(result.success, "uploadBuild with sidecars must succeed; result=$result")
            assertEquals(0, result.exitCode, "a successful CLI run exits 0; result=$result")
            assertFalse(result.shouldFallback, "success must not request a fallback; result=$result")

            val recorded = server.recordedRequests()
            // Exactly ONE registration POST (the build-info bundle ships from the
            // SAME registration — no second POST).
            val registrations = recorded.filter {
                it.method == "POST" && it.path == "/v2/apps/tok/builds"
            }
            assertEquals(
                1,
                registrations.size,
                "exactly one registration POST expected (build-info rides the same one); got: " +
                    recorded.joinToString { "${it.method} ${it.path}" },
            )
            val regBody = String(registrations.single().body, Charsets.UTF_8)
            val regJson = JSONObject(regBody)
            assertTrue(
                regJson.optBoolean("request_artifact_upload", false),
                "registration must carry request_artifact_upload=true; body=$regBody",
            )
            // Sidecars present → the CLI must inject request_build_info_upload so
            // the server signs the build-info endpoint.
            assertTrue(
                regJson.optBoolean("request_build_info_upload", false),
                "registration must carry request_build_info_upload=true when deps/timings present; body=$regBody",
            )

            // Artefact PUT landed at the artefact single-put store.
            assertNotNull(
                server.storedSinglePuts()["artefact"],
                "artefact PUT must have landed at /single-put/artefact",
            )

            // The build-info bundle PUT landed at /single-put/build-info, and the
            // bundle is a ZIP carrying dependencies.json + timings.json.
            val biBytes = server.storedSinglePuts()["build-info"]
            assertNotNull(
                biBytes,
                "build-info bundle PUT must have landed at /single-put/build-info; puts: " +
                    server.storedSinglePuts().keys.joinToString(),
            )
            assertTrue(biBytes.isNotEmpty(), "build-info bundle body must be non-empty")
            val biMethods = zipEntryMethods(biBytes)
            assertTrue(
                biMethods.containsKey("dependencies.json"),
                "build-info bundle must contain 'dependencies.json'; methods=$biMethods",
            )
            assertTrue(
                biMethods.containsKey("timings.json"),
                "build-info bundle must contain 'timings.json'; methods=$biMethods",
            )
        } finally {
            server.stop()
            tmp.deleteRecursively()
        }
    }

    // ── g. uploadBuildInfo standalone (self-contained registration) ──

    @Test
    fun `uploadBuildInfo standalone presigned PUTs the bundle to the build-info upload endpoint`() {
        val bin = requireBinary()
        val tmp = Files.createTempDirectory("bugsee-upload-build-info").toFile()
        val server = MockBuildsServer(appToken = "tok")
        server.start()
        try {
            // The plugin's standalone build-info flow already registered the build
            // and received the presigned build-info endpoint. We mint that URL
            // directly off the mock so the CLI PUTs the bundle to it. (Standalone
            // pre-signed mode: --upload-url, no second registration POST.)
            val uploadUrl = "${server.baseUrl}/single-put/build-info"
            val deps = File(tmp, "dependencies.json").apply {
                writeText("""{"dependencies":[{"name":"bar","version":"2.0"}]}""")
            }
            val timings = File(tmp, "timings.json").apply {
                writeText("""{"tasks":[{"name":":lib:test","durationMs":42}]}""")
            }

            val result = CliUploader.uploadBuildInfo(
                execOps = realExecOps(),
                cliBinary = bin,
                uploadUrl = uploadUrl,
                depsJsonFile = deps,
                timingsJsonFile = timings,
                logger = logger,
                debug = true,
            )

            assertTrue(result.success, "uploadBuildInfo must succeed against the mock; result=$result")
            assertEquals(0, result.exitCode, "a successful CLI run exits 0; result=$result")
            assertFalse(result.shouldFallback, "success must not request a fallback; result=$result")

            val recorded = server.recordedRequests()
            // Pre-signed mode: NO registration POST — the only request is the
            // bundle PUT to the presigned build-info URL.
            assertTrue(
                recorded.none { it.method == "POST" && it.path == "/v2/apps/tok/builds" },
                "standalone presigned build-info must NOT register a build; got: " +
                    recorded.joinToString { "${it.method} ${it.path}" },
            )
            val puts = recorded.filter { it.method == "PUT" && it.path == "/single-put/build-info" }
            assertEquals(
                1,
                puts.size,
                "exactly one build-info bundle PUT to /single-put/build-info expected; got: " +
                    recorded.joinToString { "${it.method} ${it.path}" },
            )

            val biBytes = server.storedSinglePuts()["build-info"]
            assertNotNull(biBytes, "the mock must have captured the build-info bundle PUT body")
            assertTrue(biBytes.isNotEmpty(), "build-info bundle body must be non-empty")
            val biMethods = zipEntryMethods(biBytes)
            assertTrue(
                biMethods.containsKey("dependencies.json"),
                "build-info bundle must contain 'dependencies.json'; methods=$biMethods",
            )
            assertTrue(
                biMethods.containsKey("timings.json"),
                "build-info bundle must contain 'timings.json'; methods=$biMethods",
            )
        } finally {
            server.stop()
            tmp.deleteRecursively()
        }
    }

    // ── h. uploadMapping (proguard) presigned symbol round-trip ──────

    @Test
    fun `uploadMapping POSTs proguard symbol metadata and PUTs the mapping zip to the presigned URL`() {
        val bin = requireBinary()
        val tmp = Files.createTempDirectory("bugsee-upload-mapping").toFile()
        val server = MockBuildsServer(appToken = "tok")
        server.start()
        try {
            val mapping = File(tmp, "mapping.txt").apply {
                writeText("a -> b:\ncom.example.Foo -> a.b.C:\n    void bar() -> a:\n")
            }
            // A real R8/Java UUID the plugin would have resolved upstream
            // (BugseeBuildIdResolveTask). The CLI accepts it via --uuid.
            val uuid = "11111111-2222-3333-4444-555555555555"

            val result = CliUploader.uploadMapping(
                execOps = realExecOps(),
                cliBinary = bin,
                mappingFile = mapping,
                iconFile = null,
                appToken = "tok",
                endpoint = server.baseUrl,
                version = "1.2.3",
                build = "456",
                uuid = uuid,
                logger = logger,
                debug = true,
            )

            assertTrue(result.success, "uploadMapping must succeed against the mock; result=$result")
            assertEquals(0, result.exitCode, "a successful CLI run exits 0; result=$result")
            assertFalse(result.shouldFallback, "success must not request a fallback; result=$result")

            val recorded = server.recordedRequests()
            // Stage 1: exactly one metadata POST landed at /apps/tok/symbols (NO
            // /v2 — the presigned-symbol protocol is mounted at /apps/<token>).
            val metaPosts = recorded.filter {
                it.method == "POST" && it.path == "/apps/tok/symbols"
            }
            assertEquals(
                1,
                metaPosts.size,
                "exactly one symbol metadata POST to /apps/tok/symbols expected; got: " +
                    recorded.joinToString { "${it.method} ${it.path}" },
            )
            // The metadata carries the proguard wire fields: uuid + version +
            // build + a SHA-1 hash, and NO transform (proguard, not breakpad).
            val meta = JSONObject(String(metaPosts.single().body, Charsets.UTF_8))
            assertEquals(uuid, meta.optString("uuid"), "metadata uuid must be the supplied --uuid; meta=$meta")
            assertEquals("1.2.3", meta.optString("version"), "metadata version mismatch; meta=$meta")
            assertEquals("456", meta.optString("build"), "metadata build mismatch; meta=$meta")
            assertEquals(
                40,
                meta.optString("hash").length,
                "metadata must carry a 40-char SHA-1 content hash; meta=$meta",
            )
            assertFalse(
                meta.has("transform"),
                "proguard metadata must NOT carry transform=breakpad; meta=$meta",
            )

            // Stage 2: the mapping zip PUT landed at the presigned URL, and its
            // body is a real ZIP carrying mapping.txt.
            val symPut = server.storedSymbolPut()
            assertNotNull(symPut, "the presigned symbol PUT body must have been captured")
            assertTrue(symPut.isNotEmpty(), "the symbol zip body must be non-empty")
            val methods = zipEntryMethods(symPut)
            assertTrue(
                methods.containsKey("mapping.txt"),
                "the uploaded symbol zip must contain 'mapping.txt'; methods=$methods",
            )
        } finally {
            server.stop()
            tmp.deleteRecursively()
        }
    }

    // ── i. uploadElf presigned symbol round-trip ─────────────────────

    @Test
    fun `uploadElf POSTs elf symbol metadata with breakpad transform and PUTs the symbols zip`() {
        val bin = requireBinary()
        val tmp = Files.createTempDirectory("bugsee-upload-elf").toFile()
        val server = MockBuildsServer(appToken = "tok")
        server.start()
        try {
            // The CLI requires --type elf inputs to be a pre-built zip (AGP's
            // native-debug-symbols.zip), which NativeUploadTask supplies. Build a
            // small but valid zip with a fake .so entry.
            val symbolsZip = File(tmp, "native-debug-symbols.zip")
            ZipOutputStream(symbolsZip.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("libfake.so"))
                zos.write("ELF fake native debug symbols".toByteArray())
                zos.closeEntry()
            }
            assertTrue(symbolsZip.length() > 0L, "precondition: the symbols zip must be non-empty")

            // The resolved BUILD_UUID NativeUploadTask passes as --uuid.
            val uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

            val result = CliUploader.uploadElf(
                execOps = realExecOps(),
                cliBinary = bin,
                symbolsZip = symbolsZip,
                appToken = "tok",
                endpoint = server.baseUrl,
                version = "1.2.3",
                build = "456",
                uuid = uuid,
                logger = logger,
                debug = true,
            )

            assertTrue(result.success, "uploadElf must succeed against the mock; result=$result")
            assertEquals(0, result.exitCode, "a successful CLI run exits 0; result=$result")
            assertFalse(result.shouldFallback, "success must not request a fallback; result=$result")

            val recorded = server.recordedRequests()
            val metaPosts = recorded.filter {
                it.method == "POST" && it.path == "/apps/tok/symbols"
            }
            assertEquals(
                1,
                metaPosts.size,
                "exactly one symbol metadata POST to /apps/tok/symbols expected; got: " +
                    recorded.joinToString { "${it.method} ${it.path}" },
            )
            val meta = JSONObject(String(metaPosts.single().body, Charsets.UTF_8))
            assertEquals(uuid, meta.optString("uuid"), "metadata uuid must be the supplied --uuid; meta=$meta")
            assertEquals("1.2.3", meta.optString("version"), "metadata version mismatch; meta=$meta")
            assertEquals("456", meta.optString("build"), "metadata build mismatch; meta=$meta")
            assertEquals(
                40,
                meta.optString("hash").length,
                "metadata must carry a 40-char SHA-1 content hash; meta=$meta",
            )
            // ELF symbols are uploaded with the breakpad transform marker (this is
            // the field that routes them through the native-symbol pipeline).
            assertEquals(
                "breakpad",
                meta.optString("transform"),
                "elf metadata must carry transform=breakpad; meta=$meta",
            )

            // Stage 2: the symbols zip PUT landed verbatim (elf is a pass-through —
            // the CLI PUTs the input zip as-is, no re-pack).
            val symPut = server.storedSymbolPut()
            assertNotNull(symPut, "the presigned symbol PUT body must have been captured")
            assertContentEquals(
                symbolsZip.readBytes(),
                symPut,
                "elf upload is a pass-through; the PUT body must equal the input zip verbatim",
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
