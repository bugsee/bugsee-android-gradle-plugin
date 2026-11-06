package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** An AGP below the minimum fails at apply time with a readable message (issue #60). */
class MinimumVersionsIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `AGP below the minimum fails with a readable message, not a linkage error`() {
        // The fixture pins AGP 7.4.2 on purpose and that AGP cannot run on the newer Gradles of the
        // toolchain matrix (it would fail before the plugin applies), so only the default lane runs it.
        assumeTrue("old-agp runs on the default toolchain only", FixtureProject.gradleVersion == null)
        val fixture = FixtureProject.materialize("old-agp", temp.newFolder("old-agp"), toolchainOverrides = false)

        val out = fixture.buildAndFail(null, "help").output

        assertTrue(out, out.contains("Bugsee plugin requires Android Gradle Plugin (AGP) 8.6.0 or newer (found 7.4.2)"))
        assertFalse(out, out.contains("toListenTo"))
    }

    @Test
    fun `AGP below the minimum fails readably when Bugsee is applied before the Android plugin`() {
        assumeTrue("old-agp runs on the default toolchain only", FixtureProject.gradleVersion == null)
        val fixture = FixtureProject.materialize("old-agp", temp.newFolder("old-agp-bugsee-first"), toolchainOverrides = false)
        // Same fixture, plugins listed the other way round: the `android` extension does not exist
        // yet when the Bugsee plugin applies, so the AGP half of the check has to run later.
        val appBuild = fixture.projectDir.resolve("app/build.gradle.kts")
        appBuild.writeText(
            appBuild.readText().replace(
                "    id(\"com.android.application\")\n    id(\"com.bugsee.android.gradle\")",
                "    id(\"com.bugsee.android.gradle\")\n    id(\"com.android.application\")",
            ),
        )
        assertTrue(appBuild.readText().indexOf("com.bugsee.android.gradle") < appBuild.readText().indexOf("com.android.application"))

        val out = fixture.buildAndFail(null, "help").output

        assertTrue(out, out.contains("Bugsee plugin requires Android Gradle Plugin (AGP) 8.6.0 or newer (found 7.4.2)"))
        assertFalse(out, out.contains("toListenTo"))
    }
}
