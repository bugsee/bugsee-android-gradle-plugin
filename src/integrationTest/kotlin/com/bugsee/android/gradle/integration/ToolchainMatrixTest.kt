package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Guards the toolchain matrix itself: when CI asks for Gradle X / AGP Y / Kotlin Z
 * (`-Pbugsee.it.gradleVersion|agpVersion|kotlinVersion`), the fixture builds must really run on
 * them. Without this a typo'd or ignored property would leave every matrix leg quietly testing the
 * default toolchain and reporting green.
 */
class ToolchainMatrixTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun fixtures_run_on_the_requested_gradle_agp_and_kotlin() {
        val fixture = FixtureProject.materialize("app-startup-tracing", temp.newFolder("toolchain"))
        // TestKit rejects `--version`, so an init script reports the Gradle that actually ran.
        val initScript = temp.newFile("report-gradle.init.gradle").apply {
            writeText("gradle.rootProject { println 'RUNNING_GRADLE=' + gradle.gradleVersion }\n")
        }
        val output = FixtureProject.runner(fixture.projectDir).withArguments(
            "buildEnvironment", "-I", initScript.absolutePath,
            "-PbugseeStubSdkRepo=${System.getProperty("bugsee.testkit.stubSdkRepo")}",
            "-PbugseePluginProjectDir=${System.getProperty("bugsee.testkit.pluginProjectDir")}",
        ).build().output

        FixtureProject.gradleVersion?.let {
            assertTrue("expected RUNNING_GRADLE=$it in:\n$output", output.lines().any { l -> l.trim() == "RUNNING_GRADLE=$it" })
        }
        val agp = FixtureProject.agpVersion ?: DEFAULT_AGP
        assertTrue(
            "expected com.android.tools.build:gradle:$agp (resolved) in:\n$output",
            output.contains("com.android.tools.build:gradle:$agp"),
        )
        val kotlin = FixtureProject.kotlinVersion ?: DEFAULT_KOTLIN
        assertTrue(
            "expected org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin in:\n$output",
            output.contains("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin"),
        )
    }

    private companion object {
        // The fixtures' declared defaults (the plugin's minimum supported AGP, the Kotlin it is built with).
        const val DEFAULT_AGP = "8.6.0"
        const val DEFAULT_KOTLIN = "2.1.0"
    }
}
