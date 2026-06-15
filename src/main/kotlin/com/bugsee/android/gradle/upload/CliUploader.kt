package com.bugsee.android.gradle.upload

import org.gradle.api.logging.Logger
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Result of one `bugsee-cli debug-files upload` attempt.
 *
 * `shouldFallback` reflects the CLI's documented exit-code contract:
 *   - exit 0 → success, no fallback.
 *   - exit 1 (Unexpected) or exit 2 (Usage) → structural CLI failure;
 *     the Kotlin uploader is given a chance to take over.
 *   - any other non-zero exit (10–19 input, 20–29 config, 30–39 upload, 40+) →
 *     substantive failure; the Kotlin uploader would hit the same error,
 *     so we do NOT retry and the caller surfaces the failure as-is.
 *
 * See `~/Projects/Bugsee/bugsee-cli/src/exit_code.rs` for the source of
 * truth contract.
 */
internal data class CliUploadResult(
    val success: Boolean,
    val exitCode: Int,
    val shouldFallback: Boolean,
    /**
     * Stable short token describing why a fallback was triggered. Stamped into
     * the Kotlin uploader's `X-Bugsee-Uploader` request header
     * (`kotlin-fallback-cli-<reason>`) so backend can bucket the cause without
     * touching customer code.
     */
    val fallbackReason: String?,
)

/**
 * Drives `bugsee-cli debug-files upload` invocations for the symbol types
 * the plugin currently uploads. Two entry points, one per type:
 *
 *   - [uploadMapping] — ProGuard/R8 `mapping.txt` (+ optional launcher icon).
 *   - [uploadElf] — Pre-packaged NDK `native-debug-symbols.zip`. The CLI does
 *     a pass-through (no Zstd re-pack) in Phase 1; the Gradle plugin is
 *     responsible for zipping the `build/intermediates/native_debug_metadata/`
 *     directory case before calling here.
 *
 * Owned entirely by the Gradle task action — no shared state, no resident
 * service. Uses [ExecOperations] (injected into the task) instead of
 * `project.exec` so the call stays configuration-cache safe.
 */
internal object CliUploader {

    /** Per the CLI's documented exit-code contract: 1 = Unexpected. */
    private const val EXIT_UNEXPECTED = 1

    /** Per the CLI's documented exit-code contract: 2 = Usage (argv error). */
    private const val EXIT_USAGE = 2

    @Suppress("LongParameterList")
    fun uploadMapping(
        execOps: ExecOperations,
        cliBinary: File,
        mappingFile: File,
        iconFile: File?,
        appToken: String,
        endpoint: String,
        version: String,
        build: String,
        uuid: String,
        logger: Logger,
        debug: Boolean,
    ): CliUploadResult = verifyAndExec(
        execOps = execOps,
        cliBinary = cliBinary,
        argv = buildMappingArgv(
            endpoint = endpoint,
            appToken = appToken,
            version = version,
            build = build,
            uuid = uuid,
            mappingFile = mappingFile,
            iconFile = iconFile,
        ),
        logger = logger,
        debug = debug,
    )

    @Suppress("LongParameterList")
    fun uploadElf(
        execOps: ExecOperations,
        cliBinary: File,
        symbolsZip: File,
        appToken: String,
        endpoint: String,
        version: String,
        build: String,
        uuid: String,
        logger: Logger,
        debug: Boolean,
    ): CliUploadResult = verifyAndExec(
        execOps = execOps,
        cliBinary = cliBinary,
        argv = buildElfArgv(
            endpoint = endpoint,
            appToken = appToken,
            version = version,
            build = build,
            uuid = uuid,
            symbolsZip = symbolsZip,
        ),
        logger = logger,
        debug = debug,
    )

    /**
     * Drives `bugsee-cli upload build-info` in PRE-SIGNED mode: the
     * Gradle plugin already registered the build via its own
     * `/v2/apps/<token>/builds` POST and received the
     * `build_info_upload_endpoint` presigned URL in that response, so the
     * CLI skips registration and PUTs the zstd bundle directly to
     * [uploadUrl]. The CLI packs [depsJsonFile] / [timingsJsonFile] (RAW
     * JSON — NOT the gzipped legacy blobs) into the bundle as
     * `dependencies.json` / `timings.json`.
     *
     * At least one of [depsJsonFile] / [timingsJsonFile] must be non-null
     * (the CLI rejects an empty bundle with a config error). Returns the
     * same [CliUploadResult] contract as the symbol uploads — a
     * structural failure (exit 1/2, binary missing) signals the caller to
     * fall back to the legacy per-blob gzip PUTs.
     *
     * Requires `bugsee-cli` >= 0.2.0 (the version that introduced the
     * `upload build-info` subcommand). The caller pins the downloaded CLI
     * version via [CliBinaryResolver]; an older binary on PATH yields a
     * Usage error (exit 2) → structural fallback.
     */
    @Suppress("LongParameterList")
    fun uploadBuildInfo(
        execOps: ExecOperations,
        cliBinary: File,
        uploadUrl: String,
        depsJsonFile: File?,
        timingsJsonFile: File?,
        logger: Logger,
        debug: Boolean,
    ): CliUploadResult = verifyAndExec(
        execOps = execOps,
        cliBinary = cliBinary,
        argv = buildBuildInfoArgv(
            uploadUrl = uploadUrl,
            depsJsonFile = depsJsonFile,
            timingsJsonFile = timingsJsonFile,
        ),
        logger = logger,
        debug = debug,
    )

    /**
     * Drives `bugsee-cli upload build` — the converged build upload: the CLI
     * registers the build (`POST /v2/apps/<token>/builds`), packs the artefact
     * (+ optional zstd mapping) and uploads it (single-PUT, or chunked when
     * [chunked]), and ships the build-info bundle from the same registration
     * when [depsJsonFile]/[timingsJsonFile] are present and the org flag signs
     * the endpoint. The plugin owns *what* (the metadata body + which files);
     * the CLI owns *how* (packing, registration, presigned PUTs, chunking,
     * retries, telemetry).
     *
     * Returns the [CliUploadResult] contract: a structural failure (exit 1/2,
     * binary missing) signals the caller to fall back to the native
     * Bundle/ChunkedBundleUploader path; a substantive failure (bad token /
     * server / transport) propagates without fallback since the native path
     * would hit the same error.
     *
     * Requires `bugsee-cli` >= the version that introduced `upload build`
     * (gated by the caller via [CliBinaryResolver]); an older binary yields a
     * Usage error (exit 2) → structural fallback.
     */
    @Suppress("LongParameterList")
    fun uploadBuild(
        execOps: ExecOperations,
        cliBinary: File,
        endpoint: String,
        appToken: String,
        payloadJsonFile: File,
        artifactFile: File,
        mappingFile: File?,
        depsJsonFile: File?,
        timingsJsonFile: File?,
        chunked: Boolean,
        logger: Logger,
        debug: Boolean,
    ): CliUploadResult = verifyAndExec(
        execOps = execOps,
        cliBinary = cliBinary,
        argv = buildBuildArgv(
            endpoint = endpoint,
            appToken = appToken,
            payloadJsonFile = payloadJsonFile,
            artifactFile = artifactFile,
            mappingFile = mappingFile,
            depsJsonFile = depsJsonFile,
            timingsJsonFile = timingsJsonFile,
            chunked = chunked,
        ),
        logger = logger,
        debug = debug,
    )

    /**
     * Drives `bugsee-cli pack` to build the normalized upload ZIP — the
     * artefact STORED verbatim plus an optional `mapping.txt` compressed with
     * zstd (method 93) — writing it to [outZip]. Local-only: no network.
     *
     * Returns `true` iff the CLI exited 0 AND [outZip] was produced. ANY
     * failure (binary missing / not executable, exec error, or any non-zero
     * exit) returns `false` so the caller falls back to the plugin's native
     * DEFLATE packer. Unlike the upload paths there is no structural-vs-
     * substantive distinction here: the native packer always produces a valid
     * (if larger) ZIP, so a CLI hiccup must never break the build. An older
     * binary without the `pack` subcommand yields a Usage error (exit 2) →
     * `false` → native fallback, so this is safe before the pinned CLI version
     * is bumped.
     */
    @Suppress("LongParameterList")
    fun packUploadZip(
        execOps: ExecOperations,
        cliBinary: File,
        artifactFile: File,
        mappingFile: File?,
        outZip: File,
        logger: Logger,
        debug: Boolean,
    ): Boolean {
        if (!cliBinary.isFile || !cliBinary.canExecute()) {
            logger.warn(
                "Bugsee: bugsee-cli unavailable for packing " +
                    "(${cliBinary.absolutePath}); using the native packer.",
            )
            return false
        }

        val argv = buildPackArgv(artifactFile, mappingFile, outZip)
        if (debug) {
            logger.warn("Bugsee: invoking bugsee-cli with args: ${argv.joinToString(" ")}")
        }

        val stderr = ByteArrayOutputStream()
        val exitCode = try {
            execOps.exec { spec ->
                spec.executable = cliBinary.absolutePath
                spec.args = argv
                spec.errorOutput = stderr
                spec.isIgnoreExitValue = true
            }.exitValue
        } catch (e: Exception) {
            logger.warn(
                "Bugsee: failed to execute bugsee-cli pack: ${e.message}; " +
                    "using the native packer.",
            )
            return false
        }

        val stderrText = stderr.toString(Charsets.UTF_8).trim()
        if (stderrText.isNotEmpty()) {
            logger.warn("Bugsee: bugsee-cli output:\n$stderrText")
        }

        // Require a non-empty output, not just presence: an exit-0 that left a
        // 0-byte / truncated ZIP (a future CLI bug, or a reaped-but-killed
        // process reporting 0) must fall back rather than upload garbage.
        if (exitCode == 0 && outZip.isFile && outZip.length() > 0L) {
            return true
        }
        logger.warn(
            "Bugsee: bugsee-cli pack exited $exitCode (or produced no/empty output); " +
                "using the native packer.",
        )
        return false
    }

    private fun verifyAndExec(
        execOps: ExecOperations,
        cliBinary: File,
        argv: List<String>,
        logger: Logger,
        debug: Boolean,
    ): CliUploadResult {
        if (!cliBinary.isFile) {
            logger.warn(
                "Bugsee: bugsee-cli binary not found at ${cliBinary.absolutePath}; " +
                    "will fall back to the Kotlin uploader.",
            )
            return CliUploadResult(
                success = false,
                exitCode = -1,
                shouldFallback = true,
                fallbackReason = "binary-missing",
            )
        }
        if (!cliBinary.canExecute()) {
            logger.warn(
                "Bugsee: bugsee-cli binary at ${cliBinary.absolutePath} is not " +
                    "executable; will fall back to the Kotlin uploader.",
            )
            return CliUploadResult(
                success = false,
                exitCode = -1,
                shouldFallback = true,
                fallbackReason = "binary-not-executable",
            )
        }

        if (debug) {
            // app-token is intentionally NOT scrubbed here — we log it the same
            // way the Kotlin uploader logs the metadata JSON when `debug=true`.
            logger.warn("Bugsee: invoking bugsee-cli with args: ${argv.joinToString(" ")}")
        }

        val stderr = ByteArrayOutputStream()

        val exitCode = try {
            val result = execOps.exec { spec ->
                spec.executable = cliBinary.absolutePath
                spec.args = argv
                // The CLI logs all diagnostics to stderr via the `tracing` crate;
                // forward to the Gradle logger so users see what happened.
                // stdout stays untouched — currently unused, reserved for future
                // structured (JSON) output.
                spec.errorOutput = stderr
                spec.isIgnoreExitValue = true
            }
            result.exitValue
        } catch (e: Exception) {
            logger.warn(
                "Bugsee: failed to execute bugsee-cli: ${e.message}; " +
                    "will fall back to the Kotlin uploader.",
            )
            return CliUploadResult(
                success = false,
                exitCode = -1,
                shouldFallback = true,
                fallbackReason = "exec-failed",
            )
        }

        val stderrText = stderr.toString(Charsets.UTF_8).trim()
        if (stderrText.isNotEmpty()) {
            // Forward the CLI's own logs unprocessed — let the user read what
            // the binary said about its own failure rather than paraphrasing.
            logger.warn("Bugsee: bugsee-cli output:\n$stderrText")
        }

        return when {
            exitCode == 0 -> CliUploadResult(
                success = true,
                exitCode = 0,
                shouldFallback = false,
                fallbackReason = null,
            )
            shouldFallback(exitCode) -> {
                logger.warn(
                    "Bugsee: bugsee-cli exited with $exitCode (structural failure); " +
                        "falling back to the Kotlin uploader.",
                )
                CliUploadResult(
                    success = false,
                    exitCode = exitCode,
                    shouldFallback = true,
                    fallbackReason = "exit-$exitCode",
                )
            }
            else -> {
                // Substantive failure (bad token / network / server). The Kotlin
                // uploader would hit the same error, so don't waste a second
                // round-trip.
                logger.warn(
                    "Bugsee: bugsee-cli exited with $exitCode (substantive failure); " +
                        "the Kotlin uploader would hit the same error so no fallback is attempted.",
                )
                CliUploadResult(
                    success = false,
                    exitCode = exitCode,
                    shouldFallback = false,
                    fallbackReason = null,
                )
            }
        }
    }

    /**
     * Constructs the argv vector for `bugsee-cli debug-files upload --type proguard`.
     *
     * Visible for testing so the contract between plugin and CLI is pinned:
     * a change to the CLI's flag surface flips the test loudly before it can
     * silently break at customer build time.
     */
    internal fun buildMappingArgv(
        endpoint: String,
        appToken: String,
        version: String,
        build: String,
        uuid: String,
        mappingFile: File,
        iconFile: File?,
    ): List<String> = buildList {
        add("--endpoint"); add(endpoint)
        add("--app-token"); add(appToken)
        add("debug-files"); add("upload")
        add("--type"); add("proguard")
        add("--version"); add(version)
        add("--build"); add(build)
        add("--uuid"); add(uuid)
        iconFile?.let { add("--icon"); add(it.absolutePath) }
        add(mappingFile.absolutePath)
    }

    /**
     * Constructs the argv vector for `bugsee-cli debug-files upload --type elf`.
     *
     * No `--icon` (only valid for proguard); the CLI rejects that combination.
     * Visible for testing.
     */
    internal fun buildElfArgv(
        endpoint: String,
        appToken: String,
        version: String,
        build: String,
        uuid: String,
        symbolsZip: File,
    ): List<String> = buildList {
        add("--endpoint"); add(endpoint)
        add("--app-token"); add(appToken)
        add("debug-files"); add("upload")
        add("--type"); add("elf")
        add("--version"); add(version)
        add("--build"); add(build)
        add("--uuid"); add(uuid)
        add(symbolsZip.absolutePath)
    }

    /**
     * Constructs the argv vector for `bugsee-cli upload build-info` in
     * pre-signed mode. No `--endpoint` / `--app-token`: `--upload-url`
     * tells the CLI to PUT the bundle directly to the presigned URL the
     * plugin already obtained, skipping a second build registration.
     *
     * Entry flags map RAW JSON files to the bundle's stable entry names
     * (`dependencies.json` / `timings.json`); the CLI does the zstd ZIP
     * packing. Omitting a flag omits that entry — the caller passes only
     * the components it collected.
     *
     * Visible for testing so the plugin↔CLI flag contract is pinned: a
     * rename of any `upload build-info` flag flips this test at plugin-CI
     * time rather than at customer build time.
     */
    /**
     * Constructs the argv vector for `bugsee-cli pack`. No `--endpoint` /
     * `--app-token`: packing is local-only. Omitting `--mapping` produces an
     * artefact-only ZIP (non-obfuscated builds).
     *
     * Visible for testing so the plugin↔CLI flag contract is pinned: a rename
     * of any `pack` flag flips this test at plugin-CI time rather than at
     * customer build time.
     */
    internal fun buildPackArgv(
        artifactFile: File,
        mappingFile: File?,
        outZip: File,
    ): List<String> = buildList {
        add("pack")
        add("--artifact"); add(artifactFile.absolutePath)
        mappingFile?.let { add("--mapping"); add(it.absolutePath) }
        add("--out"); add(outZip.absolutePath)
    }

    internal fun buildBuildInfoArgv(
        uploadUrl: String,
        depsJsonFile: File?,
        timingsJsonFile: File?,
    ): List<String> = buildList {
        add("upload"); add("build-info")
        add("--upload-url"); add(uploadUrl)
        depsJsonFile?.let { add("--deps"); add(it.absolutePath) }
        timingsJsonFile?.let { add("--timings"); add(it.absolutePath) }
    }

    /**
     * Constructs the argv vector for `bugsee-cli upload build`. Carries
     * `--endpoint` / `--app-token` (the CLI does the registration POST, unlike
     * build-info's pre-signed mode). `--artifact` is the RAW `.aab`/`.apk` (the
     * CLI packs it + the optional `--mapping`); omit `--deps`/`--timings` when
     * not collected; `--chunked` opts into the chunked transport.
     *
     * Visible for testing so the plugin↔CLI flag contract is pinned.
     */
    @Suppress("LongParameterList")
    internal fun buildBuildArgv(
        endpoint: String,
        appToken: String,
        payloadJsonFile: File,
        artifactFile: File,
        mappingFile: File?,
        depsJsonFile: File?,
        timingsJsonFile: File?,
        chunked: Boolean,
    ): List<String> = buildList {
        add("--endpoint"); add(endpoint)
        add("--app-token"); add(appToken)
        add("upload"); add("build")
        add("--payload-json"); add(payloadJsonFile.absolutePath)
        add("--artifact"); add(artifactFile.absolutePath)
        mappingFile?.let { add("--mapping"); add(it.absolutePath) }
        depsJsonFile?.let { add("--deps"); add(it.absolutePath) }
        timingsJsonFile?.let { add("--timings"); add(it.absolutePath) }
        if (chunked) add("--chunked")
    }

    /**
     * Whether [exitCode] is one of the CLI's "structural failure" codes that
     * mean the binary never got a fair chance — and the Kotlin uploader can
     * still succeed.
     *
     * Visible for testing.
     */
    internal fun shouldFallback(exitCode: Int): Boolean =
        exitCode == EXIT_UNEXPECTED || exitCode == EXIT_USAGE
}
