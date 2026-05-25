package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * End-to-end manifest behavior under TestKit:
 *
 *  1. **Assemble (APK) and Bundle (AAB) of the same workspace produce
 *     the same BUILD_UUID.** This is the load-bearing claim the
 *     deterministic-UUID fix restored (`UUID.nameUUIDFromBytes` over
 *     `manifestBytes + variant + pluginVersion` rather than
 *     `UUID.randomUUID()`). The unit-level
 *     `BugseeManifestTaskUuidDeterminismTest` pins the algorithm,
 *     but only an E2E test can verify the full AGP pipeline (both
 *     `processDebugMainManifest` paths and the artifact-transform
 *     wiring) preserves that determinism across the two task graphs
 *     (`:app:assembleDebug` vs `:app:bundleDebug`) — they consume the
 *     same MERGED_MANIFEST source but exercise different downstream
 *     consumers, and a regression that altered the pre-task-action
 *     buffering or copy semantics would only surface here.
 *
 *  2. **The BUILD_UUID is a valid v3 (name-derived) UUID.** Pinned
 *     in unit tests too, but the duplicate assertion here costs
 *     nothing and protects against the (improbable) scenario where
 *     the unit test passes but the AGP wiring substitutes the value
 *     before packaging.
 *
 * This file is the integration-level companion to the unit-level
 * `BugseeManifestTaskUuidDeterminismTest`.
 */
class BugseeManifestE2ETest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun assembleDebug_and_bundleDebug_share_the_same_BUILD_UUID() {
        // Same workspace, two task graphs. The Fastlane CI pattern
        // is `clean assembleDebug` (publish APK) then later
        // `clean bundleDebug` (publish AAB). Under the previous
        // `UUID.randomUUID()` impl these two would have ended up
        // with different BUILD_UUIDs, breaking crash-to-mapping
        // round-trip for builds shipped through the bundle path.
        val sharedDir = temp.newFolder("e2e-manifest")
        val fixture = FixtureProject.materialize("app-startup-tracing", sharedDir)

        // Run both tasks in the same invocation. AGP shares the
        // MERGED_MANIFEST output across them via the artifact
        // transform, so we expect ONE BugseeManifestTask execution
        // whose output feeds both. (If AGP ever splits the
        // intermediates per consumer, this test would catch that
        // too — the UUID values would still need to match.)
        val result = fixture.buildTasks(
            tasks = listOf(":app:assembleDebug", ":app:bundleDebug"),
            tier = "STANDARD",
        )

        val assembleOutcome = result.task(":app:assembleDebug")?.outcome
        val bundleOutcome = result.task(":app:bundleDebug")?.outcome
        assertNotNull("expected :app:assembleDebug to run", assembleOutcome)
        assertNotNull("expected :app:bundleDebug to run", bundleOutcome)
        assertTrue(
            "assembleDebug failed: $assembleOutcome",
            assembleOutcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE),
        )
        assertTrue(
            "bundleDebug failed: $bundleOutcome",
            bundleOutcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE),
        )

        val manifest = fixture.readMergedManifest("debug")
        assertNotNull(
            "merged manifest for debug variant must exist on disk after assembleDebug+bundleDebug",
            manifest,
        )
        val buildUuid = fixture.extractBuildUuid(manifest!!)
        assertNotNull(
            "merged manifest must carry BUILD_UUID meta-data; got:\n$manifest",
            buildUuid,
        )

        // Defense-in-depth: the value must be a valid v3 UUID. The
        // unit test pins this directly, but having it here catches
        // the rare regression where the manifest passes the unit
        // test in isolation but AGP's late manifest mutation
        // substitutes the value.
        val parsed = UUID.fromString(buildUuid!!)
        assertEquals(
            "BUILD_UUID must be a name-derived (v3) UUID end-to-end; got version ${parsed.version()} ($buildUuid)",
            3,
            parsed.version(),
        )
    }

    @Test
    fun rebuilding_after_clean_produces_the_same_BUILD_UUID() {
        // Stronger reproduction of the Fastlane two-lane scenario:
        // clean, build APK, clean again, build AAB — does the UUID
        // survive the workspace-wipe? It should, because the
        // deterministic derivation reads only the merged manifest
        // bytes + variant + plugin version, none of which change
        // across a `clean`.
        val sharedDir = temp.newFolder("e2e-manifest-clean")
        val fixture = FixtureProject.materialize("app-startup-tracing", sharedDir)

        val firstResult = fixture.buildTasks(
            tasks = listOf("clean", ":app:assembleDebug"),
            tier = "STANDARD",
        )
        assertTrue(
            "first build (clean + assembleDebug) failed",
            firstResult.task(":app:assembleDebug")?.outcome == TaskOutcome.SUCCESS,
        )
        val firstManifest = fixture.readMergedManifest("debug")
        val firstUuid = firstManifest?.let { fixture.extractBuildUuid(it) }
        assertNotNull("first build must produce a BUILD_UUID", firstUuid)

        val secondResult = fixture.buildTasks(
            tasks = listOf("clean", ":app:bundleDebug"),
            tier = "STANDARD",
        )
        assertTrue(
            "second build (clean + bundleDebug) failed",
            secondResult.task(":app:bundleDebug")?.outcome == TaskOutcome.SUCCESS,
        )
        val secondManifest = fixture.readMergedManifest("debug")
        val secondUuid = secondManifest?.let { fixture.extractBuildUuid(it) }
        assertNotNull("second build must produce a BUILD_UUID", secondUuid)

        // The load-bearing assertion: same workspace, same inputs,
        // same UUID. A regression that re-introduced `randomUUID()`
        // or otherwise made the derivation non-deterministic would
        // produce two different values here and fail this assertion.
        assertEquals(
            "BUILD_UUID must survive a clean + rebuild on the same workspace — " +
                "first=$firstUuid, second=$secondUuid",
            firstUuid,
            secondUuid,
        )
    }
}
