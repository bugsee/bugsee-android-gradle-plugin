package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A Java-only Android app: no Kotlin in the module and no Kotlin Gradle plugin on the build's plugin
 * classpath. The Bugsee plugin is also a Kotlin compiler plugin, so its entry class implements
 * `KotlinCompilerPluginSupportPlugin`, and AGP does not bring the Kotlin Gradle plugin along. Applying
 * it then fails with a raw `NoClassDefFoundError`; the README documents the one-line workaround.
 *
 * This pins the documented limitation. If it is ever lifted, this test fails: update the README
 * section "Java-only apps" together with it.
 */
class BugseeJavaOnlyAppTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val kotlinPluginLine = "org.jetbrains.kotlin.android"

    @Test
    fun without_the_kotlin_plugin_on_the_classpath_apply_fails_naming_the_missing_class() {
        val fixture = FixtureProject.materialize("plugin-order-reversed", temp.newFolder("java-only"))
        val root = fixture.projectDir.resolve("build.gradle.kts")
        val original = root.readText()
        root.writeText(original.lines().filterNot { it.contains(kotlinPluginLine) }.joinToString("\n"))
        assertTrue("fixture must drop the Kotlin plugin line", root.readText() != original)

        val result = fixture.runTasks(listOf("help"))

        assertTrue(result.output, result.output.contains("BUILD FAILED"))
        assertTrue(
            result.output,
            result.output.contains("NoClassDefFoundError: org/jetbrains/kotlin/gradle/plugin/KotlinCompilerPluginSupportPlugin"),
        )
    }

    @Test
    fun declaring_the_kotlin_plugin_with_apply_false_is_enough_for_a_java_only_app() {
        // The unmodified fixture is exactly that: the Kotlin plugin is declared `apply false` in the root
        // and never applied in :app (a Java-only module).
        val fixture = FixtureProject.materialize("plugin-order-reversed", temp.newFolder("java-only-ok"))
        assertTrue(fixture.projectDir.resolve("build.gradle.kts").readText().contains(kotlinPluginLine))

        val result = fixture.runTasks(listOf("help"))

        assertEquals(result.output, TaskOutcome.SUCCESS, result.task(":help")?.outcome)
    }
}
