package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import com.bugsee.android.gradle.integration.harness.InstrumentedBytecodeIndex
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/**
 * Tier-matrix TestKit test: applies the plugin to a real Android fixture
 * project at every [StartupTier], runs `:app:assembleDebug`, inspects the
 * post-transform `.class` files, and asserts the dispatcher calls match
 * the tier's documented contract.
 *
 * **Catches today's bug class.** A `private val resolvedTier by lazy { … }`
 * on the factory makes the build fail with "Could not isolate parameters
 * AsmClassesTransform$Parameters_Decorated > Could not serialize value of
 * type AppStartupTracingClassVisitorFactory" — surfaced by the TestKit
 * build, not by any in-memory ASM unit test.
 *
 * Assertion strategy follows the plan's pair-symmetry + presence +
 * monotonicity recommendation:
 *  - `count(*Start) == count(*End)` (each START/END pair balanced).
 *  - Tier-appropriate presence in expected classes.
 *  - Tier-appropriate absence in denylist classes
 *    (`com.bugsee.fake.NotMyClass`) and in
 *    `androidx.startup.InitializationProvider` (excluded by exact-FQN
 *    short-circuit in [AppStartupTracingClassVisitorFactory.classifyKinds]).
 *
 * Specific dispatcher-call counts are intentionally NOT pinned — that
 * couples the test to the fixture's exact body and creates churn whenever
 * the Kotlin compiler emits slightly different bytecode. Range/presence
 * assertions are the right granularity for "the tier contract is
 * upheld."
 */
@RunWith(Parameterized::class)
class AppStartupTracingTierMatrixTest(private val tier: String) {

    @get:Rule
    val temp = TemporaryFolder()

    companion object {
        @JvmStatic
        @Parameters(name = "tier={0}")
        fun tiers(): Iterable<String> = listOf("OFF", "MINIMAL", "STANDARD", "DETAILED", "FULL")

        // Class internal names (slash-form) for the fixture types under test.
        private const val SAMPLE_APP = "com/example/fixture/SampleApp"
        private const val SAMPLE_PROVIDER = "com/example/fixture/SampleProvider"
        private const val SAMPLE_INITIALIZER = "com/example/fixture/SampleInitializer"
        private const val TRACED_HELPER = "com/example/fixture/TracedHelper"
        private const val NOT_MY_CLASS = "com/bugsee/fake/NotMyClass"
        private const val INIT_PROVIDER = "androidx/startup/InitializationProvider"

        // Issue 2: per-kind dispatcher entry-point pairs. The plugin emits
        // a kind-specific INVOKESTATIC target so the SDK side folds each
        // wrap into a distinct `app.startup.<kind>` operation name.
        // Annotation-pickup uses its own pair regardless of class kind.
        private val APPLICATION_PAIR = "onApplicationStart" to "onApplicationEnd"
        private val PROVIDER_PAIR = "onProviderStart" to "onProviderEnd"
        private val ANNOTATED_PAIR = "onAnnotatedStart" to "onAnnotatedEnd"
        private val METHOD_PAIR = "onMethodStart" to "onMethodEnd"
    }

    @Test
    fun assembleDebugAtTier_producesExpectedDispatcherCallShape() {
        val fixture = FixtureProject.materialize("app-startup-tracing", temp.root)
        val result = fixture.build(tier = tier)

        // Sanity: the AGP assemble pipeline ran. Without :app:assembleDebug
        // there's no transformed bytecode to inspect, and any "0 dispatcher
        // calls" assertion would trivially pass.
        val assembleTask = result.task(":app:assembleDebug")
        assertNotNull("expected :app:assembleDebug to be present in build output", assembleTask)
        assertTrue(
            "expected :app:assembleDebug to succeed; outcome=${assembleTask?.outcome}",
            assembleTask?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE)
        )

        val idx = fixture.indexBytecode()

        // ── Pair symmetry — every START has a matching END at every tier. ─
        assertPairs(idx)

        // ── Per-tier presence/absence. ─────────────────────────────────────
        when (tier) {
            "OFF" -> assertOffTier(idx)
            "MINIMAL" -> assertMinimalTier(idx)
            "STANDARD" -> assertStandardTier(idx)
            "DETAILED" -> assertDetailedTier(idx)
            "FULL" -> assertFullTier(idx)
            else -> error("unknown tier $tier")
        }

        // ── Always: denylist + InitializationProvider exclusion. ───────────
        assertDenylistRespected(idx)
    }

    // ── Tier-specific assertion methods ──────────────────────────────────

    private fun assertOffTier(idx: InstrumentedBytecodeIndex.Index) {
        // OFF = no dispatcher calls anywhere in the post-transform output.
        assertEquals(
            "OFF tier must produce zero dispatcher INVOKESTATIC calls, but found ${idx.totalDispatcherCalls()}",
            0, idx.totalDispatcherCalls()
        )
    }

    private fun assertMinimalTier(idx: InstrumentedBytecodeIndex.Index) {
        // Method wraps in each kind-candidate; no calls/loops. Per
        // issue 2, each kind routes to its specific dispatcher pair.
        assertHasMethodWrap(idx, SAMPLE_APP, "onCreate", APPLICATION_PAIR)
        assertHasMethodWrap(idx, SAMPLE_APP, "attachBaseContext", APPLICATION_PAIR)
        assertHasMethodWrap(idx, SAMPLE_PROVIDER, "attachInfo", PROVIDER_PAIR)
        assertHasMethodWrap(idx, SAMPLE_PROVIDER, "onCreate", PROVIDER_PAIR)
        // Initializer falls back to the generic METHOD pair.
        assertHasMethodWrap(idx, SAMPLE_INITIALIZER, "create", METHOD_PAIR)
        assertNoCallWraps(idx, SAMPLE_APP, "onCreate")
        assertNoLoopWraps(idx, SAMPLE_APP, "onCreate")
        // TracedHelper.tracedWork is not picked up below FULL.
        assertNoDispatcherCalls(idx, TRACED_HELPER, "tracedWork")
    }

    private fun assertStandardTier(idx: InstrumentedBytecodeIndex.Index) {
        assertHasMethodWrap(idx, SAMPLE_APP, "onCreate", APPLICATION_PAIR)
        assertHasMethodWrap(idx, SAMPLE_APP, "attachBaseContext", APPLICATION_PAIR)
        assertHasMethodWrap(idx, SAMPLE_PROVIDER, "attachInfo", PROVIDER_PAIR)
        assertHasMethodWrap(idx, SAMPLE_INITIALIZER, "create", METHOD_PAIR)
        // STANDARD adds per-call wraps inside instrumented methods.
        assertHasCallWraps(idx, SAMPLE_APP, "onCreate")
        // No loop wraps at STANDARD.
        assertNoLoopWraps(idx, SAMPLE_APP, "onCreate")
        // Annotation pickup still off.
        assertNoDispatcherCalls(idx, TRACED_HELPER, "tracedWork")
    }

    private fun assertDetailedTier(idx: InstrumentedBytecodeIndex.Index) {
        assertHasMethodWrap(idx, SAMPLE_APP, "onCreate", APPLICATION_PAIR)
        assertHasMethodWrap(idx, SAMPLE_APP, "attachBaseContext", APPLICATION_PAIR)
        assertHasMethodWrap(idx, SAMPLE_PROVIDER, "attachInfo", PROVIDER_PAIR)
        assertHasMethodWrap(idx, SAMPLE_INITIALIZER, "create", METHOD_PAIR)
        assertHasCallWraps(idx, SAMPLE_APP, "onCreate")
        assertHasLoopWraps(idx, SAMPLE_APP, "onCreate")
        assertNoDispatcherCalls(idx, TRACED_HELPER, "tracedWork")
    }

    private fun assertFullTier(idx: InstrumentedBytecodeIndex.Index) {
        assertHasMethodWrap(idx, SAMPLE_APP, "onCreate", APPLICATION_PAIR)
        assertHasMethodWrap(idx, SAMPLE_APP, "attachBaseContext", APPLICATION_PAIR)
        assertHasMethodWrap(idx, SAMPLE_PROVIDER, "attachInfo", PROVIDER_PAIR)
        assertHasMethodWrap(idx, SAMPLE_INITIALIZER, "create", METHOD_PAIR)
        assertHasCallWraps(idx, SAMPLE_APP, "onCreate")
        assertHasLoopWraps(idx, SAMPLE_APP, "onCreate")
        // Annotation pickup: TracedHelper.tracedWork carries @BugseeTrace
        // → routes through the ANNOTATED pair, NOT the generic METHOD
        // pair (folds to `app.startup.annotated` on the SDK side).
        assertHasMethodWrap(idx, TRACED_HELPER, "tracedWork", ANNOTATED_PAIR)
        // Documented FULL-tier contract: annotated methods receive ONLY a
        // method-level wrap — NO automatic call/loop wraps inside.
        assertNoCallWraps(idx, TRACED_HELPER, "tracedWork")
        assertNoLoopWraps(idx, TRACED_HELPER, "tracedWork")
        // TracedHelper.untracedWork is not annotated, not in a kind class →
        // untouched even at FULL.
        assertNoDispatcherCalls(idx, TRACED_HELPER, "untracedWork")
    }

    // ── Cross-tier invariants ────────────────────────────────────────────

    private fun assertPairs(idx: InstrumentedBytecodeIndex.Index) {
        // Each wrap injects: onXxxStart at entry, onXxxEnd at the normal
        // exit, AND onXxxEnd in the catch-any cleanup handler. So END
        // count is typically 2x START count per wrapped method, not
        // equal. The invariant that actually holds is END >= START
        // (never can a normal exit run without entry having happened).
        // Per issue 2, kind-routing fans method-level wraps out across
        // four pairs: generic METHOD + APPLICATION + PROVIDER + ANNOTATED.
        // Call and loop wraps stay on the generic pair.
        val byTarget = idx.countByTargetGlobal()
        for ((start, end) in listOf(
            METHOD_PAIR,
            APPLICATION_PAIR,
            PROVIDER_PAIR,
            ANNOTATED_PAIR,
            "onCallStart" to "onCallEnd",
            "onLoopStart" to "onLoopEnd",
        )) {
            val starts = byTarget[start] ?: 0
            val ends = byTarget[end] ?: 0
            assertTrue(
                "$end ($ends) must be >= $start ($starts): $byTarget",
                ends >= starts,
            )
        }
    }

    private fun assertDenylistRespected(idx: InstrumentedBytecodeIndex.Index) {
        // Package denylist short-circuits anything in `com.bugsee.*` (even
        // if it's an Application subclass).
        assertEquals(
            "denylist breach: $NOT_MY_CLASS has dispatcher calls at tier=$tier — " +
                    "${idx.dispatcherCalls[NOT_MY_CLASS]}",
            0,
            (idx.dispatcherCalls[NOT_MY_CLASS] ?: emptyList()).size
        )
        // AndroidX InitializationProvider is excluded by exact-FQN
        // short-circuit so the StartupSpanFolder waterfall stays clean
        // (otherwise the provider's onCreate would double-count as
        // CONTENT_PROVIDER kind on top of each user Initializer).
        assertEquals(
            "androidx.startup.InitializationProvider should be untouched at tier=$tier",
            0,
            (idx.dispatcherCalls[INIT_PROVIDER] ?: emptyList()).size
        )
    }

    // ── Method-level helpers ─────────────────────────────────────────────

    private fun assertHasMethodWrap(
        idx: InstrumentedBytecodeIndex.Index,
        classInternal: String,
        methodName: String,
        dispatchPair: Pair<String, String> = METHOD_PAIR,
    ) {
        val (startName, endName) = dispatchPair
        val sites = idx.sitesIn(classInternal, methodName)
        val starts = sites.count { it.targetMethodName == startName }
        val ends = sites.count { it.targetMethodName == endName }
        assertTrue(
            "expected $startName in $classInternal#$methodName at tier=$tier, got=$sites",
            starts >= 1
        )
        assertTrue(
            "expected $endName in $classInternal#$methodName at tier=$tier, got=$sites",
            ends >= 1
        )
        // Negative: no FOREIGN method-level wrap dispatcher should fire
        // on this method — guards against a kind-routing flip silently
        // emitting both the right pair AND a wrong one.
        val foreignNames = setOf(
            METHOD_PAIR.first, METHOD_PAIR.second,
            APPLICATION_PAIR.first, APPLICATION_PAIR.second,
            PROVIDER_PAIR.first, PROVIDER_PAIR.second,
            ANNOTATED_PAIR.first, ANNOTATED_PAIR.second,
        ) - setOf(startName, endName)
        val foreignSites = sites.filter { it.targetMethodName in foreignNames }
        assertEquals(
            "$classInternal#$methodName at tier=$tier must route through " +
                "$startName/$endName only — found foreign wrap calls: $foreignSites",
            emptyList<InstrumentedBytecodeIndex.CallSite>(),
            foreignSites,
        )
        // Sanity: site id is non-empty and looks like FQN#method.
        val firstStart = sites.first { it.targetMethodName == startName }
        assertNotNull("missing site_id LDC for method wrap", firstStart.siteIdConstant)
        assertTrue(
            "site_id should mention the owner class: ${firstStart.siteIdConstant}",
            firstStart.siteIdConstant!!.contains(classInternal.replace('/', '.'))
        )
    }

    private fun assertHasCallWraps(
        idx: InstrumentedBytecodeIndex.Index,
        classInternal: String,
        methodName: String,
    ) {
        val sites = idx.sitesIn(classInternal, methodName)
        val starts = sites.count { it.targetMethodName == "onCallStart" }
        val ends = sites.count { it.targetMethodName == "onCallEnd" }
        assertTrue(
            "expected at least one onCallStart in $classInternal#$methodName at tier=$tier, got=$sites",
            starts >= 1
        )
        // END >= START (catch-any cleanup site emits an extra END per wrap).
        assertTrue(
            "onCallEnd ($ends) must be >= onCallStart ($starts) in $classInternal#$methodName at tier=$tier: $sites",
            ends >= starts
        )
    }

    private fun assertHasLoopWraps(
        idx: InstrumentedBytecodeIndex.Index,
        classInternal: String,
        methodName: String,
    ) {
        val sites = idx.sitesIn(classInternal, methodName)
        val starts = sites.count { it.targetMethodName == "onLoopStart" }
        val ends = sites.count { it.targetMethodName == "onLoopEnd" }
        assertTrue(
            "expected at least one onLoopStart in $classInternal#$methodName at tier=$tier, got=$sites",
            starts >= 1
        )
        assertTrue(
            "onLoopEnd ($ends) must be >= onLoopStart ($starts) in $classInternal#$methodName at tier=$tier: $sites",
            ends >= starts
        )
    }

    private fun assertNoCallWraps(
        idx: InstrumentedBytecodeIndex.Index,
        classInternal: String,
        methodName: String,
    ) {
        val sites = idx.sitesIn(classInternal, methodName)
        val calls = sites.count { it.targetMethodName == "onCallStart" || it.targetMethodName == "onCallEnd" }
        assertEquals(
            "expected no call wraps in $classInternal#$methodName at tier=$tier, got=$sites",
            0, calls
        )
    }

    private fun assertNoLoopWraps(
        idx: InstrumentedBytecodeIndex.Index,
        classInternal: String,
        methodName: String,
    ) {
        val sites = idx.sitesIn(classInternal, methodName)
        val loops = sites.count { it.targetMethodName == "onLoopStart" || it.targetMethodName == "onLoopEnd" }
        assertEquals(
            "expected no loop wraps in $classInternal#$methodName at tier=$tier, got=$sites",
            0, loops
        )
    }

    private fun assertNoDispatcherCalls(
        idx: InstrumentedBytecodeIndex.Index,
        classInternal: String,
        methodName: String,
    ) {
        val sites = idx.sitesIn(classInternal, methodName)
        assertEquals(
            "expected zero dispatcher calls in $classInternal#$methodName at tier=$tier, got=$sites",
            0, sites.size
        )
    }
}
