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
        extension.startupTier.set("OFF")
        val instrumentation = AppStartupTracingInstrumentation(resolver)
        // No bugsee-android dependency present in this fresh ProjectBuilder
        // project; OFF should short-circuit before the dependency probe.
        assertFalse(instrumentation.shouldApply(project))
    }

    @Test fun `tier set but no Bugsee dependency present returns false`() {
        extension.startupTier.set("STANDARD")
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
}
