package com.bugsee.android.gradle

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #141: the first-release table and its comparison against a declared core version. */
class ExtensionFirstReleaseTest {

    @Test
    fun tableMatchesMavenCentralFirstReleases() {
        assertEquals("7.0.0-beta6", ExtensionFirstRelease.of("bugsee-android-ndk"))
        assertEquals("7.0.0-beta13", ExtensionFirstRelease.of("bugsee-android-leak"))
        for (a in listOf("feedback", "compose", "okhttp", "ktor-2", "ktor-3", "cronet")) {
            assertEquals("7.0.0-beta1", ExtensionFirstRelease.of("bugsee-android-$a"), a)
        }
        assertNull(ExtensionFirstRelease.of("bugsee-android-unknown"))
    }

    @Test
    fun versionBelowFirstReleaseIsFlagged() {
        assertEquals("7.0.0-beta6", ExtensionFirstRelease.firstReleaseIfOlder("bugsee-android-ndk", "7.0.0-beta1"))
        assertEquals("7.0.0-beta6", ExtensionFirstRelease.firstReleaseIfOlder("bugsee-android-ndk", "7.0.0-beta5"))
        assertEquals("7.0.0-beta13", ExtensionFirstRelease.firstReleaseIfOlder("bugsee-android-leak", "7.0.0-beta9"))
        assertEquals("7.0.0-beta1", ExtensionFirstRelease.firstReleaseIfOlder("bugsee-android-okhttp", "6.0.4"))
    }

    @Test
    fun versionAtOrAboveFirstReleaseIsNotFlagged() {
        assertNull(ExtensionFirstRelease.firstReleaseIfOlder("bugsee-android-ndk", "7.0.0-beta6"))
        assertNull(ExtensionFirstRelease.firstReleaseIfOlder("bugsee-android-ndk", "7.0.0-beta10"), "numeric, not lexicographic")
        assertNull(ExtensionFirstRelease.firstReleaseIfOlder("bugsee-android-leak", "7.0.0-beta13-SNAPSHOT"))
        assertNull(ExtensionFirstRelease.firstReleaseIfOlder("bugsee-android-leak", "7.0.0"))
        assertNull(ExtensionFirstRelease.firstReleaseIfOlder("bugsee-android-leak", "7.0.0-SNAPSHOT"))
        assertNull(ExtensionFirstRelease.firstReleaseIfOlder("bugsee-android-okhttp", "7.0.0-beta1"))
    }

    @Test
    fun unparsableOrUnknownIsLeftToExistingBehaviour() {
        for (v in listOf("[7.3.0,)", "7.+", "latest.release", "", "7.0")) {
            assertNull(ExtensionFirstRelease.firstReleaseIfOlder("bugsee-android-ndk", v), v)
        }
        assertNull(ExtensionFirstRelease.firstReleaseIfOlder("bugsee-android-unknown", "6.0.0"))
    }

    @Test
    fun warningNamesModuleDeclaredAndFirstVersion() {
        val w = ExtensionFirstRelease.skipWarning("bugsee-android-ndk", "7.0.0-beta1", "7.0.0-beta6")
        assertTrue("bugsee-android-ndk" in w && "7.0.0-beta1" in w && "7.0.0-beta6" in w, w)
    }
}
