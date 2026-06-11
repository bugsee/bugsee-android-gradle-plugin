package com.bugsee.android.gradle

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the contract of [CoreSdkAutoLoad.range]: given the
 * SDK minimum version recorded in `sdk-min-version.txt`, return a
 * Gradle dynamic-version range whose ceiling is the next MINOR.
 *
 * The auto-pulled core SDK must track the **latest patch** of the
 * MAJOR.MINOR series the plugin was released against — never silently
 * cross into a newer MINOR that may have breaking changes.
 */
class CoreSdkAutoLoadRangeTest {

    @Test
    fun stable_minor_floor() {
        assertEquals("[7.0.0,7.1.0)", CoreSdkAutoLoad.range("7.0.0"))
    }

    @Test
    fun stable_patch_floor_does_not_pin_patch_ceiling() {
        // The ceiling is the next MINOR, not the next PATCH — a 7.0.5
        // floor still allows 7.0.99 but not 7.1.0.
        assertEquals("[7.0.5,7.1.0)", CoreSdkAutoLoad.range("7.0.5"))
    }

    @Test
    fun prerelease_floor_keeps_label_intact() {
        // The prerelease suffix is part of the floor verbatim; the
        // ceiling is still next-MINOR-stable.
        assertEquals("[7.0.0-beta12,7.1.0)", CoreSdkAutoLoad.range("7.0.0-beta12"))
    }

    @Test
    fun rc_prerelease_floor() {
        assertEquals("[8.2.3-rc1,8.3.0)", CoreSdkAutoLoad.range("8.2.3-rc1"))
    }

    @Test
    fun minor_rollover_increments_minor_not_major() {
        // 7.9.x → 7.10.0 (NOT 8.0.0). The ceiling is per-MINOR.
        assertEquals("[7.9.0,7.10.0)", CoreSdkAutoLoad.range("7.9.0"))
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
