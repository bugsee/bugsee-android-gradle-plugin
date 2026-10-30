package com.bugsee.android.gradle

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #50: Gradle fires the `implementation` `withDependencies` action on the FIRST read of the
 * dependency set. When another plugin reads it at apply time, that is before the user's
 * `bugsee { }` block, so the auto-add used defaults. The plugin re-checks the decision once the
 * DSL is final (afterEvaluate) and corrects `implementation` while it is still unresolved.
 */
class AutoAddEarlyResolutionTest {

    private fun project(): Project {
        val project = ProjectBuilder.builder().build()
        project.configurations.create("implementation")
        project.extensions.add("android", Any()) // makes the module an Android module (the auto-add is skipped otherwise)
        project.plugins.apply(BugseePlugin::class.java)
        return project
    }

    private fun Project.bugsee(block: BugseePluginExtension.() -> Unit) =
        extensions.getByType(BugseePluginExtension::class.java).block()

    private fun Project.names(): Set<String> =
        configurations.getByName("implementation").dependencies.map { it.name }.toSet()

    private fun Project.earlyRead() {
        configurations.getByName("implementation").incoming.dependencies.size
    }

    private fun Project.evaluateNow() = (this as ProjectInternal).evaluate()

    @Test
    fun earlyReadThenSdkAutoLoadOptOutIsHonoured() {
        val p = project()
        p.earlyRead()
        assertTrue("bugsee-android" in p.names(), "decided on defaults at the early read")

        p.bugsee { sdkAutoLoad.set(false) }
        p.evaluateNow()

        assertFalse("bugsee-android" in p.names(), "the opt-out must win once the DSL is final")
    }

    @Test
    fun earlyReadThenOptInsAreHonoured() {
        val p = project()
        p.earlyRead()
        p.bugsee {
            feedback.set(true)
            ndk.enabled.set(true)
        }
        p.evaluateNow()

        val names = p.names()
        assertTrue("bugsee-android-feedback" in names)
        assertTrue("bugsee-android-ndk" in names)
        assertTrue("bugsee-android" in names)
    }

    @Test
    fun dependenciesDeclaredAfterTheEarlyReadAreSeen() {
        val p = project()
        p.earlyRead()
        assertFalse("bugsee-android-okhttp" in p.names())
        p.dependencies.add("implementation", "com.squareup.okhttp3:okhttp:4.12.0")
        p.evaluateNow()
        assertTrue("bugsee-android-okhttp" in p.names())
        assertEquals(1, p.configurations.getByName("implementation").dependencies.count { it.name == "bugsee-android" })
    }

    @Test
    fun readAfterTheDslIsFinalDecidesImmediately() {
        val p = project()
        p.bugsee {
            sdkAutoLoad.set(false)
            feedback.set(true)
        }
        p.evaluateNow() // the DSL is final before the first read: no provisional pass at all
        p.earlyRead()
        // sdkAutoLoad=false opts out of ALL auto-add (core and extension modules).
        assertEquals(emptySet<String>(), p.names())
    }

    @Test
    fun earlyReadAndUnchangedDslLeavesDependenciesUntouched() {
        val p = project()
        p.earlyRead()
        val before = p.configurations.getByName("implementation").dependencies.toList()
        p.evaluateNow()
        assertEquals(before, p.configurations.getByName("implementation").dependencies.toList())
    }

    @Test
    fun normalOrderBehavesAsBefore() {
        val p = project()
        p.bugsee {
            sdkAutoLoad.set(false)
            feedback.set(true)
        }
        p.earlyRead()
        // sdkAutoLoad=false opts out of ALL auto-add (core and extension modules).
        assertEquals(emptySet<String>(), p.names())
        p.evaluateNow()
        assertEquals(emptySet<String>(), p.names())
    }

    @Test
    fun resolvedExtendingConfigurationWarnsAndLeavesTheSetIntact() {
        val p = project()
        p.earlyRead()
        // The realistic shape: `implementation` itself is not resolvable; a configuration that
        // extends it (releaseRuntimeClasspath in an app) is resolved early.
        val probe = p.configurations.create("probe") {
            it.extendsFrom(p.configurations.getByName("implementation"))
            it.isCanBeResolved = true
        }
        // No repositories: resolution fails, but the configuration is still marked resolved.
        runCatching { probe.resolve() }
        val before = p.configurations.getByName("implementation").dependencies.toList()

        p.bugsee { sdkAutoLoad.set(false) }
        val warnings = mutableListOf<String>()
        p.plugins.getPlugin(BugseePlugin::class.java).uncorrectableAutoAddHook = { warnings.add(it) }
        p.evaluateNow() // must not throw
        assertTrue(
            warnings.singleOrNull()?.contains("a configuration extending it has already been resolved") == true &&
                warnings.single().contains("sdkAutoLoad true -> false"),
            "the refused correction must be reported with the differing settings, was: $warnings",
        )

        assertEquals(before, p.configurations.getByName("implementation").dependencies.toList())
    }
}
