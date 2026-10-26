package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Multi-module (`:app` -> `:lib`) coverage for two properties of the plugin's cross-module state:
 *
 *  1. It survives the plugin being loaded in two classloaders. `:app` and `:lib` each request the
 *     plugin and `:lib` also applies another plugin, so Gradle hands the second module a shared
 *     build service created by the first module's copy of the plugin classes. Typing that service
 *     as the plugin's own class fails with a `ClassCastException`; only JDK types may cross.
 *  2. With Isolated Projects on, no problem is attributed to the Bugsee plugin. The fixture's AGP
 *     (8.6) is itself not Isolated Projects-clean, so the build is not required to succeed: the
 *     configuration-cache report is searched for problems whose owner is Bugsee.
 */
class IsolatedProjectsIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun two_plugin_classloaders_share_state_without_a_class_cast() {
        val fixture = FixtureProject.materialize("isolated-projects", temp.newFolder("split"))

        val result = fixture.buildTasks(listOf(":app:assembleDebug"))

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:assembleDebug")?.outcome)
        assertFalse(result.output, result.output.contains("cannot be cast to class"))
    }

    @Test
    fun no_isolated_projects_problem_is_attributed_to_the_bugsee_plugin() {
        val fixture = FixtureProject.materialize("isolated-projects", temp.newFolder("ip"))

        val result = fixture.runTasks(
            listOf(":app:assembleDebug"),
            "-Dorg.gradle.unsafe.isolated-projects=true",
            "--configuration-cache-problems=warn",
        )

        // The mode really was on (otherwise this test proves nothing).
        assertTrue(result.output, result.output.contains("isolated projects is an incubating feature", ignoreCase = true))
        assertFalse(result.output, result.output.contains("cannot be cast to class"))

        val report = reportFile(result.output)
        assertNotNull("expected a configuration-cache report in:\n${result.output}", report)
        val ownedByBugsee = Regex("com\\.bugsee\\.android\\.gradle'\"\\}\\],\"problem\":\\[(.*?)\\]")
            .findAll(report!!.readText())
            .map { m -> Regex("\"(?:text|name)\":\"(.*?)\"").findAll(m.groupValues[1]).joinToString("") { it.groupValues[1] } }
            .toList()
        assertTrue("Isolated Projects problems attributed to the Bugsee plugin:\n$ownedByBugsee", ownedByBugsee.isEmpty())
    }

    private fun reportFile(output: String): File? =
        Regex("file://(\\S+configuration-cache-report\\.html)").find(output)?.groupValues?.get(1)?.let { File(it) }
}
