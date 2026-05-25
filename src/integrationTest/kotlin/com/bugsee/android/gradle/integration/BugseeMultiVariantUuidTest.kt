package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end multi-variant BUILD_UUID discrimination.
 *
 * The fixture's `app/build.gradle.kts` exposes a property-gated
 * flavor matrix (`free`/`paid`) — see the
 * `bugseeFixtureMultiFlavor` block. With flavors enabled the fixture
 * builds out four variants: `freeDebug`, `freeRelease`, `paidDebug`,
 * `paidRelease`. We exercise the two debug variants here (release
 * variants would need minify/signing setup that's orthogonal to
 * BUILD_UUID determinism).
 *
 * The unit-level `BugseeManifestTaskUuidDeterminismTest` already
 * pins that the task produces different UUIDs for different `variantName`
 * inputs. This test verifies the AGP wiring actually wires
 * `variantName` correctly through to the task input — a regression
 * that pinned all variants to the same `variantName` (e.g., via a
 * stale closure capture or a wrong `variant.name` field reference)
 * would only surface here.
 */
class BugseeMultiVariantUuidTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun different_flavors_produce_distinct_BUILD_UUIDs() {
        val sharedDir = temp.newFolder("multi-variant")
        val fixture = FixtureProject.materialize("app-startup-tracing", sharedDir)

        val result = fixture.buildTasks(
            tasks = listOf(
                ":app:assembleFreeDebug",
                ":app:assemblePaidDebug",
            ),
            tier = "STANDARD",
            "-PbugseeFixtureMultiFlavor=true",
        )

        val freeOutcome = result.task(":app:assembleFreeDebug")?.outcome
        val paidOutcome = result.task(":app:assemblePaidDebug")?.outcome
        assertNotNull("expected assembleFreeDebug to run", freeOutcome)
        assertNotNull("expected assemblePaidDebug to run", paidOutcome)
        assertTrue(
            "assembleFreeDebug failed: $freeOutcome",
            freeOutcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE),
        )
        assertTrue(
            "assemblePaidDebug failed: $paidOutcome",
            paidOutcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE),
        )

        val freeManifest = fixture.readMergedManifest("freeDebug")
        val paidManifest = fixture.readMergedManifest("paidDebug")
        assertNotNull("freeDebug merged manifest missing", freeManifest)
        assertNotNull("paidDebug merged manifest missing", paidManifest)

        val freeUuid = fixture.extractBuildUuid(freeManifest!!)
        val paidUuid = fixture.extractBuildUuid(paidManifest!!)
        assertNotNull("freeDebug must produce a BUILD_UUID", freeUuid)
        assertNotNull("paidDebug must produce a BUILD_UUID", paidUuid)

        // The load-bearing assertion: two variants of the same
        // workspace get DIFFERENT BUILD_UUIDs. They ship as
        // separate artifacts and the crash-to-mapping round-trip
        // needs to route crashes from `freeDebug` users to the
        // free-flavored mapping upload, not the paid one.
        assertNotEquals(
            "freeDebug and paidDebug must produce different BUILD_UUIDs (variantName " +
                "must flow through to the task input); got both = '$freeUuid'",
            freeUuid,
            paidUuid,
        )
    }

    @Test
    fun rebuilding_the_same_flavor_produces_a_stable_BUILD_UUID() {
        // Two consecutive assembleFreeDebug invocations on the same
        // workspace must produce identical BUILD_UUIDs. This is the
        // single-variant determinism contract — symmetric with the
        // multi-flavor discrimination test above, the two together
        // pin BOTH directions of the variant-name-flow contract.
        val sharedDir = temp.newFolder("multi-variant-stable")
        val fixture = FixtureProject.materialize("app-startup-tracing", sharedDir)

        val first = fixture.buildTasks(
            tasks = listOf("clean", ":app:assembleFreeDebug"),
            tier = "STANDARD",
            "-PbugseeFixtureMultiFlavor=true",
        )
        assertTrue(
            "first build failed",
            first.task(":app:assembleFreeDebug")?.outcome == TaskOutcome.SUCCESS,
        )
        val firstUuid = fixture.readMergedManifest("freeDebug")?.let {
            fixture.extractBuildUuid(it)
        }
        assertNotNull("first build must produce a BUILD_UUID", firstUuid)

        val second = fixture.buildTasks(
            tasks = listOf("clean", ":app:assembleFreeDebug"),
            tier = "STANDARD",
            "-PbugseeFixtureMultiFlavor=true",
        )
        assertTrue(
            "second build failed",
            second.task(":app:assembleFreeDebug")?.outcome == TaskOutcome.SUCCESS,
        )
        val secondUuid = fixture.readMergedManifest("freeDebug")?.let {
            fixture.extractBuildUuid(it)
        }
        assertNotNull("second build must produce a BUILD_UUID", secondUuid)

        assertEquals(
            "same variant + same workspace must produce the same BUILD_UUID across rebuilds; " +
                "first=$firstUuid second=$secondUuid",
            firstUuid,
            secondUuid,
        )
    }
}
