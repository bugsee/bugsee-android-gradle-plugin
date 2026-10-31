package com.bugsee.android.gradle.upload

import org.gradle.api.Action
import org.gradle.api.logging.Logging
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.process.JavaExecSpec
import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pin two CLI/plugin contracts that would silently break the dual-path rollout
 * if they drifted:
 *
 *  1. The argv `CliUploader.buildMappingArgv` / `buildElfArgv` constructs
 *     matches what `bugsee-cli debug-files upload` expects per symbol type.
 *     A rename of any CLI flag flips this test loudly at plugin-CI time
 *     rather than at customer build time.
 *
 *  2. The exit-code → fallback policy stays aligned with the CLI's documented
 *     contract (see `~/Projects/Bugsee/bugsee-cli/src/exit_code.rs`):
 *     codes 1 (Unexpected) and 2 (Usage) → fall back to Kotlin uploader;
 *     codes ≥10 (Input / Config / Upload) → propagate, the fallback would hit
 *     the same error.
 */
class CliUploaderTest {

    private val mappingFile = File("/tmp/mapping.txt")
    private val symbolsZip = File("/tmp/native-debug-symbols.zip")

    // ── argv contract — proguard / mapping ───────────────────────────

    @Test fun `buildMappingArgv without icon produces the documented flag order`() {
        val argv = CliUploader.buildMappingArgv(
            endpoint = "https://api.bugsee.com",
            version = "1.2.3",
            build = "42",
            uuid = "eb642b4e-9fe3-31c6-9a66-dd05ee3516f4",
            mappingFile = mappingFile,
            iconFile = null,
        )
        assertContentEquals(
            listOf(
                "--endpoint", "https://api.bugsee.com",
                "debug-files", "upload",
                "--type", "proguard",
                "--version", "1.2.3",
                "--build", "42",
                "--uuid", "eb642b4e-9fe3-31c6-9a66-dd05ee3516f4",
                File("/tmp/mapping.txt").absolutePath,
            ),
            argv,
        )
    }

    @Test fun `buildMappingArgv with icon appends --icon before the mapping path`() {
        val icon = File("/tmp/ic_launcher.png")
        val argv = CliUploader.buildMappingArgv(
            endpoint = "https://api.bugsee.com",
            version = "1.0",
            build = "1",
            uuid = "00000000-0000-0000-0000-000000000000",
            mappingFile = mappingFile,
            iconFile = icon,
        )
        // The mapping path must remain the LAST positional — clap-side
        // positional parsing in bugsee-cli accepts mapping after all flags.
        val mappingIndex = argv.indexOf(mappingFile.absolutePath)
        val iconValueIndex = argv.indexOf(icon.absolutePath)
        assertEquals(argv.size - 1, mappingIndex, "mapping path must be last positional; argv=$argv")
        assertTrue(iconValueIndex < mappingIndex, "--icon value must precede mapping path; argv=$argv")
        assertEquals("--icon", argv[iconValueIndex - 1], "--icon flag must precede its value; argv=$argv")
    }

    // ── argv contract — elf / native ─────────────────────────────────

    @Test fun `buildElfArgv produces the documented flag order`() {
        val argv = CliUploader.buildElfArgv(
            endpoint = "https://api.bugsee.com",
            version = "1.2.3",
            build = "42",
            uuid = "eb642b4e-9fe3-31c6-9a66-dd05ee3516f4",
            symbolsZip = symbolsZip,
        )
        assertContentEquals(
            listOf(
                "--endpoint", "https://api.bugsee.com",
                "debug-files", "upload",
                "--type", "elf",
                "--version", "1.2.3",
                "--build", "42",
                "--uuid", "eb642b4e-9fe3-31c6-9a66-dd05ee3516f4",
                File("/tmp/native-debug-symbols.zip").absolutePath,
            ),
            argv,
        )
    }

    @Test fun `buildElfArgv adds --force only when requested`() {
        fun argv(force: Boolean) = CliUploader.buildElfArgv(
            endpoint = "https://api.bugsee.com",
            version = "1.0",
            build = "1",
            uuid = "00000000-0000-0000-0000-000000000000",
            symbolsZip = symbolsZip,
            force = force,
        )
        assertFalse(argv(false).contains("--force"))
        assertContentEquals(
            listOf("--type", "elf", "--force", "--version"),
            argv(true).subList(argv(true).indexOf("--type"), argv(true).indexOf("--type") + 4),
        )
    }

    @Test fun `buildElfArgv passes a library directory as the input path`() {
        val dir = File("/tmp/merged_native_libs/release")
        val argv = CliUploader.buildElfArgv(
            endpoint = "https://api.bugsee.com",
            version = "1.0",
            build = "1",
            uuid = "00000000-0000-0000-0000-000000000000",
            symbolsZip = dir,
        )
        assertEquals(dir.absolutePath, argv.last())
        assertFalse(argv.contains("--force"))
    }

    @Test fun `buildElfArgv does NOT include --icon (CLI rejects icon for elf)`() {
        // The CLI returns ConfigInvalid (exit 20) if --icon is paired with
        // --type elf. The Kotlin side must never send the flag for ELF; pin
        // that here so a future copy/paste from buildMappingArgv doesn't
        // silently start sending --icon and breaking native uploads.
        val argv = CliUploader.buildElfArgv(
            endpoint = "https://api.bugsee.com",
            version = "1.0",
            build = "1",
            uuid = "00000000-0000-0000-0000-000000000000",
            symbolsZip = symbolsZip,
        )
        assertFalse(argv.contains("--icon"), "ELF argv must NEVER contain --icon; argv=$argv")
    }

    // ── argv contract — build-info bundle (pre-signed mode) ──────────

    private val depsJson = File("/tmp/dependencies.json")
    private val timingsJson = File("/tmp/timings.json")

    @Test fun `buildBuildInfoArgv with deps and timings produces the documented flag order`() {
        val argv = CliUploader.buildBuildInfoArgv(
            uploadUrl = "https://s3.example/final/build-info/abc-tid.zip?sig=…",
            depsJsonFile = depsJson,
            timingsJsonFile = timingsJson,
        )
        assertContentEquals(
            listOf(
                "upload", "build-info",
                "--upload-url", "https://s3.example/final/build-info/abc-tid.zip?sig=…",
                "--deps", File("/tmp/dependencies.json").absolutePath,
                "--timings", File("/tmp/timings.json").absolutePath,
            ),
            argv,
        )
    }

    @Test fun `buildBuildInfoArgv omits --deps when no deps file is collected`() {
        val argv = CliUploader.buildBuildInfoArgv(
            uploadUrl = "https://s3.example/u",
            depsJsonFile = null,
            timingsJsonFile = timingsJson,
        )
        assertFalse(argv.contains("--deps"), "no --deps flag when deps file is null; argv=$argv")
        assertTrue(argv.contains("--timings"), "timings still present; argv=$argv")
    }

    @Test fun `buildBuildInfoArgv omits --timings when no timings file is collected`() {
        val argv = CliUploader.buildBuildInfoArgv(
            uploadUrl = "https://s3.example/u",
            depsJsonFile = depsJson,
            timingsJsonFile = null,
        )
        assertTrue(argv.contains("--deps"), "deps still present; argv=$argv")
        assertFalse(argv.contains("--timings"), "no --timings flag when timings file is null; argv=$argv")
    }

    @Test fun `buildBuildInfoArgv is pre-signed mode — no --endpoint or --app-token`() {
        // Pre-signed mode PUTs directly to the URL the plugin already
        // obtained from its own /builds POST; sending --endpoint /
        // --app-token would (harmlessly) imply a second registration the
        // CLI must not do. Pin their absence so a copy/paste from the
        // symbol argv builders doesn't silently reintroduce them.
        val argv = CliUploader.buildBuildInfoArgv(
            uploadUrl = "https://s3.example/u",
            depsJsonFile = depsJson,
            timingsJsonFile = timingsJson,
        )
        assertFalse(argv.contains("--endpoint"), "build-info pre-signed argv must NOT carry --endpoint; argv=$argv")
        assertFalse(argv.contains("--app-token"), "build-info pre-signed argv must NOT carry --app-token; argv=$argv")
    }

    // ── argv contract — pack (upload-ZIP builder) ────────────────────

    @Test fun `buildPackArgv with mapping produces the documented flag order`() {
        val argv = CliUploader.buildPackArgv(
            artifactFile = File("/tmp/app.apk"),
            mappingFile = File("/tmp/mapping.txt"),
            outZip = File("/tmp/upload.zip"),
        )
        assertContentEquals(
            listOf(
                "pack",
                "--artifact", File("/tmp/app.apk").absolutePath,
                "--mapping", File("/tmp/mapping.txt").absolutePath,
                "--out", File("/tmp/upload.zip").absolutePath,
            ),
            argv,
        )
    }

    @Test fun `buildPackArgv omits --mapping for a non-obfuscated build`() {
        val argv = CliUploader.buildPackArgv(
            artifactFile = File("/tmp/app.aab"),
            mappingFile = null,
            outZip = File("/tmp/upload.zip"),
        )
        assertContentEquals(
            listOf("pack", "--artifact", File("/tmp/app.aab").absolutePath, "--out", File("/tmp/upload.zip").absolutePath),
            argv,
        )
        assertFalse(argv.contains("--mapping"), "no --mapping flag when mapping is null; argv=$argv")
    }

    // ── argv contract — upload build (converged build upload) ────────

    @Test fun `buildBuildArgv with all inputs produces the documented flag order`() {
        val argv = CliUploader.buildBuildArgv(
            endpoint = "https://api.bugsee.com",
            payloadJsonFile = File("/tmp/payload.json"),
            artifactFile = File("/tmp/app.aab"),
            mappingFile = File("/tmp/mapping.txt"),
            depsJsonFile = File("/tmp/deps.json"),
            timingsJsonFile = File("/tmp/timings.json"),
            chunked = true,
        )
        assertContentEquals(
            listOf(
                "--endpoint", "https://api.bugsee.com",
                "upload", "build",
                "--payload-json", File("/tmp/payload.json").absolutePath,
                "--artifact", File("/tmp/app.aab").absolutePath,
                "--mapping", File("/tmp/mapping.txt").absolutePath,
                "--deps", File("/tmp/deps.json").absolutePath,
                "--timings", File("/tmp/timings.json").absolutePath,
                "--chunked",
            ),
            argv,
        )
    }

    @Test fun `buildBuildArgv omits optional flags when not supplied`() {
        val argv = CliUploader.buildBuildArgv(
            endpoint = "https://api.bugsee.com",
            payloadJsonFile = File("/tmp/payload.json"),
            artifactFile = File("/tmp/app.apk"),
            mappingFile = null,
            depsJsonFile = null,
            timingsJsonFile = null,
            chunked = false,
        )
        assertContentEquals(
            listOf(
                "--endpoint", "https://api.bugsee.com",
                "upload", "build",
                "--payload-json", File("/tmp/payload.json").absolutePath,
                "--artifact", File("/tmp/app.apk").absolutePath,
            ),
            argv,
        )
        assertFalse(argv.contains("--mapping"), "no --mapping; argv=$argv")
        assertFalse(argv.contains("--deps"), "no --deps; argv=$argv")
        assertFalse(argv.contains("--timings"), "no --timings; argv=$argv")
        assertFalse(argv.contains("--chunked"), "no --chunked; argv=$argv")
    }

    @Test fun `packUploadZip falls back (returns false) when the binary is missing, without exec'ing`() {
        // The most common fallback trigger: no CLI resolvable (pre-activation,
        // offline, unsupported host). packUploadZip must short-circuit to
        // false BEFORE touching ExecOperations, so the caller uses the native
        // packer. The fake exec throws if invoked, proving we never tried.
        val neverExec = object : ExecOperations {
            override fun exec(action: Action<in ExecSpec>): ExecResult =
                throw AssertionError("must not exec when the binary is missing")
            override fun javaexec(action: Action<in JavaExecSpec>): ExecResult =
                throw AssertionError("must not javaexec")
        }
        val result = CliUploader.packUploadZip(
            execOps = neverExec,
            cliBinary = File("/definitely/not/here/bugsee-cli"),
            artifactFile = File("/tmp/app.apk"),
            mappingFile = File("/tmp/mapping.txt"),
            outZip = File("/tmp/out.zip"),
            logger = Logging.getLogger("test"),
            debug = false,
        )
        assertFalse(result, "missing binary must yield false (native fallback)")
    }

    @Test fun `buildPackArgv is local-only — no --endpoint or --app-token`() {
        // Packing writes a local ZIP and does no network I/O; the producer
        // uploads the result itself. Pin the absence of network flags so a
        // copy/paste from an upload argv builder can't reintroduce them.
        val argv = CliUploader.buildPackArgv(
            artifactFile = File("/tmp/app.apk"),
            mappingFile = File("/tmp/mapping.txt"),
            outZip = File("/tmp/upload.zip"),
        )
        assertFalse(argv.contains("--endpoint"), "pack argv must NOT carry --endpoint; argv=$argv")
        assertFalse(argv.contains("--app-token"), "pack argv must NOT carry --app-token; argv=$argv")
    }

    // ── exit-code → fallback contract ───────────────────────────────

    @Test fun `exit 0 does not trigger fallback`() {
        assertFalse(CliUploader.shouldFallback(0), "success must not trigger fallback")
    }

    @Test fun `exit 1 (Unexpected) triggers fallback`() {
        assertTrue(
            CliUploader.shouldFallback(1),
            "Unexpected/unhandled errors are structural — the Kotlin uploader can still succeed",
        )
    }

    @Test fun `exit 2 (Usage) triggers fallback`() {
        assertTrue(
            CliUploader.shouldFallback(2),
            "Usage errors usually indicate a plugin/CLI version mismatch — Kotlin uploader works",
        )
    }

    @Test fun `exit 10 (InputNotFound) does NOT trigger fallback`() {
        assertFalse(
            CliUploader.shouldFallback(10),
            "Input errors are substantive — Kotlin uploader would hit the same missing file",
        )
    }

    @Test fun `exit 11 (InputInvalid) does NOT trigger fallback`() {
        assertFalse(CliUploader.shouldFallback(11), "substantive failure")
    }

    @Test fun `exit 20 (ConfigInvalid) does NOT trigger fallback`() {
        assertFalse(
            CliUploader.shouldFallback(20),
            "Config errors are substantive — same flags would reach the Kotlin uploader",
        )
    }

    @Test fun `exit 21 (AppTokenRejected) does NOT trigger fallback`() {
        assertFalse(
            CliUploader.shouldFallback(21),
            "Server already rejected this app token; Kotlin uploader would get the same answer",
        )
    }

    @Test fun `exit 30 (UploadServer) does NOT trigger fallback`() {
        assertFalse(
            CliUploader.shouldFallback(30),
            "Server-side upload error; the Kotlin path hits the same backend",
        )
    }

    @Test fun `exit 31 (UploadTransport) does NOT trigger fallback`() {
        assertFalse(
            CliUploader.shouldFallback(31),
            "Network-layer error; the Kotlin path has the same network too",
        )
    }

    @Test fun `binary-did-not-run exit codes trigger fallback`() {
        listOf(126, 127, 128, 134, 137, 139, 255, -1, -1073741701, -1073741515, Int.MIN_VALUE).forEach { code ->
            assertTrue(CliUploader.shouldFallback(code), "exit $code means the binary did not run")
        }
    }

    @Test fun `substantive and reserved exit codes 3 and 40 to 125 do NOT trigger fallback`() {
        // Defensive: any future addition to the CLI's exit-code enum lands
        // in a non-fallback bucket by default. A new code that SHOULD trigger
        // fallback must be added explicitly to `CliUploader.shouldFallback`.
        listOf(3, 40, 41, 50, 99, 125).forEach { code ->
            assertFalse(
                CliUploader.shouldFallback(code),
                "exit $code (reserved) must default to substantive-failure; update both sides if needed",
            )
        }
    }

    // ── debug-log redaction ──────────────────────────────────────────

    private val secretToken = "tok_SECRET_0123456789abcdef"

    @Test fun `a real mapping argv carries no app token and redactForLog keeps the rest`() {
        val argv = CliUploader.buildMappingArgv(
            endpoint = "https://api.bugsee.com",
            mappingFile = mappingFile,
            version = "1.2.3",
            build = "45",
            uuid = "uuid-1",
            iconFile = null,
        )
        // The token travels in BUGSEE_APP_TOKEN, never in argv.
        assertFalse(argv.any { it.startsWith("--app-token") }, argv.toString())
        val shown = CliUploader.redactForLog(argv)
        assertFalse(shown.contains(secretToken), shown)
        // Everything else is still there for debugging.
        assertTrue(shown.contains("--endpoint https://api.bugsee.com"), shown)
        assertTrue(shown.contains("--version 1.2.3"), shown)
    }

    @Test fun `redactForLog still masks the value after --app-token (defence in depth)`() {
        val shown = CliUploader.redactForLog(listOf("--endpoint", "https://api.bugsee.com", "--app-token", secretToken, "--version", "1.2.3"))
        assertFalse(shown.contains(secretToken), shown)
        assertTrue(shown.contains("--app-token ***"), shown)
        assertTrue(shown.contains("--version 1.2.3"), shown)
    }

    @Test fun `redactForLog masks the token segment of a plugin-built v2 apps URL`() {
        val url = ApiEndpoint.buildsUrl("https://api.bugsee.com", secretToken, "/info")
        assertTrue(url.contains(secretToken), "precondition: the URL carries the token ($url)")
        val argv = CliUploader.buildBuildInfoArgv(url, depsJsonFile = null, timingsJsonFile = null)
        val shown = CliUploader.redactForLog(argv)
        assertFalse(shown.contains(secretToken), shown)
        assertTrue(shown.contains("/v2/apps/***/builds"), shown)
    }

    @Test fun `redactForLog drops the query of the presigned build-info upload URL`() {
        val signature = "SECRETSIG0123456789"
        val url = "https://s3.example/final/build-info/abc-tid.zip?X-Amz-Signature=$signature&X-Amz-Credential=AKIAEXAMPLE"
        val argv = CliUploader.buildBuildInfoArgv(url, depsJsonFile = null, timingsJsonFile = null)
        assertTrue(argv.contains(url), "precondition: the real argv carries the full URL")
        val shown = CliUploader.redactForLog(argv)
        assertFalse(shown.contains(signature), shown)
        assertFalse(shown.contains("AKIAEXAMPLE"), shown)
        assertTrue(shown.contains("--upload-url https://s3.example/final/build-info/abc-tid.zip?…<redacted>"), shown)
        assertTrue(argv.contains(url), "the argv passed to the process is not modified")
    }

    @Test fun `redactForLog drops the query in the --upload-url=value form too`() {
        val shown = CliUploader.redactForLog(listOf("upload", "build-info", "--upload-url=https://s3.example/k.zip?sig=SECRETSIG"))
        assertEquals("upload build-info --upload-url=https://s3.example/k.zip?…<redacted>", shown)
    }

    @Test fun `redactForLog leaves an upload URL without a query unchanged`() {
        assertEquals(
            "upload build-info --upload-url https://s3.example/u",
            CliUploader.redactForLog(listOf("upload", "build-info", "--upload-url", "https://s3.example/u")),
        )
    }

    @Test fun `redactForLog masks the --app-token=value form and leaves the input argv untouched`() {
        val argv = listOf("upload", "build", "--app-token=$secretToken", "--chunked")
        assertEquals("upload build --app-token=*** --chunked", CliUploader.redactForLog(argv))
        assertEquals("--app-token=$secretToken", argv[2])
    }

    @Test fun `redactForLog does not mask unrelated values or a trailing --app-token`() {
        assertEquals(
            "debug-files upload --version 1.0 --app-token",
            CliUploader.redactForLog(listOf("debug-files", "upload", "--version", "1.0", "--app-token")),
        )
    }

    // ── verifyAndExec exit-code → CliUploadResult mapping ────────────
    //
    // The pure shouldFallback(Int) predicate above is tested, but the
    // END-TO-END mapping in verifyAndExec — exit code → (success,
    // shouldFallback, fallbackReason) — was previously unverified: every
    // fake/real exec in the suite either threw or returned exit 0. These
    // tests drive the real uploadMapping entry point through a fake
    // ExecOperations that returns a configurable exit value (and writes to
    // spec.errorOutput, exercising the stderr-forwarding branch), pinning
    // the full result for representative codes.

    /**
     * A fake [ExecOperations] whose `exec` captures the [ExecSpec]'s
     * `errorOutput`, writes [stderr] into it (exercising the
     * stderr-forwarding branch), and returns [exitValue]. The [ExecSpec]
     * is a [java.lang.reflect.Proxy] that records the `setErrorOutput`
     * call and replays it from `getErrorOutput` — no Mockito needed
     * (not on the test classpath), and no need to implement every method
     * of the wide ExecSpec interface.
     */
    private class FakeExec(
        private val cliExitValue: Int,
        private val stderr: String = "",
        private val stdout: String = "",
    ) : ExecOperations {
        /** The argv and environment the production code gave the process, as recorded from the spec. */
        var recordedArgs: List<String> = emptyList()
        val recordedEnv: MutableMap<String, String> = mutableMapOf()
        var execCount = 0

        override fun exec(action: Action<in ExecSpec>): ExecResult {
            execCount++
            // Capture into a local so the anonymous ExecResult below
            // references THIS value — not its own getExitValue() (a name
            // clash with the Java `exitValue` property → infinite recursion).
            val resultExit = cliExitValue
            var capturedErr: java.io.OutputStream? = null
            var capturedOut: java.io.OutputStream? = null
            val spec = java.lang.reflect.Proxy.newProxyInstance(
                ExecSpec::class.java.classLoader,
                arrayOf(ExecSpec::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "setErrorOutput" -> { capturedErr = args[0] as? java.io.OutputStream; proxy }
                    "setArgs" -> { recordedArgs = (args[0] as Iterable<*>).map { it.toString() }; proxy }
                    "environment" -> {
                        if (args.size == 2) recordedEnv[args[0] as String] = args[1].toString()
                        proxy
                    }
                    "getErrorOutput" -> capturedErr
                    "setStandardOutput" -> { capturedOut = args[0] as? java.io.OutputStream; proxy }
                    "getStandardOutput" -> capturedOut
                    // Defaults for the few getters Gradle might probe — the
                    // production code only sets executable/args/errorOutput.
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args[0]
                    "toString" -> "FakeExecSpec"
                    else -> if (method.returnType == java.lang.Boolean.TYPE) false else proxy
                }
            } as ExecSpec

            action.execute(spec)
            if (stdout.isNotEmpty()) {
                capturedOut?.write(stdout.toByteArray(Charsets.UTF_8))
                capturedOut?.flush()
            }
            if (stderr.isNotEmpty()) {
                capturedErr?.write(stderr.toByteArray(Charsets.UTF_8))
                capturedErr?.flush()
            }
            return object : ExecResult {
                override fun getExitValue(): Int = resultExit
                override fun assertNormalExitValue(): ExecResult = this
                override fun rethrowFailure(): ExecResult = this
            }
        }

        override fun javaexec(action: Action<in JavaExecSpec>): ExecResult =
            throw AssertionError("javaexec must not be called")
    }

    private fun executableCliStub(): File {
        val f = File.createTempFile("bugsee-cli-stub", "")
        f.deleteOnExit()
        f.writeText("#!/bin/sh\nexit 0\n")
        f.setExecutable(true)
        return f
    }

    private fun runUploadMapping(
        exec: ExecOperations,
        cli: File,
        logger: org.gradle.api.logging.Logger = Logging.getLogger("test"),
    ): CliUploadResult =
        CliUploader.uploadMapping(
            execOps = exec,
            cliBinary = cli,
            mappingFile = mappingFile,
            iconFile = null,
            appToken = envToken,
            endpoint = "https://api.bugsee.com",
            version = "1.0",
            build = "1",
            uuid = "00000000-0000-0000-0000-000000000000",
            logger = logger,
            debug = false,
        )

    private class ThrowingExec : ExecOperations {
        override fun exec(action: Action<in ExecSpec>): ExecResult = throw RuntimeException("exec boom")
        override fun javaexec(action: Action<in JavaExecSpec>): ExecResult = throw AssertionError("no javaexec")
    }

    /** A [org.gradle.api.logging.Logger] proxy that records every `warn(String)` message. */
    private fun recordingLogger(warns: MutableList<String>): org.gradle.api.logging.Logger =
        java.lang.reflect.Proxy.newProxyInstance(
            org.gradle.api.logging.Logger::class.java.classLoader,
            arrayOf(org.gradle.api.logging.Logger::class.java),
        ) { _, method, args ->
            if (method.name == "warn" && args?.firstOrNull() is String) warns += args[0] as String
            when (method.name) {
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                "toString" -> "RecordingLogger"
                else -> if (method.returnType == java.lang.Boolean.TYPE) false else null
            }
        } as org.gradle.api.logging.Logger

    private fun withInterruptFlag(interrupted: Boolean, body: () -> Unit) {
        if (interrupted) Thread.currentThread().interrupt()
        try { body() } finally { Thread.interrupted() }
    }

    @Test fun `exec failure logs the fall back warning when not interrupted`() {
        val warns = mutableListOf<String>()
        val r = runUploadMapping(ThrowingExec(), executableCliStub(), recordingLogger(warns))
        assertEquals("exec-failed", r.fallbackReason)
        assertTrue(warns.any { "will fall back to the Kotlin uploader" in it }, "warns=$warns")
    }

    @Test fun `exec failure logs no fall back warning when the thread is interrupted`() {
        val warns = mutableListOf<String>()
        val cli = executableCliStub()
        withInterruptFlag(true) {
            val r = runUploadMapping(ThrowingExec(), cli, recordingLogger(warns))
            assertEquals("exec-failed", r.fallbackReason) // semantics unchanged
            assertTrue(Thread.currentThread().isInterrupted, "flag must stay set for the caller to rethrow")
        }
        assertTrue(warns.none { "fall back" in it }, "warns=$warns")
    }

    @Test fun `pack exec failure warns only when not interrupted`() {
        val cli = executableCliStub()
        for (interrupted in listOf(false, true)) {
            val warns = mutableListOf<String>()
            withInterruptFlag(interrupted) {
                val ok = CliUploader.packUploadZip(
                    ThrowingExec(), cli, File("/tmp/app.apk"), null,
                    File("/tmp/out.zip"), recordingLogger(warns), debug = false,
                )
                assertFalse(ok)
            }
            assertEquals(
                !interrupted, warns.any { "using the native packer" in it }, "interrupted=$interrupted warns=$warns",
            )
        }
    }

    @Test fun `non-zero exit warns about fallback only when not interrupted`() {
        val cli = executableCliStub()
        // 143 = killed by signal (fallback); 3 = substantive failure (no fallback).
        for ((code, marker) in listOf(143 to "falling back", 3 to "substantive failure")) {
            for (interrupted in listOf(false, true)) {
                val warns = mutableListOf<String>()
                withInterruptFlag(interrupted) {
                    val r = runUploadMapping(FakeExec(cliExitValue = code), cli, recordingLogger(warns))
                    assertEquals(code, r.exitCode) // classification unchanged
                    assertEquals(code == 143, r.shouldFallback)
                    assertEquals(interrupted, Thread.currentThread().isInterrupted, "flag must stay set for the caller")
                }
                assertEquals(
                    !interrupted, warns.any { marker in it }, "code=$code interrupted=$interrupted warns=$warns",
                )
            }
        }
    }

    @Test fun `pack non-zero exit warns only when not interrupted`() {
        val cli = executableCliStub()
        for (interrupted in listOf(false, true)) {
            val warns = mutableListOf<String>()
            withInterruptFlag(interrupted) {
                val ok = CliUploader.packUploadZip(
                    FakeExec(cliExitValue = 143), cli, File("/tmp/app.apk"), null,
                    File("/tmp/out-134.zip"), recordingLogger(warns), debug = false,
                )
                assertFalse(ok)
                assertEquals(interrupted, Thread.currentThread().isInterrupted, "flag must stay set for the caller")
            }
            assertEquals(
                !interrupted, warns.any { "using the native packer" in it }, "interrupted=$interrupted warns=$warns",
            )
        }
    }

    // ── the app token goes in the environment, never on the command line ──

    private val envToken = "tok_SECRET_0123456789abcdef"

    @Test fun `uploadMapping passes the token via BUGSEE_APP_TOKEN and not on argv`() {
        val exec = FakeExec(cliExitValue = 0)
        CliUploader.uploadMapping(
            execOps = exec, cliBinary = executableCliStub(), mappingFile = mappingFile, iconFile = null,
            appToken = envToken, endpoint = "https://api.bugsee.com", version = "1.0", build = "1",
            uuid = "00000000-0000-0000-0000-000000000000", logger = Logging.getLogger("test"), debug = false,
        )
        assertEquals(1, exec.execCount)
        assertEquals(envToken, exec.recordedEnv["BUGSEE_APP_TOKEN"])
        assertFalse(exec.recordedArgs.any { it.contains(envToken) }, "token must not be on argv: ${exec.recordedArgs}")
        assertFalse(exec.recordedArgs.contains("--app-token"), "no --app-token flag: ${exec.recordedArgs}")
        assertTrue(exec.recordedArgs.containsAll(listOf("--endpoint", "https://api.bugsee.com", "debug-files", "upload")))
    }

    @Test fun `uploadElf passes the token via BUGSEE_APP_TOKEN and not on argv`() {
        val exec = FakeExec(cliExitValue = 0)
        CliUploader.uploadElf(
            execOps = exec, cliBinary = executableCliStub(), symbolsZip = symbolsZip, appToken = envToken,
            endpoint = "https://api.bugsee.com", version = "1.0", build = "1",
            uuid = "00000000-0000-0000-0000-000000000000", logger = Logging.getLogger("test"), debug = false,
        )
        assertEquals(envToken, exec.recordedEnv["BUGSEE_APP_TOKEN"])
        // Prove argv was actually recorded, so the "not on argv" checks below cannot pass vacuously.
        assertTrue(exec.recordedArgs.containsAll(listOf("--endpoint", "https://api.bugsee.com", "--type", "elf")), "argv=${exec.recordedArgs}")
        assertFalse(exec.recordedArgs.any { it.contains(envToken) }, "token must not be on argv: ${exec.recordedArgs}")
        assertFalse(exec.recordedArgs.contains("--app-token"), "no --app-token flag: ${exec.recordedArgs}")
    }

    @Test fun `uploadBuild passes the token via BUGSEE_APP_TOKEN and not on argv`() {
        val exec = FakeExec(cliExitValue = 0)
        CliUploader.uploadBuild(
            execOps = exec, cliBinary = executableCliStub(), endpoint = "https://api.bugsee.com", appToken = envToken,
            payloadJsonFile = File("/tmp/payload.json"), artifactFile = File("/tmp/app.aab"), mappingFile = null,
            depsJsonFile = null, timingsJsonFile = null, chunked = false, logger = Logging.getLogger("test"), debug = false,
        )
        assertEquals(envToken, exec.recordedEnv["BUGSEE_APP_TOKEN"])
        assertFalse(exec.recordedArgs.any { it.contains(envToken) }, "token must not be on argv: ${exec.recordedArgs}")
        assertTrue(exec.recordedArgs.containsAll(listOf("upload", "build")), "argv=${exec.recordedArgs}")
    }

    @Test fun `verifyAndExec maps exit 0 to success with no fallback`() {
        val result = runUploadMapping(FakeExec(cliExitValue = 0), executableCliStub())
        assertTrue(result.success, "exit 0 → success")
        assertEquals(0, result.exitCode)
        assertFalse(result.shouldFallback, "exit 0 → no fallback")
        assertEquals(null, result.fallbackReason)
    }

    @Test fun `verifyAndExec maps exit 1 to structural fallback with reason exit-1`() {
        val result = runUploadMapping(FakeExec(cliExitValue = 1, stderr = "boom"), executableCliStub())
        assertFalse(result.success)
        assertEquals(1, result.exitCode)
        assertTrue(result.shouldFallback, "exit 1 (Unexpected) → structural fallback")
        assertEquals("exit-1", result.fallbackReason)
    }

    @Test fun `verifyAndExec maps exit 2 to structural fallback with reason exit-2`() {
        val result = runUploadMapping(FakeExec(cliExitValue = 2), executableCliStub())
        assertFalse(result.success)
        assertEquals(2, result.exitCode)
        assertTrue(result.shouldFallback, "exit 2 (Usage) → structural fallback")
        assertEquals("exit-2", result.fallbackReason)
    }

    @Test fun `verifyAndExec maps exit 127 to fallback with not-runnable reason`() {
        val result = runUploadMapping(FakeExec(cliExitValue = 127), executableCliStub())
        assertFalse(result.success)
        assertEquals(127, result.exitCode)
        assertTrue(result.shouldFallback)
        assertEquals("exit-127-not-runnable", result.fallbackReason)
    }

    @Test fun `verifyAndExec maps exit 137 to fallback with signal reason`() {
        val result = runUploadMapping(FakeExec(cliExitValue = 137), executableCliStub())
        assertTrue(result.shouldFallback)
        assertEquals("exit-137-signal-9", result.fallbackReason)
    }

    @Test fun `verifyAndExec renders a negative exit code as unsigned hex in the reason`() {
        val result = runUploadMapping(FakeExec(cliExitValue = -1073741819), executableCliStub())
        assertTrue(result.shouldFallback)
        assertEquals("exit-0xC0000005-not-runnable", result.fallbackReason)
    }

    @Test fun `fallbackReason formats every fallback bucket`() {
        assertEquals("exit-1", CliUploader.fallbackReason(1))
        assertEquals("exit-2", CliUploader.fallbackReason(2))
        assertEquals("exit-126-not-runnable", CliUploader.fallbackReason(126))
        assertEquals("exit-128-not-runnable", CliUploader.fallbackReason(128))
        assertEquals("exit-129-signal-1", CliUploader.fallbackReason(129))
        assertEquals("exit-139-signal-11", CliUploader.fallbackReason(139))
        assertEquals("exit-0xFFFFFFFF-not-runnable", CliUploader.fallbackReason(-1))
    }

    @Test fun `signal exit falls back for symbols but not for build registration`() {
        val r = runUploadMapping(FakeExec(cliExitValue = 137), executableCliStub())
        assertTrue(r.shouldFallback, "symbols are idempotent: fall back")
        assertFalse(r.shouldFallbackForBuildRegistration, "build registration state unknown: no second attempt")
    }

    @Test fun `windows crash status falls back for symbols but not for build registration`() {
        val r = runUploadMapping(FakeExec(cliExitValue = -1073741819), executableCliStub())
        assertTrue(r.shouldFallback)
        assertFalse(r.shouldFallbackForBuildRegistration)
    }

    @Test fun `not-runnable exits fall back for both symbols and build registration`() {
        for (code in listOf(126, 127, 128, -1, -1073741515, -1073741701, -1073741511, -1073741502)) {
            val r = runUploadMapping(FakeExec(cliExitValue = code), executableCliStub())
            assertTrue(r.shouldFallback, "exit $code symbols")
            assertTrue(r.shouldFallbackForBuildRegistration, "exit $code build registration")
        }
        for (code in listOf(1, 2)) {
            val r = runUploadMapping(FakeExec(cliExitValue = code), executableCliStub())
            assertTrue(r.shouldFallbackForBuildRegistration, "structural exit $code keeps build fallback")
        }
    }

    @Test fun `verifyAndExec maps exit 3 to no-fallback failure`() {
        val result = runUploadMapping(FakeExec(cliExitValue = 3), executableCliStub())
        assertFalse(result.success)
        assertFalse(result.shouldFallback)
        assertEquals(null, result.fallbackReason)
    }

    @Test fun `verifyAndExec maps substantive exit codes to no-fallback failures`() {
        // 10 (Input), 20 (Config), 40 (reserved) — substantive failures the
        // Kotlin uploader would also hit, so NO fallback and NO reason.
        for (code in listOf(10, 20, 40)) {
            val result = runUploadMapping(FakeExec(cliExitValue = code), executableCliStub())
            assertFalse(result.success, "exit $code → failure")
            assertEquals(code, result.exitCode)
            assertFalse(result.shouldFallback, "exit $code is substantive → no fallback")
            assertEquals(null, result.fallbackReason, "substantive failure carries no fallback reason")
        }
    }

    @Test fun `verifyAndExec reports binary-missing without exec'ing`() {
        val neverExec = object : ExecOperations {
            override fun exec(action: Action<in ExecSpec>): ExecResult =
                throw AssertionError("must not exec when the binary is missing")
            override fun javaexec(action: Action<in JavaExecSpec>): ExecResult =
                throw AssertionError("must not javaexec")
        }
        val result = runUploadMapping(neverExec, File("/definitely/not/here/bugsee-cli"))
        assertFalse(result.success)
        assertEquals(-1, result.exitCode)
        assertTrue(result.shouldFallback)
        assertEquals("binary-missing", result.fallbackReason)
    }

    @Test fun `verifyAndExec reports exec-failed when exec throws`() {
        val throwingExec = object : ExecOperations {
            override fun exec(action: Action<in ExecSpec>): ExecResult =
                throw RuntimeException("spawn failed")
            override fun javaexec(action: Action<in JavaExecSpec>): ExecResult =
                throw AssertionError("must not javaexec")
        }
        val result = runUploadMapping(throwingExec, executableCliStub())
        assertFalse(result.success)
        assertEquals(-1, result.exitCode)
        assertTrue(result.shouldFallback)
        assertEquals("exec-failed", result.fallbackReason)
    }

    // ── CLI stderr/stdout is scrubbed before it reaches any log level (#109) ──

    /** A [org.gradle.api.logging.Logger] proxy recording every message (and argument) at every level. */
    private class AllLevelsLogger {
        val lines = mutableListOf<Pair<String, String>>()
        val logger: org.gradle.api.logging.Logger = java.lang.reflect.Proxy.newProxyInstance(
            org.gradle.api.logging.Logger::class.java.classLoader,
            arrayOf(org.gradle.api.logging.Logger::class.java),
        ) { proxy, method, args ->
            val level = method.name
            if (level in setOf("trace", "debug", "info", "lifecycle", "quiet", "warn", "error", "log")) {
                lines += level to (args ?: emptyArray()).joinToString(" ") { it.toString() }
            }
            when {
                method.name.startsWith("is") && method.returnType == java.lang.Boolean.TYPE -> true
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args[0]
                method.name == "toString" -> "AllLevelsLogger"
                method.name == "getName" -> "all-levels"
                else -> null
            }
        } as org.gradle.api.logging.Logger
        fun all(): String = lines.joinToString("\n") { "${it.first}: ${it.second}" }
    }

    private val presigned = "https://bucket.s3.amazonaws.com/builds/abc.zst?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
        "&X-Amz-Credential=AKIAEXAMPLEKEY%2F20260101%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Signature=deadbeefcafe0123456789"
    private val s3Error = "<Error><Code>SignatureDoesNotMatch</Code><AWSAccessKeyId>AKIAEXAMPLEKEY</AWSAccessKeyId>" +
        "<StringToSign>AWS4-HMAC-SHA256\n20260101T000000Z</StringToSign><SignatureProvided>deadbeefcafe0123456789</SignatureProvided></Error>"

    private fun assertNoSecrets(text: String) {
        for (secret in listOf(envToken, "deadbeefcafe0123456789", "AKIAEXAMPLEKEY", "20260101T000000Z", "SECRETSESSION")) {
            assertFalse(text.contains(secret), "'$secret' leaked into the log:\n$text")
        }
    }

    private val noisyStderr = "upload failed: PUT $presigned\n" +
        "for https://api.bugsee.com/v2/apps/$envToken/builds status=403 body=$s3Error\n" +
        "response {\"error\":\"bad\",\"X-Amz-Security-Token\":\"SECRETSESSION\"}\n" +
        "Authorization: Bearer $envToken\n" +
        "StringToSign: AWS4-HMAC-SHA256 20260101T000000Z\n"

    @Test fun `failure stderr and stdout never leak the presigned URL signature or app token at any level`() {
        val log = AllLevelsLogger()
        val result = CliUploader.uploadMapping(
            execOps = FakeExec(cliExitValue = 31, stderr = noisyStderr, stdout = noisyStderr),
            cliBinary = executableCliStub(), mappingFile = mappingFile, iconFile = null, appToken = envToken,
            endpoint = "https://api.bugsee.com", version = "1.0", build = "1",
            uuid = "00000000-0000-0000-0000-000000000000", logger = log.logger, debug = true,
        )
        assertNoSecrets(log.all())
        // Diagnostic value is kept: exit code, the failing host/path and the S3 error code.
        assertTrue(log.all().contains("exit 31"), log.all())
        assertTrue(log.all().contains("bucket.s3.amazonaws.com/builds/abc.zst"), log.all())
        assertTrue(log.all().contains("SignatureDoesNotMatch"), log.all())
        // Semantics unchanged.
        assertFalse(result.success); assertFalse(result.shouldFallback); assertEquals(31, result.exitCode)
    }

    @Test fun `success and fallback exits scrub too and keep their result`() {
        for (code in listOf(0, 1)) {
            val log = AllLevelsLogger()
            val result = runUploadMapping(FakeExec(cliExitValue = code, stderr = noisyStderr, stdout = noisyStderr), executableCliStub(), log.logger)
            assertNoSecrets(log.all())
            assertEquals(code == 0, result.success)
            assertEquals(code == 1, result.shouldFallback)
        }
    }

    @Test fun `packUploadZip scrubs the CLI output`() {
        val log = AllLevelsLogger()
        val out = File.createTempFile("pack", ".zip").also { it.deleteOnExit() }
        CliUploader.packUploadZip(
            execOps = FakeExec(cliExitValue = 1, stderr = noisyStderr, stdout = noisyStderr),
            cliBinary = executableCliStub(), artifactFile = mappingFile, mappingFile = null, outZip = out,
            logger = log.logger, debug = true,
        )
        for (s in listOf("AKIAEXAMPLEKEY", "20260101T000000Z", "SECRETSESSION")) assertFalse(log.all().contains(s), log.all())
        assertFalse(log.all().contains("deadbeefcafe0123456789"), log.all())
    }

    @Test fun `stdout is forwarded scrubbed at lifecycle level, independent of stderr`() {
        val log = AllLevelsLogger()
        runUploadMapping(FakeExec(cliExitValue = 0, stdout = "build_id=42 $presigned"), executableCliStub(), log.logger)
        val lifecycle = log.lines.filter { it.first == "lifecycle" }.joinToString("\n") { it.second }
        assertTrue(lifecycle.contains("build_id=42"), log.all())
        assertNoSecrets(log.all())
    }

    @Test fun `a truncated unterminated secret JSON value and a huge value neither leak nor overflow the stack`() {
        val truncated = "body={\"upload_token\":\"" + "S".repeat(3000) + "\n" + "retry warn\n".repeat(30) + "error: \"Service Unavailable\""
        var out = ""
        val t = Thread(null, { out = CliUploader.scrubCliOutput(truncated) }, "small-stack", 256 * 1024)
        t.start(); t.join()
        assertFalse(out.contains("SSSS"), out)
        assertTrue(out.contains("Service Unavailable"), out)
    }

    @Test fun `ANSI colour codes and underscored token keys do not defeat the scrubber`() {
        val out = CliUploader.scrubCliOutput("\u001B[3mtoken\u001B[0m=ANSISECRET upload_token=UNDERSECRET")
        assertFalse(out.contains("ANSISECRET") || out.contains("UNDERSECRET"), out)
    }

    @Test fun `escaped-quote JSON bodies and colon-delimited SGR codes are scrubbed`() {
        val escaped = CliUploader.scrubCliOutput("body=\"{\\\"upload_token\\\":\\\"ESCSECRET\\\"}\" status=403")
        assertFalse(escaped.contains("ESCSECRET"), escaped)
        val sgr = CliUploader.scrubCliOutput("token\u001B[38:5:1m=COLONSECRET")
        assertFalse(sgr.contains("COLONSECRET"), sgr)
    }

    @Test fun `escape-heavy secret value does not overflow a small stack`() {
        val nasty = "{\"upload_token\":\"" + "\\\"".repeat(1900) + "\"}\nerror: done"
        var out = ""
        val t = Thread(null, { out = CliUploader.scrubCliOutput(nasty) }, "small-stack-2", 64 * 1024)
        t.start(); t.join()
        assertTrue(out.contains("<redacted>"), out)
    }

    @Test fun `non-SGR escape sequences glued to a key do not defeat the scrubber`() {
        val out = CliUploader.scrubCliOutput("\u001B[2Ktoken=KSECRET")
        assertFalse(out.contains("KSECRET"), out)
    }

    @Test fun `quoted tracing-style field values and other credential-named keys are scrubbed`() {
        val out = CliUploader.scrubCliOutput(
            "WARN upload_token=\"QSECRET\" signature=\"deadbeef\" client_secret=CSECRET password=hunter2 api_key=k1 retries=3",
        )
        for (s in listOf("QSECRET", "deadbeef", "CSECRET", "hunter2", "k1 ")) assertFalse(out.contains(s), out)
        assertTrue(out.contains("retries=3"), out)
    }

    @Test fun `Debug-struct, single-quoted, escaped-quote and digit-bearing credential fields are scrubbed`() {
        val out = CliUploader.scrubCliOutput(
            "Resp { upload_url: \"https://x\", upload_token: \"DBGSECRET\" } password='hunter2' " +
                "client_secret=\"abc\\\"RESTSECRET\" oauth2_access_token=OSECRET error: token expired",
        )
        for (s in listOf("DBGSECRET", "hunter2", "RESTSECRET", "OSECRET")) assertFalse(out.contains(s), out)
        val wrapped = CliUploader.scrubCliOutput(
            "Resp { upload_token: Some(\"OPTSECRET\"), session_secret: Secret(\"WRAP\") } " +
                "${"a".repeat(40)}_api_key=LONGKEY api_key_${"a".repeat(40)}=LONGKEY2",
        )
        for (s in listOf("OPTSECRET", "WRAP", "LONGKEY")) assertFalse(wrapped.contains(s), wrapped)
        assertTrue(out.contains("error: token expired"), out)
    }

    @Test fun `quoted credential value with long escape run does not overflow a small stack`() {
        val nasty = "client_secret=\"" + "\\\"".repeat(950) + "\nx password: '" + "\\'".repeat(950)
        var out = ""
        val t = Thread(null, { out = CliUploader.scrubCliOutput(nasty) }, "small-stack-3", 64 * 1024)
        t.start(); t.join()
        assertTrue(out.contains("<redacted>"), out)
    }

    @Test fun `a long run of credential-looking word characters scrubs in bounded time`() {
        // Each input is < 4000 chars with no newline, so head+tail trimming keeps it and the key regex runs.
        for (nasty in listOf("token".repeat(790), "-key".repeat(990), "-token".repeat(660), "key_".repeat(990) + "=x")) {
            val start = System.nanoTime()
            val out = CliUploader.scrubCliOutput(nasty)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 2000, "scrub took too long for ${nasty.take(12)}")
            assertFalse(out.contains("output truncated"), "input must survive trimming")
        }
        val eqWrapped = CliUploader.scrubCliOutput("upload_token=Some(\"EQSECRET\") x=1")
        assertFalse(eqWrapped.contains("EQSECRET"), eqWrapped)
        // A secret nested inside a non-credential quoted value is still found.
        val nested = CliUploader.scrubCliOutput("msg=\"retry token=NESTEDSECRET later\"")
        assertFalse(nested.contains("NESTEDSECRET"), nested)
    }

    @Test fun `scrubCliOutput is bounded-time on adversarial 1 MB inputs`() {
        val mb = 1_000_000
        val inputs = listOf(
            "token".repeat(mb / 5), "-key".repeat(mb / 4), "?a=".repeat(mb / 3), "\\\"".repeat(mb / 2),
            "\"token\":\"".repeat(mb / 9), "<StringToSign>".repeat(mb / 14), "Bearer ".repeat(mb / 7),
            "\u001B[".repeat(mb / 2), "token=\"".repeat(mb / 7), "line with token=x\n".repeat(mb / 18),
            "a".repeat(mb),
        )
        for (nasty in inputs) {
            val start = System.nanoTime()
            CliUploader.scrubCliOutput(nasty)
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue(ms < 10_000, "scrub took ${ms}ms for ${nasty.take(12)}")
        }
    }

    @Test fun `S3 CanonicalRequestBytes element is redacted`() {
        val out = CliUploader.scrubCliOutput("<Error><CanonicalRequestBytes>41 4b 49 41 53 45 43</CanonicalRequestBytes></Error>")
        assertFalse(out.contains("41 4b 49"), out)
    }

    @Test fun `long output keeps head and tail so the final error line survives`() {
        val text = "first line\n" + "retrying request\n".repeat(1000) + "error: upload rejected (403)"
        val out = CliUploader.scrubCliOutput(text)
        assertTrue(out.startsWith("first line"), out)
        assertTrue(out.endsWith("error: upload rejected (403)"), out)
        assertTrue(out.length < 4300, "len=${out.length}")
    }

    @Test fun `scrubCliOutput bounds the length and keeps short plain text`() {
        assertEquals("connection refused (os error 111)", CliUploader.scrubCliOutput("connection refused (os error 111)\n"))
        assertTrue(CliUploader.scrubCliOutput("x".repeat(100_000)).length <= 4100)
        assertEquals("", CliUploader.scrubCliOutput("  \n"))
    }

    private val s3ExpiredToken = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error><Code>ExpiredToken</Code>" +
        "<Message>The provided token has expired.</Message><Token-0>FwoGZXIvYXdzEJr//SESSIONSECRET</Token-0>" +
        "<RequestId>4442587FB7D0A2F9</RequestId><HostId>HOSTID123abc</HostId></Error>"

    @Test fun `S3 ExpiredToken body redacts Token-0 but keeps benign elements`() {
        val out = CliUploader.scrubCliOutput("error: " + s3ExpiredToken)
        assertFalse(out.contains("SESSIONSECRET"), out)
        assertTrue(out.contains("<Token-0><redacted></Token-0>"), out)
        for (keep in listOf("<Code>ExpiredToken</Code>", "<Message>The provided token has expired.</Message>",
            "<RequestId>4442587FB7D0A2F9</RequestId>", "<HostId>HOSTID123abc</HostId>")) {
            assertTrue(out.contains(keep), "lost $keep in $out")
        }
    }

    @Test fun `generalised XML secret tags cover suffixes namespaces attributes and unknown names`() {
        val cases = listOf(
            "<Token-1>S1</Token-1>", "<x:SessionToken>S2</x:SessionToken>", "<SecurityToken>S3</SecurityToken>",
            "<ClientSecret>S4</ClientSecret>", "<Password>S5</Password>", "<ApiKey>S6</ApiKey>",
            "<SecretAccessKey>S7</SecretAccessKey>", "<AWSAccessKeyId>S8</AWSAccessKeyId>",
            "<Token-0 type=\"x\">S9</Token-0>", "<token>S10</token>", "<Credential>S11</Credential>",
            "<Token-0>S12", "<Token-0 a=\"b\">S13", "<private_key>S14</private_key>", "<signing-key>S15</signing-key>",
            "<Token><![CDATA[S16]]></Token>", "<Token>S17<!-- c --></Token>", "<Token value=\"S18\"/>", "<Item token=\"S19\">x</Item>",
        )
        for (c in cases) {
            val out = CliUploader.scrubCliOutput("<Error>$c<Code>X</Code></Error>")
            assertFalse(Regex("S\\d+").containsMatchIn(out), "leaked in $out")
            assertTrue(out.contains("<Code>X</Code>"), out)
        }
    }

    @Test fun `benign XML elements are untouched`() {
        val benign = "<Error><Code>AccessDenied</Code><Message>Request has expired</Message><RequestId>R1</RequestId>" +
            "<HostId>H1</HostId><Resource>/bucket/obj</Resource><Expires>2026-01-01T00:00:00Z</Expires>" +
            "<Key>some/object</Key><KeyCount>3</KeyCount><Empty/><Token/></Error>"
        assertEquals(benign, CliUploader.scrubCliOutput(benign))
    }

    @Test fun `prose and CLI usage with token-like placeholders is unchanged`() {
        val usage = "Usage: bugsee-cli upload --app-token <APP_TOKEN> --type <TYPE>\n\nFor more information try --help"
        assertEquals(usage, CliUploader.scrubCliOutput(usage))
        val msg = "expected List<AccessToken> but got null at Foo.kt:12 (caused by bar)"
        assertEquals(msg, BundleUploader.scrubForLog(msg))
        val benignKeys = "<Error><NextKeyMarker>m</NextKeyMarker><PolicyName>p</PolicyName><Monkey>x</Monkey></Error>"
        assertEquals(benignKeys, BundleUploader.scrubForLog(benignKeys))
    }

    @Test fun `truncated or oversized secret elements are redacted to the next tag`() {
        val cases = mapOf(
            "<Error><CanonicalRequestBytes>50 55 54 0a X-Amz-Security-Token=FwoGLEAK1 41 42" to "LEAK1",
            "<Error><CanonicalRequest>PUT\n/b/k\nX-Amz-Security-Token=FwoGLEAK2" to "LEAK2",
            "<Error><StringToSign>AWS4-HMAC-SHA256 20260101T000000Z LEAK3" to "LEAK3",
            "<Error><Code>X</Code>\n  <SessionToken>\n    LEAK4" to "LEAK4",
            "<Token>" + "C".repeat(4096) + "LEAK5</Token>" to "LEAK5",
            "<SessionToken>" + "A".repeat(5000) + "LEAK6</SessionToken>" to "LEAK6",
            "<Policy>{\"c\": [\"LEAK7\"]}" + " ".repeat(4100) + "</Policy>" to "LEAK7",
            "<Token-0>abc LEAK8</Token-0 >" to "LEAK8",
            "<Error><ArgumentName>x-amz-security-token</ArgumentName><ArgumentValue>FwoGLEAK9</ArgumentValue></Error>" to "LEAK9",
        )
        for ((input, leak) in cases) {
            assertFalse(BundleUploader.scrubForLog(input).contains(leak), "scrubForLog leaked $leak")
        }
    }

    @Test fun `scrubForLog redacts credential attributes and keeps clap-style prose`() {
        assertFalse(BundleUploader.scrubForLog("<Item token=\"SECRETA\">x</Item>").contains("SECRETA"))
        assertFalse(BundleUploader.scrubForLog("<Token value='SECRETB'/>").contains("SECRETB"))
        for (prose in listOf(
            "error: a value is required for '--app-token <APP_TOKEN>' but none was supplied",
            "invalid value 'x' for '--app-token <APP_TOKEN>': too short",
            "Set BUGSEE_APP_TOKEN=<your-token>.",
            "got (<Token>)",
        )) {
            assertEquals(prose, BundleUploader.scrubForLog(prose))
        }
    }

    @Test fun `legacy S3 secret elements still redact exactly as before`() {
        val body = "<Error><Code>SignatureDoesNotMatch</Code><AWSAccessKeyId>AKIAXX</AWSAccessKeyId>" +
            "<StringToSign>AWS4\n2026</StringToSign><SignatureProvided>abc123</SignatureProvided>" +
            "<CanonicalRequestBytes>41 4b</CanonicalRequestBytes><Policy>p</Policy></Error>"
        val out = BundleUploader.scrubForLog(body)
        for (leak in listOf("AKIAXX", "AWS4", "abc123", "41 4b", ">p<")) assertFalse(out.contains(leak), out)
        assertTrue(out.contains("<Code>SignatureDoesNotMatch</Code>"), out)
    }

    @Test fun `self-closing secret tag does not swallow following text and the window limit is honoured`() {
        assertEquals("<Token a=\"<redacted>\"/>keep me<Code>X</Code>", BundleUploader.scrubForLog("<Token a=\"b\"/>keep me<Code>X</Code>"))
        val inside = "<Token>" + "x".repeat(4000) + "<![CDATA[IN]]></Token>"
        assertFalse(BundleUploader.scrubForLog(inside).contains("IN]]"), "close tag inside the window must redact the CDATA")
        val outside = "<Token>" + "x".repeat(4100) + "<b>AFTER</b></Token>"
        val out = BundleUploader.scrubForLog(outside)
        assertTrue(out.contains("<b>AFTER</b>"), "text after a nested tag beyond the window is the accepted limit")
        assertFalse(out.contains("xxxx"), out)
    }

    @Test fun `new credential names and line-start placeholders`() {
        val body = "<Error><AccountKey>S1</AccountKey><SharedKey>S2</SharedKey><Passphrase>S3</Passphrase><Cookie>S4</Cookie></Error>"
        assertFalse(Regex("S\\d").containsMatchIn(BundleUploader.scrubForLog(body)))
        val help = "Arguments:\n  <TOKEN>  Bugsee app token\n\nOptions:\n  -h <HELP>"
        assertEquals(help, BundleUploader.scrubForLog(help))
    }

    @Test fun `redactBodyForLog JSON string values are scrubbed`() {
        val out = BundleUploader.redactBodyForLog("{\"detail\":\"<Token-0>JSONSECRET</Token-0> failed\",\"n\":1}")
        assertFalse(out.contains("JSONSECRET"), out)
    }

    @Test fun `XML scrub time scales linearly`() {
        // Distinct names defeat the failed-search memo, so only the 4096-char window keeps this linear.
        fun time(n: Int, input: String): Long {
            BundleUploader.scrubForLog(input)
            return (1..5).minOf { val s = System.nanoTime(); BundleUploader.scrubForLog(input); System.nanoTime() - s }
        }
        fun distinct(n: Int) = buildString { for (i in 0 until n) append("<Token").append(i).append(">") }
        fun same(n: Int) = "<Token>".repeat(n)
        for (gen in listOf(::distinct, ::same)) {
            time(20_000, gen(20_000))
            val small = time(100_000, gen(100_000)); val big = time(200_000, gen(200_000))
            assertTrue(big < small * 3 + 5_000_000, "small=$small big=$big")
        }
    }

    @Test fun `unclosed secret element with CDATA or comment is redacted past it`() {
        for (c in listOf("<Error><Token><![CDATA[CDSECRET]]>", "<Error><Token><!-- c -->CMSECRET", "<Error><StringToSign><![CDATA[CDSECRET]]>")) {
            assertFalse(BundleUploader.scrubForLog(c).contains("SECRET"), c)
        }
    }

    @Test fun `XML tag scrub is bounded-time on adversarial input`() {
        for (nasty in listOf("<" + "a".repeat(1_000_000), "<a ".repeat(300_000), "<Token" + "-".repeat(1_000_000), "<a>".repeat(300_000),
            "<Token>".repeat(150_000), "<Token>x".repeat(150_000), "<a token=\"".repeat(100_000),
            "<a " + "key_".repeat(125) + ">", ("<a " + "key_".repeat(125) + ">").repeat(2000), ("<a " + "b".repeat(500) + ">").repeat(2000),
            ("<token " + "b".repeat(500) + ">").repeat(2000), ("<_key>" + "<".repeat(1000)).repeat(1000))) {
            val start = System.nanoTime()
            BundleUploader.scrubForLog(nasty)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 3_000, "slow on ${nasty.take(20)}")
        }
    }
}
