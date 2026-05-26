package com.bugsee.android.gradle.instrumentation

/**
 * Minimal semver-flavored version comparator used to detect when the
 * `com.bugsee:bugsee-android` runtime dependency is older than a
 * required minimum for a given plugin-injected SDK symbol.
 *
 * Supports versions of the form `MAJOR.MINOR.PATCH` with an optional
 * pre-release suffix `-LABEL[NUMBER]` (e.g. `7.0.0`, `7.0.0-beta11`,
 * `7.0.0-rc1`, `7.0.0-alpha`). Snapshot suffixes (`-SNAPSHOT`,
 * `-LOCAL`) are treated as the most recent build of the parsed
 * pre-release — i.e. `7.0.0-beta11-SNAPSHOT` is treated as exactly
 * `7.0.0-beta11`, on the assumption that the consumer is testing
 * against the very build they're shipping.
 *
 * Pre-release ordering follows semver: a version with a pre-release
 * suffix is LOWER than the same version without one (i.e.
 * `7.0.0-beta11 < 7.0.0`). Pre-release labels are compared
 * alphabetically (`alpha` < `beta` < `rc`) and the numeric suffix
 * within a label is compared numerically (`beta2 < beta11`, NOT the
 * lexicographic `beta11 < beta2`).
 *
 * `7.0.0-SNAPSHOT` (no pre-release label, just a snapshot suffix) is
 * parsed as a STABLE `7.0.0` — i.e. ranks above any `7.0.0-betaN`.
 * That's intentional: a developer running a local `-SNAPSHOT` of the
 * stable line is testing what will ship as `7.0.0`, so the gate
 * should accept it. Contrast with `7.0.0-beta11-SNAPSHOT`, which
 * preserves the `-beta11` pre-release rank.
 *
 * Build metadata (`+sha.abc`), dotted pre-releases (`-rc.1`), and
 * non-numeric suffix shapes are deliberately NOT supported: the
 * Bugsee SDK ships `MAJOR.MINOR.PATCH[-labelN]` exclusively, and a
 * permissive grammar would invite drift. Such inputs return `null`
 * (caller proceeds without the gate — see the unparseable note below).
 *
 * Unparseable versions return `null` from [parse] — callers should be
 * permissive (skip the check) rather than erroring out, because the
 * input may legitimately be a Gradle range (`7.+`, `[7.0,8.0)`) or
 * a project-dep without a string version.
 */
internal data class BugseeSdkVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /**
     * Pre-release label (`"beta"`, `"rc"`, etc.) or `""` for a stable
     * release. Stable sorts AFTER any pre-release (per semver §11.4.4),
     * so we represent it as an empty string with a comparator that
     * special-cases `""` as the "highest" label.
     */
    val preLabel: String,
    /**
     * Numeric suffix within a pre-release (`11` for `beta11`), or
     * `-1` if no numeric suffix is present (`alpha` → `-1`).
     */
    val preNumber: Int,
) : Comparable<BugseeSdkVersion> {

    /**
     * Renders as `MAJOR.MINOR.PATCH[-labelN]` — the canonical shape we
     * parse from. Overrides the data-class default so user-facing
     * logs (e.g. the `shouldApply` warn message) read cleanly rather
     * than dumping the data-class internal structure.
     */
    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (preLabel.isNotEmpty()) {
            append('-').append(preLabel)
            if (preNumber >= 0) append(preNumber)
        }
    }

    override fun compareTo(other: BugseeSdkVersion): Int {
        val majorCmp = major.compareTo(other.major)
        if (majorCmp != 0) return majorCmp
        val minorCmp = minor.compareTo(other.minor)
        if (minorCmp != 0) return minorCmp
        val patchCmp = patch.compareTo(other.patch)
        if (patchCmp != 0) return patchCmp

        // Semver: a stable release outranks any pre-release of the
        // same MAJOR.MINOR.PATCH. Represent "" (empty label) as
        // logically higher than any non-empty label.
        if (preLabel.isEmpty() && other.preLabel.isNotEmpty()) return 1
        if (preLabel.isNotEmpty() && other.preLabel.isEmpty()) return -1

        val labelCmp = preLabel.compareTo(other.preLabel)
        if (labelCmp != 0) return labelCmp
        return preNumber.compareTo(other.preNumber)
    }

    companion object {
        // Matches MAJOR.MINOR.PATCH[-LABEL[NUMBER]][-SNAPSHOT|-LOCAL].
        // Examples accepted:
        //   7.0.0
        //   7.0.0-beta11
        //   7.0.0-rc1
        //   7.0.0-alpha
        //   7.0.0-beta11-SNAPSHOT
        //   7.0.0-SNAPSHOT
        private val PATTERN = Regex(
            """^(\d+)\.(\d+)\.(\d+)(?:-([A-Za-z]+)(\d+)?)?(?:-(?:SNAPSHOT|LOCAL))?$"""
        )

        fun parse(s: String?): BugseeSdkVersion? {
            if (s.isNullOrBlank()) return null
            val match = PATTERN.matchEntire(s.trim()) ?: return null
            val (mj, mn, pt, lbl, num) = match.destructured
            return BugseeSdkVersion(
                major = mj.toInt(),
                minor = mn.toInt(),
                patch = pt.toInt(),
                preLabel = lbl,
                preNumber = num.toIntOrNull() ?: -1,
            )
        }
    }
}
