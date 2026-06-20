package com.bugsee.android.gradle.instrumentation

import com.bugsee.android.gradle.instrumentation.http_engine.HttpEngineInstrumentation
import com.bugsee.android.gradle.instrumentation.log.LogInstrumentation
import com.bugsee.android.gradle.instrumentation.main_thread_misuse.MainThreadMisuseInstrumentation
import com.bugsee.android.gradle.instrumentation.okhttp.OkHttpInstrumentation
import com.bugsee.android.gradle.instrumentation.operation_dispatch.OperationDispatchInstrumentation
import com.bugsee.android.gradle.instrumentation.thread.ThreadInstrumentation
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BUG-2 regression coverage for `Instrumentation.shouldApply(project, coreSdkAutoLoad)`.
 *
 * A plugin-only app declares NO `com.bugsee:*` dependency — the plugin auto-adds
 * the core SDK in a later `withDependencies` pass. At instrumentation-gating time
 * that dependency is invisible to [DependencyDetector], so before the fix every
 * core-gated instrumentation was skipped ("dependency not found") and the app got
 * no network/log/thread/main-thread/operation-dispatch capture of its own code.
 *
 * The `coreSdkAutoLoad` signal makes core-gated instrumentations apply when the
 * core SDK is going to be auto-added — while extension-gated instrumentations
 * (OkHttp) must still require their own extension AAR.
 */
class InstrumentationCoreSdkAutoLoadTest {

    private lateinit var project: Project

    @Before fun setUp() {
        project = ProjectBuilder.builder().build()
    }

    private fun declare(coordinate: String) {
        val config = project.configurations.maybeCreate("bugseeProbe")
        project.dependencies.add(config.name, coordinate)
    }

    private val coreInstrumentations
        get() = listOf(
            LogInstrumentation(),
            ThreadInstrumentation(),
            MainThreadMisuseInstrumentation(),
            OperationDispatchInstrumentation(),
            HttpEngineInstrumentation(),
        )

    @Test fun `core instrumentations skipped when no dep and no auto-load`() {
        coreInstrumentations.forEach {
            assertFalse(it.name, it.shouldApply(project, false))
        }
    }

    @Test fun `core instrumentations apply when core SDK will be auto-loaded`() {
        // No declared bugsee dependency, but the plugin will auto-add the core.
        coreInstrumentations.forEach {
            assertTrue(it.name, it.shouldApply(project, true))
        }
    }

    @Test fun `core instrumentations apply when core SDK declared explicitly`() {
        declare("com.bugsee:bugsee-android:7.0.0-beta14")
        coreInstrumentations.forEach {
            assertTrue(it.name, it.shouldApply(project, false))
        }
    }

    @Test fun `okhttp instrumentation ignores core auto-load`() {
        // Extension-gated: core auto-load alone must NOT enable OkHttp — it
        // needs the okhttp extension AAR (whose interceptor it injects).
        assertFalse(OkHttpInstrumentation().shouldApply(project, true))
    }

    @Test fun `okhttp instrumentation applies when okhttp extension declared`() {
        declare("com.bugsee:bugsee-android-okhttp:7.0.0-beta14")
        assertTrue(OkHttpInstrumentation().shouldApply(project, false))
    }
}
