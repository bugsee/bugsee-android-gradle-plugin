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
     * Whether [exitCode] is one of the CLI's "structural failure" codes that
     * mean the binary never got a fair chance — and the Kotlin uploader can
     * still succeed.
     *
     * Visible for testing.
     */
    internal fun shouldFallback(exitCode: Int): Boolean =
        exitCode == EXIT_UNEXPECTED || exitCode == EXIT_USAGE
}
