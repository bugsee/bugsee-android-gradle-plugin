package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.bugsee.android.gradle.StartupTier
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
        assertFalse(instrumentation.shouldApply(project, false, null))
    }

    @Test fun `tier set but no Bugsee dependency present returns false`() {
        extension.startupTier.set(StartupTier.STANDARD)
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        // The dependency probe fails on a bare ProjectBuilder project, so
        // shouldApply returns false for the dependency reason. Phase 5
        // will add an end-to-end test against a sample app with the SDK.
        assertFalse(instrumentation.shouldApply(project, false, null))
    }

    @Test fun `default tier (no DSL set) still requires dependency`() {
        // No tier set → DEFAULT (STANDARD); still needs SDK on classpath.
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        assertFalse(instrumentation.shouldApply(project, false, null))
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
        addBugseeAndroidDependency(project, COMPATIBLE_SDK_VERSION)
        extension.startupTier.set(StartupTier.OFF)
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        org.junit.Assert.assertFalse(instrumentation.shouldApply(project, false, null))
    }

    @Test fun `tier STANDARD with bugsee-android dependency present returns true`() {
        // Companion positive control: proves the dependency probe path
        // actually returns true when a matching dep is present at a
        // non-OFF tier. Combined with the OFF-with-dep test above, this
        // pair distinguishes the OFF gate from the dependency gate.
        addBugseeAndroidDependency(project, COMPATIBLE_SDK_VERSION)
        extension.startupTier.set(StartupTier.STANDARD)
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        org.junit.Assert.assertTrue(instrumentation.shouldApply(project, false, null))
    }

    @Test fun `tier STANDARD with too-old bugsee-android version returns false`() {
        // Version-gate test: a `com.bugsee:bugsee-android` declared at a
        // version that predates `BugseeAppStartupDispatcher` MUST cause
        // `shouldApply` to refuse instrumentation, otherwise the plugin
        // injects INVOKESTATIC calls against a missing class and the
        // host app crashes at launch with NoClassDefFoundError.
        addBugseeAndroidDependency(project, "6.5.0")
        extension.startupTier.set(StartupTier.STANDARD)
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        org.junit.Assert.assertFalse(instrumentation.shouldApply(project, false, null))
    }

    @Test fun `tier STANDARD with unparseable version still returns true`() {
        // Dynamic / range versions (e.g. `7.+`, version catalogs that
        // haven't resolved at config time) parse to null and the gate
        // must be permissive: proceed with instrumentation rather than
        // refuse. Catches a regression that flips the null branch to
        // "fail-closed", which would silently disable APM for anyone
        // using version catalogs.
        addBugseeAndroidDependency(project, "7.+")
        extension.startupTier.set(StartupTier.STANDARD)
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        org.junit.Assert.assertTrue(instrumentation.shouldApply(project, false, null))
    }

    @Test fun `tier STANDARD with one-tick-above-min version returns true`() {
        // Boundary test on the integration side: a version that's
        // strictly newer than MIN_SDK_VERSION_WITH_DISPATCHER (which
        // is 7.0.0-beta11 today) must pass the gate. Catches a
        // mutation that bumps the MIN constant to a stricter value
        // — `COMPATIBLE_SDK_VERSION` (exact-min) and `6.5.0` (clearly
        // below) wouldn't catch a one-tick-too-strict shift, but
        // 7.0.0-beta13 here will.
        addBugseeAndroidDependency(project, "7.0.0-beta13")
        extension.startupTier.set(StartupTier.STANDARD)
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        org.junit.Assert.assertTrue(instrumentation.shouldApply(project, false, null))
    }

    // ── BUG-2 regression: core-SDK auto-load (no declared dependency) ──

    @Test fun `no declared SDK but coreSdkAutoLoad true applies`() {
        // A plugin-only app declares NO `com.bugsee:bugsee-android` (the plugin
        // auto-adds it in a later withDependencies pass). At gating time the
        // dependency is invisible to DependencyDetector, so before the fix this
        // returned false and cold-start tracing was silently dropped. The
        // auto-add floor is always >= MIN_SDK_VERSION_WITH_DISPATCHER, so the
        // version gate is correctly skipped here.
        extension.startupTier.set(StartupTier.STANDARD)
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        org.junit.Assert.assertTrue(instrumentation.shouldApply(project, true, null))
    }

    @Test fun `tier OFF wins even when coreSdkAutoLoad is true`() {
        // coreSdkAutoLoad compensates only for a missing *declared* dependency;
        // it must never override an explicit OFF tier.
        extension.startupTier.set(StartupTier.OFF)
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        org.junit.Assert.assertFalse(instrumentation.shouldApply(project, true, null))
    }

    /**
     * Adds a real `com.bugsee:bugsee-android` external dependency to a
     * resolvable configuration on the project so [DependencyDetector]
     * sees it during its `configurations.any { ... }` scan. We never
     * actually resolve the artifacts — the detector only walks declared
     * dependency metadata, not the resolved classpath.
     */
    private fun addBugseeAndroidDependency(project: Project, version: String) {
        val config = project.configurations.maybeCreate("bugseeProbe")
        project.dependencies.add(config.name, "com.bugsee:bugsee-android:$version")
    }

    private companion object {
        // Any version >= MIN_SDK_VERSION_WITH_DISPATCHER. Pinned to the
        // current SDK release so the positive-control tests test against
        // the same surface the SDK ships today.
        const val COMPATIBLE_SDK_VERSION = "7.0.0-beta11"
    }
}
