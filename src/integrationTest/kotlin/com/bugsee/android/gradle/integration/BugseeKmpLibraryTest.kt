package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * AGP's Kotlin Multiplatform library plugin (`com.android.kotlin.multiplatform.library`) applied
 * together with the Bugsee plugin (issue #92).
 *
 * The plugin has no `android` extension and no AndroidComponentsExtension, so before the fix the
 * "requires com.android.application or com.android.library" check failed the module at configuration
 * time. It is now a library module: it gets the library-module warning, no Bugsee tasks are wired,
 * and the build succeeds.
 *
 * The KMP library plugin exists from AGP 8.10, so on the default AGP 8.6.0 these tests are skipped;
 * the toolchain-matrix legs (AGP 8.13 / 9.x) run them.
 */
class BugseeKmpLibraryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val warning = "Bugsee: :kmp is a library module."

    @Test
    fun kmp_library_module_gets_the_library_warning_once_and_builds() {
        assumeKmpLibraryPluginAvailable()
        val fixture = FixtureProject.materialize("kmp-library", temp.newFolder("kmp-default"))

        val output = runTasks(fixture.projectDir, ":kmp:tasks", "--all").output
        // The module also compiles (Compose-free here; the Bugsee compiler plugin must stay out of KMP).
        runTasks(fixture.projectDir, ":kmp:compileAndroidMain")

        assertEquals(
            "expected exactly one library-module warning. Output:\n$output",
            1,
            Regex(Regex.escape(warning)).findAll(output).count(),
        )
        assertFalse(output, output.contains("Bugsee requires 'com.android.application'"))
    }

    @Test
    fun kmp_library_module_gets_no_bugsee_tasks() {
        assumeKmpLibraryPluginAvailable()
        val fixture = FixtureProject.materialize("kmp-library", temp.newFolder("kmp-tasks"))

        val output = runTasks(fixture.projectDir, ":kmp:tasks", "--all").output
        val deps = runTasks(fixture.projectDir, ":kmp:dependencies").output
        assertFalse("no Bugsee SDK may be auto-added to a KMP module:\n$deps", deps.contains("com.bugsee:"))

        val bugseeTasks = output.lines().filter { Regex("^[a-z]\\S*[Bb]ugsee\\S* ").containsMatchIn(it) }
        assertTrue("no Bugsee task may be wired for a library module: $bugseeTasks", bugseeTasks.isEmpty())
    }

    @Test
    fun warnLibraryModule_false_in_the_dsl_silences_the_kmp_warning() {
        assumeKmpLibraryPluginAvailable()
        val fixture = FixtureProject.materialize("kmp-library", temp.newFolder("kmp-dsl"))
        fixture.projectDir.resolve("kmp/build.gradle.kts").appendText("\nbugsee { warnLibraryModule.set(false) }\n")

        val output = runTasks(fixture.projectDir, ":kmp:tasks").output

        assertFalse(output, output.contains("is a library module."))
    }

    @Test
    fun warnLibraryModule_false_in_bugsee_properties_silences_the_kmp_warning() {
        assumeKmpLibraryPluginAvailable()
        val fixture = FixtureProject.materialize("kmp-library", temp.newFolder("kmp-props"))
        fixture.projectDir.resolve("bugsee.properties").writeText("plugin.warnLibraryModule=false\n")

        val output = runTasks(fixture.projectDir, ":kmp:tasks").output

        assertFalse(output, output.contains("is a library module."))
    }

    @Test
    fun bugsee_sdk_group_kmp_module_is_exempt() {
        assumeKmpLibraryPluginAvailable()
        val fixture = FixtureProject.materialize("kmp-library", temp.newFolder("kmp-sdk-group"))

        val output = runTasks(fixture.projectDir, ":kmp:tasks", "-PbugseeFixtureGroup=com.bugsee.sdk").output

        assertFalse(output, output.contains("is a library module."))
    }

    @Test
    fun kmp_library_module_is_configuration_cache_compatible() {
        assumeKmpLibraryPluginAvailable()
        val fixture = FixtureProject.materialize("kmp-library", temp.newFolder("kmp-cc"))

        val first = runTasks(fixture.projectDir, ":kmp:tasks", "--configuration-cache").output
        val second = runTasks(fixture.projectDir, ":kmp:tasks", "--configuration-cache").output

        assertTrue(first, first.contains("Configuration cache entry stored"))
        assertTrue(second, second.contains("Reusing configuration cache"))
        assertFalse(first, Regex("\\d+ problems? (was|were) found").containsMatchIn(first))
    }

    /** AGP >= 8.10 ships the plugin; the default (unset) toolchain is AGP 8.6.0, which does not. */
    private fun assumeKmpLibraryPluginAvailable() {
        val parts = FixtureProject.agpVersion?.split('.')?.map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        assumeTrue(
            "needs AGP >= 8.10 (-Pbugsee.it.agpVersion); default AGP 8.6.0 has no KMP library plugin",
            parts != null && (parts[0] > 8 || (parts[0] == 8 && parts.getOrElse(1) { 0 } >= 10)),
        )
    }

    private fun runTasks(projectDir: File, vararg args: String): org.gradle.testkit.runner.BuildResult =
        FixtureProject.runner(projectDir)
            .withArguments(
                *args,
                "-PbugseeStubSdkRepo=${System.getProperty("bugsee.testkit.stubSdkRepo")}",
                "-PbugseePluginProjectDir=${System.getProperty("bugsee.testkit.pluginProjectDir")}",
                "--stacktrace",
            )
            .build()
}
