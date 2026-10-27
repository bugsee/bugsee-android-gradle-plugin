package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertFalse
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
            resolvesTo(output, "com.android.tools.build:gradle", agp),
        )
        val kotlin = FixtureProject.kotlinVersion ?: DEFAULT_KOTLIN
        assertTrue(
            "expected org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin (resolved) in:\n$output",
            resolvesTo(output, "org.jetbrains.kotlin:kotlin-gradle-plugin", kotlin),
        )
    }

    /**
     * The matcher must be exact: `buildEnvironment` prints a request that was bumped by conflict
     * resolution as `coord:2.2.0 -> 2.2.10`, which a substring match for `:2.2.0` would accept
     * although the build ran on 2.2.10.
     */
    @Test
    fun resolvesTo_requires_the_exact_resolved_version() {
        val kgp = "org.jetbrains.kotlin:kotlin-gradle-plugin"
        assertTrue(resolvesTo("+--- $kgp:2.2.0", kgp, "2.2.0"))
        assertTrue(resolvesTo("\\--- $kgp:2.2.0 (*)", kgp, "2.2.0"))
        assertFalse("bumped by conflict resolution", resolvesTo("+--- $kgp:2.2.0 -> 2.2.10", kgp, "2.2.0"))
        assertFalse("prefix of a longer version", resolvesTo("+--- $kgp:2.2.0-RC", kgp, "2.2.0"))
        assertFalse("prefix of a longer version", resolvesTo("+--- $kgp:2.2.10", kgp, "2.2.1"))
        assertTrue("the version it was bumped TO", resolvesTo("+--- $kgp:2.2.10", kgp, "2.2.10"))
    }

    private companion object {
        /** True when some `buildEnvironment` line shows [coordinate] resolved at exactly [version]. */
        fun resolvesTo(output: String, coordinate: String, version: String): Boolean {
            val exact = Regex("""${Regex.escape("$coordinate:$version")}(?:\s+\([^)]*\))?\s*$""")
            return output.lines().any { exact.containsMatchIn(it) }
        }

        // The fixtures' declared defaults (the plugin's minimum supported AGP, the Kotlin it is built with).
        const val DEFAULT_AGP = "8.6.0"
        const val DEFAULT_KOTLIN = "2.1.0"
    }
}
