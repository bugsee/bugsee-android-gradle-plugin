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
 * Bugsee applied to modules that are not application or `com.android.library` modules (issue #159).
 * A module type must never fail the build:
 *  - `com.android.fused-library` (no `android` extension) is a library module: the library-module warning.
 *  - `com.android.lint` (no `android` extension) and a plain Kotlin/JVM module in a build that has AGP on
 *    the shared classpath get the "no Android application or library plugin" warning.
 * Nothing is wired in any of them. Before the fix each of these failed at configuration time with
 * "Bugsee requires 'com.android.application', 'com.android.library' or ...".
 */
class BugseeModuleTypesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fusedWarning = "Bugsee: :fused is a library module."
    private val lintWarning = "Bugsee: :lint has no Android application or library plugin"
    private val jvmWarning = "Bugsee: :jvm has no Android application or library plugin"

    private fun count(output: String, text: String) = Regex(Regex.escape(text)).findAll(output).count()

    @Test
    fun lint_and_jvm_modules_warn_exactly_once_and_the_build_succeeds() {
        val fixture = materialize("types")

        val output = runTasks(fixture.projectDir, "tasks", "--all").output

        assertEquals("lint warning count. Output:\n$output", 1, count(output, lintWarning))
        assertEquals("jvm warning count. Output:\n$output", 1, count(output, jvmWarning))
        assertFalse(output, output.contains("Bugsee requires"))
        assertTrue(output, output.contains("README, \"Library modules\""))
    }

    @Test
    fun fused_library_is_a_library_module_and_builds() {
        assumeFusedLibraryAvailable()
        val fixture = materialize("fused")

        val output = runTasks(fixture.projectDir, "tasks", "--all", "-PbugseeFixtureFused=true").output

        assertEquals("fused library warning count. Output:\n$output", 1, count(output, fusedWarning))
        assertEquals(output, 0, count(output, "Bugsee: :fused has no Android"))
        assertFalse(output, output.contains("Bugsee requires"))
    }

    @Test
    fun no_bugsee_tasks_are_wired_in_lint_and_jvm_modules() {
        val fixture = materialize("tasks")

        for (module in listOf("lint", "jvm")) {
            val output = runTasks(fixture.projectDir, ":$module:tasks", "--all").output
            val bugseeTasks = output.lines().filter { Regex("^[a-z]\\S*[Bb]ugsee\\S*(\\s|$)").containsMatchIn(it) }
            assertTrue(":$module must have no Bugsee task: $bugseeTasks", bugseeTasks.isEmpty())
        }
    }

    @Test
    fun no_bugsee_tasks_are_wired_in_the_fused_library() {
        assumeFusedLibraryAvailable()
        val fixture = materialize("fused-tasks")

        val output = runTasks(fixture.projectDir, ":fused:tasks", "--all", "-PbugseeFixtureFused=true").output
        val bugseeTasks = output.lines().filter { Regex("^[a-z]\\S*[Bb]ugsee\\S*(\\s|$)").containsMatchIn(it) }
        assertTrue("fused must have no Bugsee task: $bugseeTasks", bugseeTasks.isEmpty())
    }

    @Test
    fun the_sdk_is_not_auto_added_to_a_jvm_module_and_it_compiles() {
        val fixture = materialize("jvm-deps")

        val deps = runTasks(fixture.projectDir, ":jvm:dependencies", "--configuration", "implementation").output
        runTasks(fixture.projectDir, ":jvm:compileKotlin")

        assertFalse("no Bugsee SDK may be auto-added to a JVM module:\n$deps", deps.contains("com.bugsee:"))
    }

    @Test
    fun warnLibraryModule_false_silences_the_lint_and_jvm_warnings() {
        val fixture = materialize("silenced")
        fixture.projectDir.resolve("bugsee.properties").writeText("plugin.warnLibraryModule=false\n")

        val output = runTasks(fixture.projectDir, "tasks").output

        assertFalse(output, output.contains("has no Android application or library plugin"))
    }

    @Test
    fun warnLibraryModule_false_silences_the_fused_library_warning() {
        assumeFusedLibraryAvailable()
        val fixture = materialize("silenced-fused")
        fixture.projectDir.resolve("bugsee.properties").writeText("plugin.warnLibraryModule=false\n")

        val output = runTasks(fixture.projectDir, "tasks", "-PbugseeFixtureFused=true").output

        assertFalse(output, output.contains("is a library module."))
    }

    @Test
    fun bugsee_sdk_group_fused_module_is_exempt() {
        assumeFusedLibraryAvailable()
        val fixture = materialize("sdk-group")

        val output = runTasks(
            fixture.projectDir, "tasks", "-PbugseeFixtureFused=true", "-PbugseeFixtureGroup=com.bugsee.sdk",
        ).output

        assertEquals(output, 0, count(output, fusedWarning))
    }

    @Test
    fun module_types_are_configuration_cache_compatible() {
        val fixture = materialize("cc")

        val first = runTasks(fixture.projectDir, "tasks", "--configuration-cache").output
        val second = runTasks(fixture.projectDir, "tasks", "--configuration-cache").output

        assertTrue(first, first.contains("Configuration cache entry stored"))
        assertTrue(second, second.contains("Reusing configuration cache"))
        assertFalse(first, Regex("\\d+ problems? (was|were) found").containsMatchIn(first))
    }

    /** AGP's fused library plugin is only usable from AGP 8.13; the default toolchain (8.6.0) skips these. */
    private fun assumeFusedLibraryAvailable() {
        val parts = FixtureProject.agpVersion?.split('.')?.map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        assumeTrue(
            "needs AGP >= 8.13 (-Pbugsee.it.agpVersion)",
            parts != null && (parts[0] > 8 || (parts[0] == 8 && parts.getOrElse(1) { 0 } >= 13)),
        )
    }

    /** Materializes the fixture; the fused library's `minSdk` DSL changed shape in AGP 9. */
    private fun materialize(name: String): FixtureProject {
        val fixture = FixtureProject.materialize("module-types", temp.newFolder(name))
        if ((FixtureProject.agpVersion?.substringBefore('.')?.toIntOrNull() ?: 0) >= 9) {
            val script = fixture.projectDir.resolve("fused/build.gradle.kts")
            script.writeText(script.readText().replace("minSdk = 24", "minSdk { version = release(24) }"))
        }
        return fixture
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
