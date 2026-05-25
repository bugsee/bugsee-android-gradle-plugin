package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Failure-mode coverage for the plugin's resolution lattice:
 *
 *  - Invalid Gradle property → default tier (STANDARD) is used, with a
 *    warning in the build output.
 *  - DSL set to STANDARD with the typed enum API still works (the same
 *    happy-path the matrix covers — sanity check that the typed DSL
 *    rejects nonsense at compile time, not silently at runtime).
 */
class AppStartupTracingFailureModeTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * Resolver contract: Gradle-property source stays lenient. An invalid
     * value (`BLERG`) must not crash the build. The plugin warns and
     * falls through to the default tier, which is STANDARD.
     */
    @Test
    fun invalidGradleProperty_warnsAndFallsThroughToDefaultTier() {
        val fixture = FixtureProject.materialize("app-startup-tracing", temp.root)
        // Don't set the typed DSL property (`-PbugseeStartupTier=...` is
        // wired to the DSL in app/build.gradle.kts). Instead pass the
        // GRADLE-property source `bugsee.instrumentation.startupTier=BLERG`
        // directly. The plugin's resolver should reject it leniently.
        val result = fixture.build(
            tier = null,
            "-Pbugsee.instrumentation.startupTier=BLERG",
        )

        val task = result.task(":app:assembleDebug")
        assertNotNull(task)
        assertTrue(
            "build must succeed even with an invalid tier value: outcome=${task?.outcome}",
            task?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE)
        )

        // Build output must include the resolver's exact warn shape:
        // "Bugsee: Invalid startupTier 'BLERG' for Gradle property '...'".
        // The earlier permissive `contains("BLERG") || contains("Invalid")
        // || contains("startupTier")` was too loose: the string
        // `"startupTier"` appears in normal plugin output (Gradle's task
        // configuration log mentions the typed Property name during
        // configuration), so the OR-chain trivially passed on virtually
        // any build, including builds where the resolver had silently
        // accepted the invalid value or never logged anything. Tighten to
        // the joint contract: BOTH the warn prefix AND the rejected token
        // must appear, AND the warn line as a whole must mention
        // "startupTier" (not just the same letters elsewhere in build
        // log). Matches `InstrumentationConfigResolver.kt:82`.
        val warnLine = result.output.lineSequence().firstOrNull { line ->
            line.contains("Bugsee: Invalid startupTier") && line.contains("'BLERG'")
        }
        assertNotNull(
            "expected a warn matching `Bugsee: Invalid startupTier 'BLERG'`; full output:\n${result.output}",
            warnLine,
        )

        // The transform must still have produced bytecode at the default
        // tier (STANDARD): method wraps + call wraps inside SampleApp.
        // Per issue 2, method-level wraps fan out across kind-specific
        // dispatcher pairs — sum across all wrap variants here.
        val idx = fixture.indexBytecode()
        val byTarget = idx.countByTargetGlobal()
        val totalStarts = (byTarget["onMethodStart"] ?: 0) +
                (byTarget["onApplicationStart"] ?: 0) +
                (byTarget["onProviderStart"] ?: 0) +
                (byTarget["onAnnotatedStart"] ?: 0) +
                (byTarget["onCallStart"] ?: 0) +
                (byTarget["onLoopStart"] ?: 0)
        assertTrue(
            "expected DEFAULT-tier bytecode to be produced after lenient fallback, " +
                    "got=$byTarget",
            totalStarts > 0
        )
        // STANDARD does NOT inject loop wraps — sanity that we fell to
        // STANDARD specifically, not silently up-tiered.
        assertEquals(
            "default tier must NOT emit loop wraps: $byTarget",
            0,
            byTarget["onLoopStart"] ?: 0
        )
    }
}
