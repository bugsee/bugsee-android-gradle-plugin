package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.bugsee.android.gradle.BugseeInstrumentationExtension
import com.bugsee.android.gradle.instrumentation.InstrumentationConfigResolver
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Smoke tests for [AppStartupTracingInstrumentation] focused on the
 * tier-aware gating logic in `shouldApply`. Bytecode-transform behavior
 * is verified by ASM fixture tests landing in Phase 5+.
 */
class AppStartupTracingInstrumentationTest {

    private lateinit var project: Project
    private lateinit var extension: BugseeInstrumentationExtension
    private lateinit var resolver: InstrumentationConfigResolver

    @Before
    fun setUp() {
        project = ProjectBuilder.builder().build()
        extension = project.objects.newInstance(BugseeInstrumentationExtension::class.java)
        resolver = InstrumentationConfigResolver(extension, project, null)
    }

    @Test fun `metadata is wired correctly`() {
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        assertEquals("AppStartupTracing", instrumentation.name)
        assertEquals("appStartupTracing", instrumentation.key)
    }

    @Test fun `tier OFF disables instrumentation regardless of dependency`() {
        extension.startupTier.set(StartupTier.OFF)
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        // No bugsee-android dependency present in this fresh ProjectBuilder
        // project; OFF should short-circuit before the dependency probe.
        assertFalse(instrumentation.shouldApply(project))
    }

    @Test fun `tier set but no Bugsee dependency present returns false`() {
        extension.startupTier.set(StartupTier.STANDARD)
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        // The dependency probe fails on a bare ProjectBuilder project, so
        // shouldApply returns false for the dependency reason. Phase 5
        // will add an end-to-end test against a sample app with the SDK.
        assertFalse(instrumentation.shouldApply(project))
    }

    @Test fun `default tier (no DSL set) still requires dependency`() {
        // No tier set → DEFAULT (STANDARD); still needs SDK on classpath.
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        assertFalse(instrumentation.shouldApply(project))
    }

    @Test fun `isTierDriven is true so registrar bypasses the boolean gate`() {
        // Regression test for the Phase 3 review's W1: the registrar must
        // not route this instrumentation through isFeatureEnabled, because
        // its key is not a boolean property and any typo'd Gradle property
        // like bugsee.instrumentation.appStartupTracing=garbage would
        // otherwise emit a misleading "invalid boolean" warning and
        // silently disable the feature.
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        org.junit.Assert.assertTrue(instrumentation.isTierDriven)
    }

    // ── positive-control: dependency present, tier observed ──────────

    @Test fun `tier OFF with bugsee-android dependency present still returns false`() {
        // Positive control: prove the OFF tier gate short-circuits BEFORE
        // the dependency probe. Without this test the existing OFF test
        // is tautological — `shouldApply` could return false purely
        // because no dependency was present. Add a real bugsee-android
        // external dependency to the project so the probe would succeed,
        // and assert OFF still wins.
        addBugseeAndroidDependency(project)
        extension.startupTier.set(StartupTier.OFF)
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        org.junit.Assert.assertFalse(instrumentation.shouldApply(project))
    }

    @Test fun `tier STANDARD with bugsee-android dependency present returns true`() {
        // Companion positive control: proves the dependency probe path
        // actually returns true when a matching dep is present at a
        // non-OFF tier. Combined with the OFF-with-dep test above, this
        // pair distinguishes the OFF gate from the dependency gate.
        addBugseeAndroidDependency(project)
        extension.startupTier.set(StartupTier.STANDARD)
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        org.junit.Assert.assertTrue(instrumentation.shouldApply(project))
    }

    /**
     * Adds a real `com.bugsee:bugsee-android` external dependency to a
     * resolvable configuration on the project so [DependencyDetector]
     * sees it during its `configurations.any { ... }` scan. We never
     * actually resolve the artifacts — the detector only walks declared
     * dependency metadata, not the resolved classpath.
     */
    private fun addBugseeAndroidDependency(project: Project) {
        val config = project.configurations.maybeCreate("bugseeProbe")
        project.dependencies.add(config.name, "com.bugsee:bugsee-android:1.0.0")
    }
}
