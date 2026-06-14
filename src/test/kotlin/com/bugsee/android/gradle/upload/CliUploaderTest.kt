package com.bugsee.android.gradle.upload

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
            appToken = "token-xyz",
            version = "1.2.3",
            build = "42",
            uuid = "eb642b4e-9fe3-31c6-9a66-dd05ee3516f4",
            mappingFile = mappingFile,
            iconFile = null,
        )
        assertContentEquals(
            listOf(
                "--endpoint", "https://api.bugsee.com",
                "--app-token", "token-xyz",
                "debug-files", "upload",
                "--type", "proguard",
                "--version", "1.2.3",
                "--build", "42",
                "--uuid", "eb642b4e-9fe3-31c6-9a66-dd05ee3516f4",
                "/tmp/mapping.txt",
            ),
            argv,
        )
    }

    @Test fun `buildMappingArgv with icon appends --icon before the mapping path`() {
        val icon = File("/tmp/ic_launcher.png")
        val argv = CliUploader.buildMappingArgv(
            endpoint = "https://api.bugsee.com",
            appToken = "token-xyz",
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
            appToken = "token-xyz",
            version = "1.2.3",
            build = "42",
            uuid = "eb642b4e-9fe3-31c6-9a66-dd05ee3516f4",
            symbolsZip = symbolsZip,
        )
        assertContentEquals(
            listOf(
                "--endpoint", "https://api.bugsee.com",
                "--app-token", "token-xyz",
                "debug-files", "upload",
                "--type", "elf",
                "--version", "1.2.3",
                "--build", "42",
                "--uuid", "eb642b4e-9fe3-31c6-9a66-dd05ee3516f4",
                "/tmp/native-debug-symbols.zip",
            ),
            argv,
        )
    }

    @Test fun `buildElfArgv does NOT include --icon (CLI rejects icon for elf)`() {
        // The CLI returns ConfigInvalid (exit 20) if --icon is paired with
        // --type elf. The Kotlin side must never send the flag for ELF; pin
        // that here so a future copy/paste from buildMappingArgv doesn't
        // silently start sending --icon and breaking native uploads.
        val argv = CliUploader.buildElfArgv(
            endpoint = "https://api.bugsee.com",
            appToken = "token-xyz",
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
                "--deps", "/tmp/dependencies.json",
                "--timings", "/tmp/timings.json",
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

    @Test fun `reserved exit codes in 40+ range do NOT trigger fallback`() {
        // Defensive: any future addition to the CLI's exit-code enum lands
        // in a non-fallback bucket by default. A new code that SHOULD trigger
        // fallback must be added explicitly to `CliUploader.shouldFallback`.
        listOf(40, 41, 50, 99, 127).forEach { code ->
            assertFalse(
                CliUploader.shouldFallback(code),
                "exit $code (reserved) must default to substantive-failure; update both sides if needed",
            )
        }
    }
}
