package com.bugsee.android.gradle.instrumentation

import com.bugsee.android.gradle.instrumentation.SdkVersionAlignment.Module
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkVersionAlignmentTest {

    private fun m(name: String, version: String, group: String = "com.bugsee") = Module(group, name, version)

    @Test
    fun `no warning when all SDK modules share a version`() {
        assertNull(
            SdkVersionAlignment.mismatchMessage(
                "debug", listOf(m("bugsee-android", "7.1.0"), m("bugsee-android-okhttp", "7.1.0")),
            )
        )
    }

    @Test
    fun `no warning for a single module or none`() {
        assertNull(SdkVersionAlignment.mismatchMessage("debug", listOf(m("bugsee-android", "7.1.0"))))
        assertNull(SdkVersionAlignment.mismatchMessage("debug", emptyList()))
    }

    @Test
    fun `warns naming every module and version and the variant`() {
        val msg = SdkVersionAlignment.mismatchMessage(
            "release", listOf(m("bugsee-android", "7.1.0"), m("bugsee-android-okhttp", "7.0.2")),
        )
        assertNotNull(msg)
        assertTrue(msg!!, "variant release" in msg)
        assertTrue(msg, "com.bugsee:bugsee-android:7.1.0" in msg)
        assertTrue(msg, "com.bugsee:bugsee-android-okhttp:7.0.2" in msg)
    }

    @Test
    fun `ignores non-SDK modules, the plugin artifact and other groups`() {
        assertNull(
            SdkVersionAlignment.mismatchMessage(
                "debug",
                listOf(
                    m("bugsee-android", "7.1.0"),
                    m("bugsee-android-gradle-plugin", "4.0.0"),
                    m("other", "1.0.0"),
                    m("bugsee-android", "3.0.0", group = "org.example"),
                ),
            )
        )
    }

    @Test
    fun `duplicates of the same module are not a mismatch`() {
        assertNull(
            SdkVersionAlignment.mismatchMessage(
                "debug", listOf(m("bugsee-android", "7.1.0"), m("bugsee-android", "7.1.0")),
            )
        )
    }
}
