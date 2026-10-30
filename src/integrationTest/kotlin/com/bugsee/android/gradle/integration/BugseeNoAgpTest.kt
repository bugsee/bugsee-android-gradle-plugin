package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Bugsee plugin applied to a module in a build with NO Android Gradle Plugin on the plugin
 * classpath (#161). Gradle decorates the plugin entry class by loading every type in its signature, so
 * an AGP type there failed with a raw `NoClassDefFoundError` before any Bugsee code ran. The entry
 * class is now AGP-free: it warns once and stands down, and the build succeeds.
 */
class BugseeNoAgpTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val warning = "Bugsee: the Android Gradle Plugin is not on the plugin classpath of project :"

    @Test
    fun applying_the_plugin_without_agp_warns_once_and_the_build_succeeds() {
        val fixture = FixtureProject.materialize("no-agp", temp.newFolder("no-agp"), toolchainOverrides = false)

        val result = fixture.runTasks(listOf("build"))

        assertEquals(result.output, org.gradle.testkit.runner.TaskOutcome.SUCCESS, result.task(":build")?.outcome)
        assertEquals(
            "expected exactly one no-AGP warning. Output:\n${result.output}",
            1,
            Regex(Regex.escape(warning)).findAll(result.output).count(),
        )
        assertFalse(result.output, result.output.contains("NoClassDefFoundError"))
        assertFalse(result.output, result.output.contains("Could not generate a decorated class"))
    }

    @Test
    fun no_bugsee_tasks_extension_or_compiler_plugin_in_a_module_without_agp() {
        val fixture = FixtureProject.materialize("no-agp", temp.newFolder("no-agp-tasks"), toolchainOverrides = false)

        val tasks = fixture.runTasks(listOf("tasks", "--all")).output
        val deps = fixture.runTasks(listOf("dependencies")).output

        assertTrue(tasks, tasks.lines().none { Regex("^[a-z]\\S*[Bb]ugsee\\S* ").containsMatchIn(it) })
        assertFalse("no Bugsee SDK may be auto-added:\n$deps", deps.contains("com.bugsee:"))
        assertFalse(deps, deps.contains("bugsee-compose-compiler-plugin"))
    }

    @Test
    fun the_no_agp_module_is_configuration_cache_compatible() {
        val fixture = FixtureProject.materialize("no-agp", temp.newFolder("no-agp-cc"), toolchainOverrides = false)

        val first = fixture.runTasks(listOf("compileKotlin"), "--configuration-cache").output
        val second = fixture.runTasks(listOf("compileKotlin"), "--configuration-cache").output

        assertTrue(first, first.contains("Configuration cache entry stored"))
        assertTrue(second, second.contains("Reusing configuration cache"))
        assertFalse(first, Regex("\\d+ problems? (was|were) found").containsMatchIn(first))
    }

    /**
     * Bugsee declared in the root with `apply false` (so loaded in the root's classloader scope) and AGP
     * only in :app: AGP is not visible to Bugsee. That must not silently build an app with nothing
     * wired; it fails with a message.
     */
    @Test
    fun agp_applied_only_in_a_child_scope_fails_with_a_message_not_a_silent_no_op() {
        assumeTrue(
            "pins AGP 8.6.0 in the fixture; default toolchain only",
            FixtureProject.gradleVersion == null && FixtureProject.agpVersion == null &&
                FixtureProject.kotlinVersion == null,
        )
        val fixture = FixtureProject.materialize("build-type-gating", temp.newFolder("child-scope"))
        val root = fixture.projectDir.resolve("build.gradle.kts")
        root.writeText(
            root.readText()
                .replace("    id(\"com.android.application\") version \"8.6.0\" apply false\n", "")
                // Kotlin's own Android plugin cannot see AGP from the root scope either; the Kotlin
                // JVM plugin supplies the Kotlin Gradle plugin API Bugsee's entry class needs.
                .replace("id(\"org.jetbrains.kotlin.android\")", "id(\"org.jetbrains.kotlin.jvm\")"),
        )
        val app = fixture.projectDir.resolve("app/build.gradle.kts")
        app.writeText(
            app.readText()
                .replace("id(\"com.android.application\")", "id(\"com.android.application\") version \"8.6.0\"")
                .replace("    id(\"org.jetbrains.kotlin.android\")\n", "")
                .replace(Regex("(?s)\nkotlin \\{.*?\n\\}\n"), "\n"),
        )

        val result = fixture.runTasks(listOf(":app:tasks"))

        assertEquals(result.output, null, result.task(":app:tasks")?.outcome)
        assertTrue(result.output, result.output.contains("'com.android.application' is applied in project :app, but the Android Gradle Plugin is not visible"))
        assertFalse(result.output, result.output.contains("NoClassDefFoundError"))
    }
}
