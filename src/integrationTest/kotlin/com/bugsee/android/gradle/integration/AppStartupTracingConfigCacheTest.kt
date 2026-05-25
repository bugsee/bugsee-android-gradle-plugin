package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Configuration-cache compatibility regression test. Today's bug
 * (`private val resolvedTier by lazy { ... }` on the visitor factory)
 * exactly fits the class that Gradle's configuration cache catches:
 * "transform parameters cannot be serialized." This test runs
 * `:app:assembleDebug` with
 * `--configuration-cache --configuration-cache-problems=fail` and then
 * runs it AGAIN to verify the cache is actually reused. Catches
 * non-serializable transform state and also subtler regressions where a
 * transform parameter is computed eagerly during apply (which prevents
 * the configuration cache from being reused on second run).
 */
class AppStartupTracingConfigCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun assembleDebug_isConfigurationCacheCompatibleAndReusable() {
        // Two consecutive builds in the SAME project directory. The
        // first stores a configuration cache entry; the second must
        // REUSE it (not just succeed under
        // `--configuration-cache-problems=fail`). Reuse is the
        // load-bearing CC contract — a regression that turns a
        // task-graph component into a non-serializable value would
        // still let the first build pass (Gradle is permissive on
        // store) but force a re-calculation on the second.
        //
        // Previously this test ran each build in a DIFFERENT temp
        // dir, which trivially defeated CC reuse (CC entries are
        // keyed on project path) and reduced the assertion to "no
        // CC problems on a single build" — a weaker check that
        // would have missed many real regressions in serializable-
        // value handling.
        //
        // Earlier this test excluded `:app:uploadBugseeDebugMapping`
        // via `-x` because the mapping upload task read the plugin
        // extension at execution time — a CC violation that would
        // trip `--configuration-cache-problems=fail` even though the
        // narrow regression this test guards against is in the
        // app-startup-tracing visitor-factory serialization. The
        // mapping task has since been refactored to wire all its
        // execution-time inputs at registration (the same pattern
        // BundleUploadTask uses), so the exclusion is no longer
        // needed — and removing it means a future CC regression in
        // the mapping task surfaces immediately on this test.

        val sharedDir = temp.newFolder("cc-shared")
        val fixture = FixtureProject.materialize("app-startup-tracing", sharedDir)

        val firstResult = fixture.build(
            tier = "STANDARD",
            "--configuration-cache",
            "--configuration-cache-problems=fail",
        )
        val task1 = firstResult.task(":app:assembleDebug")
        assertNotNull(task1)
        assertTrue(
            "first build failed: outcome=${task1?.outcome}",
            task1?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE)
        )
        // First run should STORE a CC entry. Modern Gradle logs
        // either "Configuration cache entry stored." or
        // "Calculating task graph as no cached configuration is available...".
        // We don't pin the exact wording (it has drifted across Gradle
        // versions), but we DO pin that the second build hits
        // "Reusing configuration cache" — which is the only signal
        // that load-bearing CC compatibility is intact.

        val secondResult = fixture.build(
            tier = "STANDARD",
            "--configuration-cache",
            "--configuration-cache-problems=fail",
        )
        val task2 = secondResult.task(":app:assembleDebug")
        assertNotNull(task2)
        assertTrue(
            "second build failed: outcome=${task2?.outcome}",
            task2?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE)
        )

        // The CC-reuse signal. Gradle prints this line at the
        // beginning of a build that successfully loaded a stored
        // entry. If ANY value in the task graph was not Gradle-
        // serializable on the first build, the second run would
        // either re-store the entry (no "Reusing" line) or fail
        // outright. Pin the literal phrase — Gradle has been stable
        // on this wording since CC went GA.
        assertTrue(
            "expected the second build to reuse the configuration cache. " +
                "Second-build output (head):\n${secondResult.output.lines().take(40).joinToString("\n")}",
            secondResult.output.contains("Reusing configuration cache."),
        )
    }
}
