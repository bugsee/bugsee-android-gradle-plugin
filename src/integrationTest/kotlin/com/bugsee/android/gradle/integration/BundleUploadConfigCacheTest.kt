package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Configuration-cache regression test for the build-info / dependency-
 * collection upload path (`BundleUploadTask` + `wireDependenciesCollectionInputs`).
 *
 * History: `wireDependenciesCollectionInputs` used to set the task's
 * `declaredScopes` / `fileDependencies` via `project.provider { ... }`
 * lambdas that captured the `Project` to read `project.configurations`
 * at realization. That stores a non-serializable provider in the task
 * state and fails configuration-cache serialization ("cannot serialize
 * object of type 'org.gradle.api.Project'"). The fix computes both
 * eagerly into plain Map/List values.
 *
 * Crucially, the sibling `AppStartupTracingConfigCacheTest` does NOT
 * cover this: that fixture deliberately disables `buildInfo`, so the
 * upload task is never registered and its config block never runs — the
 * violation went uncaught for exactly that reason. This test flips the
 * fixture's `bugseeFixtureBuildInfoCc` toggle ON, which enables
 * build-info for all build types (so `:app:assembleDebug` realizes the
 * APK upload task via its `finalizedBy` wiring, running the
 * dependency-collection config under CC) and runs under
 * `--configuration-cache --configuration-cache-problems=fail`.
 *
 * No app token is configured, so the upload task returns early at
 * execution (it never touches the network) — we only assert that the
 * CONFIGURATION serializes cleanly and the cache is REUSED on a second
 * run (the load-bearing CC contract; a non-serializable value would
 * pass a single store but force a recalculation on reuse).
 */
class BundleUploadConfigCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun assembleDebug_withBuildInfo_isConfigurationCacheCompatibleAndReusable() {
        val sharedDir = temp.newFolder("cc-shared")
        val fixture = FixtureProject.materialize("app-startup-tracing", sharedDir)

        val ccArgs = arrayOf(
            "-PbugseeFixtureBuildInfoCc=true",
            "--configuration-cache",
            "--configuration-cache-problems=fail",
        )

        // First run STORES the CC entry. If the deps-collection wiring
        // captured a Project (the pre-fix bug), the store fails here
        // under `--configuration-cache-problems=fail`.
        val first = fixture.buildTasks(listOf(":app:assembleDebug"), null, *ccArgs)
        val firstAssemble = first.task(":app:assembleDebug")
        assertNotNull(firstAssemble)
        assertTrue(
            "first build failed: outcome=${firstAssemble?.outcome}",
            firstAssemble?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE),
        )
        // The APK upload task (which carries the deps-collection inputs)
        // is finalizedBy assembleDebug, so build-info being ON puts it in
        // the graph — proving the configured-under-CC path was actually
        // exercised, not silently skipped.
        assertNotNull(
            "expected :app:uploadBugseeDebugApk in the graph — the deps-" +
                "collection config wiring under test never ran otherwise",
            first.task(":app:uploadBugseeDebugApk"),
        )

        // Second run in the SAME project dir must REUSE the stored entry.
        // A value that wasn't Gradle-serializable on the first build would
        // re-store (no "Reusing" line) or fail outright.
        val second = fixture.buildTasks(listOf(":app:assembleDebug"), null, *ccArgs)
        val secondAssemble = second.task(":app:assembleDebug")
        assertNotNull(secondAssemble)
        assertTrue(
            "second build failed: outcome=${secondAssemble?.outcome}",
            secondAssemble?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE),
        )
        assertTrue(
            "expected the second build to reuse the configuration cache. " +
                "Second-build output (head):\n" +
                second.output.lines().take(40).joinToString("\n"),
            second.output.contains("Reusing configuration cache."),
        )
    }
}
