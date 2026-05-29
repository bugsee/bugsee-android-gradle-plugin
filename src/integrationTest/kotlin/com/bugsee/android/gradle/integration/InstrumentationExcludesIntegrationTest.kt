package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import com.bugsee.android.gradle.integration.harness.InstrumentedBytecodeIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end proof that `bugsee { instrumentation { excludes.add(...) } }`
 * opts a class OUT of Bugsee bytecode instrumentation — exercising the full
 * DSL → registrar → params → `isInstrumentable` wiring.
 *
 * Uses a WITHIN-RUN contrast in one fixture directory: a baseline build with
 * no excludes (proving the class is normally instrumented at STANDARD), then
 * a second build that excludes it. Asserting the delta avoids depending on
 * absolute cross-run state and makes "nothing was instrumented at all" fail
 * the baseline rather than masquerade as a pass.
 *
 * NOTE: requires the integration-test stub SDK (`com.bugsee:bugsee-android`)
 * to be published at a version >= app-startup's
 * `MIN_SDK_VERSION_WITH_DISPATCHER`; otherwise app-startup tracing's
 * `shouldApply` version gate skips instrumentation and the baseline (which
 * reads app-startup's dispatcher calls) has nothing to observe.
 */
class InstrumentationExcludesIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sampleApp = "com/example/fixture/SampleApp"
    private val sampleInitializer = "com/example/fixture/SampleInitializer"

    private fun InstrumentedBytecodeIndex.Index.callsIn(internalName: String): Int =
        (dispatcherCalls[internalName] ?: emptyList()).size

    @Test
    fun excludeOptsAClassOutOfInstrumentation() {
        val fixture = FixtureProject.materialize("app-startup-tracing", temp.newFolder("excludes"))

        // Baseline (no excludes): the Application + Initializer kind classes
        // are instrumented at STANDARD.
        fixture.buildTasks(listOf(":app:assembleDebug"), "STANDARD")
        val baseline = fixture.indexBytecode()
        assertTrue(
            "baseline: SampleApp must be instrumented at STANDARD",
            baseline.callsIn(sampleApp) > 0,
        )
        assertTrue(
            "baseline: SampleInitializer must be instrumented at STANDARD",
            baseline.callsIn(sampleInitializer) > 0,
        )

        // Same fixture, now excluding SampleApp. The only changed input is the
        // exclude set, so the instrumentation re-runs and drops SampleApp.
        fixture.buildTasks(
            listOf(":app:assembleDebug"),
            "STANDARD",
            "-PbugseeFixtureExcludes=com.example.fixture.SampleApp",
        )
        val excluded = fixture.indexBytecode()
        assertEquals(
            "excluded SampleApp must have NO dispatcher calls, got: " +
                "${excluded.dispatcherCalls[sampleApp]}",
            0,
            excluded.callsIn(sampleApp),
        )
        // Scoped, not a global off-switch: a non-excluded kind class stays in.
        assertTrue(
            "non-excluded SampleInitializer must remain instrumented",
            excluded.callsIn(sampleInitializer) > 0,
        )
    }
}
