package com.bugsee.android.gradle

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the contract of [CoreSdkAutoLoad.range]: given the
 * SDK minimum version recorded in `sdk-min-version.txt`, return a
 * Gradle dynamic-version range whose ceiling is the next MAJOR.
 *
 * The auto-pulled core SDK must track the **latest release** of the
 * MAJOR series the plugin was released against — including newer
 * minor and patch releases, but never silently crossing into a newer
 * MAJOR that may have breaking changes.
 */
class CoreSdkAutoLoadRangeTest {

    @Test
    fun stable_major_floor() {
        assertEquals("[7.0.0,8.0.0)", CoreSdkAutoLoad.range("7.0.0"))
    }

    @Test
    fun stable_patch_floor_allows_newer_minor_releases() {
        // The ceiling is the next MAJOR, not the next PATCH or MINOR —
        // a 7.0.5 floor still allows 7.1.x and 7.9.x but not 8.0.0.
        assertEquals("[7.0.5,8.0.0)", CoreSdkAutoLoad.range("7.0.5"))
    }

    @Test
    fun prerelease_floor_keeps_label_intact() {
        // The prerelease suffix is part of the floor verbatim; the
        // ceiling is still next-MAJOR-stable.
        assertEquals("[7.0.0-beta13,8.0.0)", CoreSdkAutoLoad.range("7.0.0-beta13"))
    }

    @Test
    fun rc_prerelease_floor() {
        assertEquals("[8.2.3-rc1,9.0.0)", CoreSdkAutoLoad.range("8.2.3-rc1"))
    }

    @Test
    fun minor_rollover_keeps_next_major_ceiling() {
        assertEquals("[7.9.0,8.0.0)", CoreSdkAutoLoad.range("7.9.0"))
    }

    @Test
    fun unparseable_falls_back_to_open_ended_range() {
        // Anything BugseeSdkVersion.parse can't recognise should not
        // produce a malformed Gradle coordinate. Open-ended range is
        // the documented fallback.
        assertEquals("[7.+,)", CoreSdkAutoLoad.range("7.+"))
        assertEquals("[snapshot,)", CoreSdkAutoLoad.range("snapshot"))
        assertEquals("[,)", CoreSdkAutoLoad.range(""))
    }
}
