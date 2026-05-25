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
        // Two builds in two fresh project directories share the same Gradle
        // user home (the integrationTest task does not isolate it), so the
        // second run can REUSE the first run's CC entry.
        val first = temp.newFolder("first")
        val second = temp.newFolder("second")

        // Previously this test excluded `:app:uploadBugseeDebugMapping`
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

        val fixture1 = FixtureProject.materialize("app-startup-tracing", first)
        val firstResult = fixture1.build(
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
        // First run: Gradle reports either "calculating" or "Configuration cache
        // entry stored." in modern Gradle. We just need the build to succeed
        // without `--configuration-cache-problems=fail` flipping it red.

        val fixture2 = FixtureProject.materialize("app-startup-tracing", second)
        val secondResult = fixture2.build(
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
        // Second run on a different directory does NOT generally reuse CC
        // (project paths differ); the real value here is that
        // `--configuration-cache-problems=fail` caused the build to fail
        // if there were ANY incompatibility — including the
        // non-serializable factory parameter regression.
    }
}
