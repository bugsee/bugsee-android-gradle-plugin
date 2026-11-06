package com.bugsee.android.gradle.util

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimumVersionsTest {

    @Test fun `supported versions pass`() {
        MinimumVersions.check("8.6.0", "8.7")
        MinimumVersions.check("8.13.2", "9.0.0")
        MinimumVersions.check("8.6.0-beta01", "8.7-rc-1")
        MinimumVersions.check(null, "8.7")
    }

    @Test fun `gradle nightly and milestone formats are accepted`() {
        MinimumVersions.check("8.6.0", "8.7-20240115010203+0000")
        MinimumVersions.check("8.6.0", "9.0-milestone-1")
        assertTrue(MinimumVersions.compare("8.6-20240115010203+0000", "8.7") < 0)
    }

    private fun failure(agp: String?, gradle: String): String =
        assertThrows(GradleException::class.java) { MinimumVersions.check(agp, gradle) }.message!!

    @Test fun `old AGP names both found and required`() {
        val m = failure("8.2.2", "8.7")
        assertTrue(m, m.contains("8.6.0") && m.contains("8.2.2"))
        assertTrue(m, m.contains("older Bugsee plugin release") && m.contains(MinimumVersions.REQUIREMENTS_URL))
    }

    @Test fun `old Gradle names both found and required`() {
        val m = failure("8.6.0", "8.2.1")
        assertTrue(m, m.contains("8.7") && m.contains("8.2.1"))
    }

    @Test fun `both stale yields a single message listing both`() {
        val m = failure("7.4.2", "8.2.1")
        assertTrue(m, m.contains("found 8.2.1") && m.contains("found 7.4.2"))
    }

    @Test fun `compare handles uneven lengths and double digits`() {
        assertEquals(0, MinimumVersions.compare("8.7", "8.7.0"))
        assertTrue(MinimumVersions.compare("8.10", "8.7") > 0)
        assertTrue(MinimumVersions.compare("7.4.2", "8.6.0") < 0)
    }

    @Test fun `detectAgpVersion finds AGP on the classpath`() {
        val v = MinimumVersions.detectAgpVersion()
        assertNotNull("reflection on com.android.Version silently became a no-op", v)
        assertTrue(v!!, MinimumVersions.compare(v, "8.0.0") >= 0)
    }
}
