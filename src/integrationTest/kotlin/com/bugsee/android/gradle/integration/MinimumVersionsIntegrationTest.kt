package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** An AGP below the minimum fails at apply time with a readable message (issue #60). */
class MinimumVersionsIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `AGP below the minimum fails with a readable message, not a linkage error`() {
        val fixture = FixtureProject.materialize("old-agp", temp.newFolder("old-agp"))

        val out = fixture.buildAndFail(null, "help").output

        assertTrue(out, out.contains("Bugsee plugin requires Android Gradle Plugin (AGP) 8.6.0 or newer (found 7.4.2)"))
        assertFalse(out, out.contains("toListenTo"))
    }
}
