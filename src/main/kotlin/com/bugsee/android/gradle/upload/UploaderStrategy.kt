package com.bugsee.android.gradle.upload

/**
 * Selects which uploader runs for ProGuard mapping and (future) NDK symbol
 * uploads.
 *
 * Phase-1 default is [KOTLIN] — the built-in `SymbolUploader` continues to
 * handle everything. Set `bugsee.uploader = cli` in `bugsee.properties` (or
 * `bugsee { uploader.set(UploaderStrategy.CLI) }` in build.gradle.kts) to opt
 * into the `bugsee-cli` binary referenced by [BugseePluginExtension.cliPath].
 *
 * When the CLI is selected but fails *structurally* (binary missing, exec
 * failed, exit codes 1 or 2 per the CLI's documented contract), the task
 * falls back to the Kotlin uploader so the build still ships symbols. The
 * fallback transition is logged at WARN and stamped into the
 * `X-Bugsee-Uploader` request header so backend can count fallback rates.
 *
 * Substantive CLI failures (bad token, network, server 4xx/5xx) propagate
 * up — the fallback would hit the same error.
 *
 * See `bugsee-cli` README (`~/Projects/Bugsee/bugsee-cli/README.md`) for the
 * exit-code contract and the dual-path rollout plan.
 */
enum class UploaderStrategy {
    /** Use the built-in Kotlin [SymbolUploader] directly. */
    KOTLIN,

    /**
     * Exec the `bugsee-cli` binary at [BugseePluginExtension.cliPath]; on a
     * structural failure (CLI exit code 1 or 2), fall back to [KOTLIN].
     */
    CLI,
}
