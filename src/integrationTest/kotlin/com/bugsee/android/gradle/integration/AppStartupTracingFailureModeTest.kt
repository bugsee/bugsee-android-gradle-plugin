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

        // Build output should mention the rejection somewhere. We use a
        // permissive contains() — the exact warning string is the plugin's
        // implementation detail and not contract.
        assertTrue(
            "expected the build output to mention the invalid tier value",
            result.output.contains("BLERG") || result.output.contains("Invalid")
                    || result.output.contains("startupTier")
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
