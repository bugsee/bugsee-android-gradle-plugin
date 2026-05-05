package com.bugsee.android.gradle.upload

import java.util.Locale

/**
 * Pure threshold evaluation for the in-build size-check feature.
 * Kept side-effect free + parameterised so the logic can be unit-
 * tested without standing up a Gradle harness — the calling task
 * does the I/O (baseline fetch, log emission, throw).
 */
internal object SizeCheckEvaluator {

    /**
     * Trigger threshold collection. Each value is `null` when the
     * corresponding gate is disabled — either unset by the user or
     * explicitly `0` (which the resolver normalises to `null` so
     * "0 == disabled" is enforced in one place).
     */
    internal data class Thresholds(
        val warningPercent: Double?,
        val failPercent: Double?,
        val warningBytes: Long?,
        val failBytes: Long?,
    ) {
        /**
         * `true` when at least one gate is active. A check with no
         * active gate is a no-op — the caller should skip it (or log
         * "configured but inert") rather than fetching a baseline
         * just to discard the result.
         */
        val anyActive: Boolean
            get() = warningPercent != null ||
                    failPercent != null ||
                    warningBytes != null ||
                    failBytes != null
    }

    /** Outcome of evaluating a delta against the configured thresholds. */
    internal enum class Outcome { PASS, WARN, FAIL }

    /**
     * Pre-computed delta + result of threshold evaluation.
     *
     * `deltaPercent` is the relative change as a percentage —
     * `(local - baseline) / baseline * 100`. `deltaBytes` is the raw
     * byte difference (signed). Both can be negative when the
     * artifact shrunk; in that case `outcome` is always [Outcome.PASS]
     * regardless of thresholds (negative deltas never trigger a gate).
     *
     * `triggeredBy` names the gate that produced the outcome — useful
     * for the log line ("exceeds fail threshold 10%") so the user
     * doesn't have to cross-reference their config to figure out which
     * gate fired.
     */
    internal data class Result(
        val deltaBytes: Long,
        val deltaPercent: Double,
        val outcome: Outcome,
        val triggeredBy: String?,
    )

    /**
     * Evaluate a (localSize, baselineSize) pair against the
     * thresholds. Always returns a [Result]; the caller decides what
     * to do with it.
     *
     * Edge cases:
     *   - `baselineSize <= 0` is undefined (the caller should not
     *     have produced a baseline in the first place); we still
     *     return a sane PASS rather than throwing, so a server that
     *     somehow returned a zero baseline can't fail the build.
     */
    fun evaluate(
        localSize: Long,
        baselineSize: Long,
        thresholds: Thresholds,
    ): Result {
        if (baselineSize <= 0L) {
            return Result(0L, 0.0, Outcome.PASS, null)
        }
        val deltaBytes = localSize - baselineSize
        val deltaPercent = deltaBytes.toDouble() / baselineSize.toDouble() * 100.0

        if (deltaBytes <= 0L) {
            // Shrunk or unchanged. No gate ever triggers on a
            // non-positive delta — the user wants a guard against
            // *growth*, not against improvements.
            return Result(deltaBytes, deltaPercent, Outcome.PASS, null)
        }

        // Fail thresholds win. Check both gates; the first one that
        // crosses produces the message. We deliberately check
        // percent first because percent thresholds tend to be the
        // "primary" gate in practice — the user reads the log line
        // and recognises the configured number more quickly.
        thresholds.failPercent?.let {
            if (deltaPercent >= it) {
                return Result(deltaBytes, deltaPercent, Outcome.FAIL, "failPercent ${formatPct(it)}")
            }
        }
        thresholds.failBytes?.let {
            if (deltaBytes >= it) {
                return Result(deltaBytes, deltaPercent, Outcome.FAIL, "failBytes ${formatBytes(it)}")
            }
        }
        thresholds.warningPercent?.let {
            if (deltaPercent >= it) {
                return Result(deltaBytes, deltaPercent, Outcome.WARN, "warningPercent ${formatPct(it)}")
            }
        }
        thresholds.warningBytes?.let {
            if (deltaBytes >= it) {
                return Result(deltaBytes, deltaPercent, Outcome.WARN, "warningBytes ${formatBytes(it)}")
            }
        }
        return Result(deltaBytes, deltaPercent, Outcome.PASS, null)
    }

    /**
     * Format a positive byte count using SI-ish suffixes — chosen to
     * match the Bugsee viewer's `formatSize` so the build log and the
     * dashboard speak the same units. Negative values use the
     * positive form prefixed with `-` (callers also prefix `+` for
     * non-negative deltas at their own level).
     *
     * Locale-pinned to `Locale.ROOT` so a CI host with a non-en
     * locale (e.g. `de_DE`) doesn't render `1,5 MB` instead of
     * `1.5 MB` — the comma/period swap would corrupt log-grep
     * patterns that detect WARN/FAIL outcomes.
     */
    fun formatBytes(bytes: Long): String {
        val abs = if (bytes < 0L) -bytes else bytes
        return when {
            abs < 1024L                        -> "$bytes B"
            abs < 1024L * 1024L                -> String.format(Locale.ROOT, "%.1f KB", bytes.toDouble() / 1024.0)
            abs < 1024L * 1024L * 1024L        -> String.format(Locale.ROOT, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
            else                               -> String.format(Locale.ROOT, "%.2f GB", bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun formatPct(pct: Double): String =
        if (pct == pct.toLong().toDouble()) "${pct.toLong()}%"
        else String.format(Locale.ROOT, "%.1f%%", pct)

    /**
     * Render the full size-check log line. Format is shared between
     * Android and iOS — same `"<old> → <new> (+8.3%, +1.2 MB) vs
     * version 1.4.2 (318) — exceeds X threshold Y"` shape the iOS
     * BugseeAgent will emit, so users reading both platforms' build
     * logs see consistent wording.
     */
    fun formatMessage(
        localSize: Long,
        baselineSize: Long,
        baselineVersion: String?,
        baselineBuild: String?,
        result: Result,
    ): String {
        val baselineLabel = buildString {
            if (!baselineVersion.isNullOrBlank()) append("version $baselineVersion")
            if (!baselineBuild.isNullOrBlank()) {
                if (isNotEmpty()) append(' ')
                append("($baselineBuild)")
            }
            if (isEmpty()) append("previous build")
        }

        val deltaPctSigned = if (result.deltaBytes >= 0) "+${formatPct(result.deltaPercent)}"
                             else formatPct(result.deltaPercent)
        val deltaBytesSigned = if (result.deltaBytes >= 0) "+${formatBytes(result.deltaBytes)}"
                               else formatBytes(result.deltaBytes)

        val head = "Bugsee size check: ${formatBytes(baselineSize)} → ${formatBytes(localSize)} " +
                "($deltaPctSigned, $deltaBytesSigned) vs $baselineLabel"

        return when (result.outcome) {
            Outcome.PASS -> head
            Outcome.WARN -> "$head — exceeds ${result.triggeredBy ?: "warning threshold"}"
            Outcome.FAIL -> "$head — exceeds ${result.triggeredBy ?: "fail threshold"}"
        }
    }
}
