package com.bugsee.android.gradle

import com.bugsee.android.gradle.instrumentation.BugseeSdkVersion

/**
 * Helpers for the plugin's auto-load of the core `com.bugsee:bugsee-android`
 * SDK when an extension module is declared but the core is not.
 *
 * Lives outside [BugseePlugin] (which inherits from AGP/KGP plugin
 * interfaces unavailable on the unit-test classpath) so the range
 * builder can be unit-tested directly.
 */
internal object CoreSdkAutoLoad {

    /**
     * Builds the Gradle dynamic-version range for the auto-loaded
     * core SDK so consumers pick up the latest release within the
     * same `MAJOR` series as `sdk-min-version`. Floor is the parsed
     * [min] (inclusive); ceiling is `(MAJOR+1).0.0` exclusive, so a
     * `7.0.x` floor pulls any future `7.y.z` release but never
     * crosses into `8.0.0`.
     *
     * Falls back to the open-ended legacy range `[$min,)` when
     * [min] isn't parseable as SemVer — for the same reason
     * [BugseeSdkVersion.parse] is lenient: an unknown shape is
     * safer to forward than to choke on.
     */
    fun range(min: String): String {
        val parsed = BugseeSdkVersion.parse(min) ?: return "[$min,)"
        val ceiling = "${parsed.major + 1}.0.0"
        return "[$min,$ceiling)"
    }
}
