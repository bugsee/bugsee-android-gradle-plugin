package com.bugsee.android.gradle.util

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
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

    @Test fun `old AGP names both found and required`() {
        val e = assertThrows(GradleException::class.java) { MinimumVersions.check("8.2.2", "8.7") }
        val m = e.message!!
        assertTrue(m, m.contains("8.6.0") && m.contains("8.2.2"))
    }

    @Test fun `old Gradle names both found and required`() {
        val e = assertThrows(GradleException::class.java) { MinimumVersions.check("8.6.0", "8.2.1") }
        val m = e.message!!
        assertTrue(m, m.contains("8.7") && m.contains("8.2.1"))
    }

    @Test fun `compare handles uneven lengths and double digits`() {
        assertEquals(0, MinimumVersions.compare("8.7", "8.7.0"))
        assertTrue(MinimumVersions.compare("8.10", "8.7") > 0)
        assertTrue(MinimumVersions.compare("7.4.2", "8.6.0") < 0)
    }
}
