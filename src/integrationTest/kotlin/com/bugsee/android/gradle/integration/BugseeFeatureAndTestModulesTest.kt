package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Bugsee applied to a `com.android.dynamic-feature` and a `com.android.test` module (issue #163).
 * Neither is wired. Neither gets the SDK auto-add unless the Bugsee Compose compiler plugin applies to it
 * (it emits calls into the SDK, so dropping the SDK there would lose the tag/secure wraps or break the
 * compile): a Compose dependency with tag/secure enabled, plus `composeInTests` for a test module.
 * Both get a default-level warning, silenced by `warnLibraryModule=false`. The application module is
 * unchanged: still wired, still auto-added, still instrumented.
 */
class BugseeFeatureAndTestModulesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val featureWarning = "Bugsee: :feature is a dynamic-feature module"
    private val testWarning = "Bugsee: :tst is a test module"

    private fun count(output: String, text: String) = Regex(Regex.escape(text)).findAll(output).count()

    @Test
    fun feature_and_test_modules_warn_exactly_once_and_the_build_succeeds() {
        val fixture = materialize("warn")

        val output = runTasks(fixture, "tasks", "--all").output

        assertEquals("feature warning count. Output:\n$output", 1, count(output, featureWarning))
        assertEquals("test warning count. Output:\n$output", 1, count(output, testWarning))
        assertTrue(output, output.contains("README, \"Library modules\""))
        assertTrue(output, output.contains("must declare it itself as compileOnly"))
        assertTrue(output, output.contains("the SDK cannot be in a feature alone"))
        // The application module is not a library/feature/test module: no warning of any such kind.
        assertEquals(output, 0, count(output, "Bugsee: :app is a"))
    }

    @Test
    fun the_sdk_is_not_auto_added_to_feature_and_test_modules_but_still_to_the_app() {
        val fixture = materialize("deps")

        val output = runTasks(
            fixture, ":app:printImplementationDeps", ":feature:printImplementationDeps", ":tst:printImplementationDeps",
        ).output
        fun declared(module: String) = checkNotNull(Regex("IMPL\\[:$module]: (.*)").find(output)?.groupValues?.get(1)) {
            "no IMPL line for :$module in:\n$output"
        }

        assertTrue("the app keeps the auto-add: $output", declared("app").contains("com.bugsee:bugsee-android:"))
        assertFalse("no SDK in the feature: $output", declared("feature").contains("com.bugsee:"))
        assertFalse("no SDK in the test module: $output", declared("tst").contains("com.bugsee:"))
    }

    @Test
    fun no_bugsee_tasks_in_feature_and_test_modules_and_the_app_keeps_its_tasks() {
        val fixture = materialize("tasks")
        val bugseeTask = Regex("^[a-z]\\S*[Bb]ugsee\\S*(\\s|$)")

        for (module in listOf("feature", "tst")) {
            val tasks = runTasks(fixture, ":$module:tasks", "--all").output.lines().filter { bugseeTask.containsMatchIn(it) }
            assertTrue(":$module must have no Bugsee task: $tasks", tasks.isEmpty())
        }
        val appTasks = runTasks(fixture, ":app:tasks", "--all").output.lines().filter { bugseeTask.containsMatchIn(it) }
        assertTrue(":app must keep its Bugsee tasks: $appTasks", appTasks.any { it.startsWith("createBugseeDebugManifestConfig") })
    }

    @Test
    fun warnLibraryModule_false_silences_both_warnings() {
        val fixture = materialize("silenced")
        fixture.projectDir.resolve("bugsee.properties").writeText("plugin.warnLibraryModule=false\n")

        val output = runTasks(fixture, "tasks").output

        assertEquals(output, 0, count(output, featureWarning))
        assertEquals(output, 0, count(output, testWarning))
    }

    @Test
    fun bugsee_sdk_group_modules_are_exempt_from_the_warnings() {
        val fixture = materialize("sdk-group")

        val output = runTasks(fixture, "tasks", "-PbugseeFixtureGroup=com.bugsee.sdk").output

        assertEquals(output, 0, count(output, featureWarning))
        assertEquals(output, 0, count(output, testWarning))
    }

    @Test
    fun app_with_a_feature_builds_the_feature_resolves_the_sdk_it_declares_and_the_app_stays_wired() {
        val fixture = materialize("build")

        val result = fixture.buildTasks(
            listOf(":app:assembleDebug", ":feature:assembleDebug", ":tst:assembleDebug"),
            null,
            "-PbugseeFixtureDeclareSdk=true",
        )

        assertTrue(result.output, result.output.contains("BUILD SUCCESSFUL"))
        // The application is still wired: manifest UUID injected, and instrumentation still runs.
        val manifest = fixture.readMergedManifest("debug")
        assertNotNull("app merged manifest", manifest)
        assertNotNull("app BUILD_UUID", fixture.extractBuildUuid(manifest!!))
        val startupCalls = fixture.indexBytecode().countByTargetGlobal()
        assertTrue("app startup instrumentation must still run: $startupCalls", startupCalls.values.sum() > 0)
    }

    @Test
    fun a_compose_feature_keeps_the_compose_compiler_plugin_and_the_sdk() {
        val fixture = materialize("compose-feature")

        val deps = composeRun(fixture, ":feature:dependencies", "-PbugseeFixtureCompose=true").output
        val declared = composeRun(fixture, ":feature:printImplementationDeps", "-PbugseeFixtureCompose=true").output

        assertTrue("the Compose compiler plugin must stay on the feature's classpath:\n$deps", deps.contains("bugsee-compose-compiler-plugin"))
        assertTrue("the SDK the Compose wraps call into must stay:\n$declared", declared.contains("com.bugsee:bugsee-android:"))
    }

    @Test
    fun a_compose_test_module_with_composeInTests_keeps_the_compose_compiler_plugin_and_the_sdk() {
        val fixture = materialize("compose-test-on")
        val args = arrayOf("-PbugseeFixtureCompose=true", "-PbugseeFixtureComposeInTests=true")

        val deps = composeRun(fixture, ":tst:dependencies", *args).output
        val declared = composeRun(fixture, ":tst:printImplementationDeps", *args).output

        assertTrue("composeInTests keeps the plugin on the test module:\n$deps", deps.contains("bugsee-compose-compiler-plugin"))
        assertTrue("and the SDK:\n$declared", declared.contains("com.bugsee:bugsee-android:"))
    }

    @Test
    fun a_compose_test_module_without_composeInTests_gets_neither() {
        val fixture = materialize("compose-test-off")

        val deps = composeRun(fixture, ":tst:dependencies", "-PbugseeFixtureCompose=true").output
        val declared = composeRun(fixture, ":tst:printImplementationDeps", "-PbugseeFixtureCompose=true").output

        assertFalse("test code stays uninstrumented by default:\n$deps", deps.contains("bugsee-compose-compiler-plugin"))
        assertFalse("no SDK without Compose instrumentation:\n$declared", declared.contains("com.bugsee:"))
    }

    @Test
    fun a_compose_app_still_gets_the_compose_compiler_plugin() {
        val fixture = materialize("compose-app")

        val deps = composeRun(fixture, ":app:dependencies", "-PbugseeFixtureCompose=true").output

        assertTrue(deps, deps.contains("bugsee-compose-compiler-plugin"))
    }

    @Test
    fun an_early_read_of_implementation_is_corrected_for_a_compose_feature() {
        val fixture = materialize("early-compose")

        val declared = composeRun(
            fixture, ":feature:printImplementationDeps", "-PbugseeFixtureCompose=true", "-PbugseeFixtureEarlyRead=true",
        ).output

        assertTrue("the Compose dependency declared after the early read must still get the SDK:\n$declared", declared.contains("com.bugsee:bugsee-android:"))
    }

    @Test
    fun an_early_read_is_undone_when_compose_instrumentation_is_off_at_dsl_final() {
        val fixture = materialize("early-compose-off")

        val declared = composeRun(
            fixture, ":feature:printImplementationDeps", "-PbugseeFixtureCompose=true",
            "-PbugseeFixtureEarlyRead=true", "-PbugseeFixtureComposeDepEarly=true", "-PbugseeFixtureComposeOff=true",
        ).output

        assertFalse("the provisional add must be taken back:\n$declared", declared.contains("com.bugsee:"))
    }

    @Test
    fun a_compose_feature_with_compose_instrumentation_off_gets_no_sdk() {
        val fixture = materialize("compose-off")

        val declared = composeRun(
            fixture, ":feature:printImplementationDeps", "-PbugseeFixtureCompose=true", "-PbugseeFixtureComposeOff=true",
        ).output

        assertFalse(declared, declared.contains("com.bugsee:"))
    }

    @Test
    fun feature_and_test_modules_are_configuration_cache_compatible() {
        val fixture = materialize("cc")

        // Compiling the feature resolves its classpath, which fires the SDK auto-add decision.
        val args = arrayOf(":feature:compileDebugJavaWithJavac", "-PbugseeFixtureDeclareSdk=true", "--configuration-cache")
        val first = runTasks(fixture, *args).output
        val second = runTasks(fixture, *args).output

        assertTrue(first, first.contains("Configuration cache entry stored"))
        assertTrue(second, second.contains("Reusing configuration cache"))
        assertFalse(first, Regex("\\d+ problems? (was|were) found").containsMatchIn(first))
    }

    private fun composeRun(fixture: FixtureProject, vararg args: String) =
        runTasks(fixture, *args, "-PbugseeComposePluginRepo=${System.getProperty("bugsee.testkit.composePluginRepo")}")

    private fun materialize(name: String): FixtureProject =
        FixtureProject.materialize("feature-test-modules", temp.newFolder(name))

    private fun runTasks(fixture: FixtureProject, vararg args: String): org.gradle.testkit.runner.BuildResult =
        fixture.runTasks(args.toList()).also {
            assertTrue("build failed:\n${it.output}", it.output.contains("BUILD SUCCESSFUL"))
        }
}
