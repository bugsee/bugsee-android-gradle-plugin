package com.bugsee.android.gradle.upload

import com.bugsee.android.gradle.registerBuildTimingListenerOnce
import org.gradle.api.services.BuildServiceParameters
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationDescriptor
import org.gradle.tooling.events.OperationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BuildTimingService] — the live Gradle adapter over
 * the pure [buildTimings] aggregator. The pure path is covered in
 * [BuildTimingsTest]; this file covers the listener-side integration:
 *
 *   - `record(...)` accumulates into `snapshot()`.
 *   - `onFinish` with non-task events is a no-op.
 *   - Multiple concurrent `record(...)` calls all land in the snapshot.
 *
 * `BuildTimingService` is abstract (it extends Gradle's BuildService,
 * which the build runtime normally instantiates via
 * `sharedServices.registerIfAbsent`). The test subclass below provides
 * the only missing concrete — `getParameters()` — with the standard
 * `BuildServiceParameters.None` anonymous implementation.
 */
class BuildTimingServiceTest {

    private class TestService : BuildTimingService() {
        // `BuildServiceParameters.None` is final with no public
        // constructor — Gradle produces the singleton for real
        // services registered via `sharedServices.registerIfAbsent`,
        // but tests instantiate the class directly. The service never
        // calls `getParameters()` from its own code, so throwing here
        // is safe and keeps the test independent of Gradle internals.
        override fun getParameters(): BuildServiceParameters.None =
            throw UnsupportedOperationException("getParameters is not used in unit tests")
    }


    @Test fun `record accumulates into snapshot`() {
        val svc = TestService()
        svc.record(":app:compileReleaseKotlin", 1_000, 5_000)
        svc.record(":app:bundleRelease",        5_000, 5_800)

        val rollup = svc.snapshot()
        assertEquals(4_000L, rollup.managedCodeMs)
        assertEquals(800L,   rollup.packagingMs)
        assertEquals(4_800L, rollup.totalMs)
        assertEquals(2, rollup.topTasks.size)
    }

    @Test fun `snapshot is empty before any records arrive`() {
        val svc = TestService()
        assertEquals(BuildTimings.EMPTY, svc.snapshot())
    }

    @Test fun `onFinish ignores non-task events`() {
        // The listener is registered under `onTaskCompletion`, but
        // Gradle's broader event stream emits other FinishEvent subtypes
        // too (e.g. work-item completion in parallel workers). Guard
        // that non-task events are silently dropped rather than turning
        // into spurious timing rows.
        val svc = TestService()
        svc.onFinish(nonTaskFinishEvent())
        assertEquals(BuildTimings.EMPTY, svc.snapshot())
    }

    @Test fun `concurrent record calls all land in the snapshot`() {
        // The service observes task completions on Gradle's executor
        // threads; snapshot() reads on the upload-task thread. This
        // test pins the concurrent-producer property: no records go
        // missing when multiple threads call `record` in lockstep.
        //
        // Tightened beyond a simple cardinality check: every produced
        // `(path, durationMs)` pair must appear in the snapshot's
        // `topTasks`. A naïve size check could pass if duplicate paths
        // overwrote each other in a buggy implementation; comparing
        // sets pins both identity AND uniqueness.
        val svc = TestService()
        val threads = 8
        val perThread = 50
        val latch = java.util.concurrent.CountDownLatch(threads)
        for (i in 0 until threads) {
            Thread {
                for (j in 0 until perThread) {
                    // Distinct endTime per task so each entry has a
                    // unique non-zero duration — lets us assert the
                    // (path, durationMs) pair survives the round trip.
                    val end = (i.toLong() * 1000 + j.toLong() + 1)
                    svc.record(":t:${i}-$j", 0, end)
                }
                latch.countDown()
            }.start()
        }
        latch.await()

        val rollup = svc.snapshot(topN = threads * perThread)
        assertEquals(threads * perThread, rollup.topTasks.size)

        // Build the expected (path, durationMs) set from the same
        // distinct-endTime arithmetic the producer used above.
        val expected: Set<Pair<String, Long>> = buildSet {
            for (i in 0 until threads) {
                for (j in 0 until perThread) {
                    add(":t:${i}-$j" to (i.toLong() * 1000 + j.toLong() + 1))
                }
            }
        }
        val actual: Set<Pair<String, Long>> =
            rollup.topTasks.map { it.path to it.durationMs }.toSet()
        assertEquals(threads * perThread, actual.size)
        assertEquals(expected, actual)

        // Per-record duration must be preserved exactly — not collapsed,
        // rounded, or replaced with a default. Spot-check a few specific
        // entries by lookup since iteration order in `topTasks` is sorted
        // by duration descending.
        val byPath = rollup.topTasks.associateBy { it.path }
        assertEquals(1L,       byPath[":t:0-0"]!!.durationMs)
        assertEquals(50L,      byPath[":t:0-49"]!!.durationMs)
        assertEquals(7_050L,   byPath[":t:7-49"]!!.durationMs)
    }


    @Test fun `registerBuildTimingListenerOnce only registers on the first call`() {
        // Multi-module project shape: a root project and two children
        // (both would apply the bugsee plugin). They share the same
        // Gradle instance — naïvely calling `listenerRegistry
        // .onTaskCompletion(...)` from BOTH would subscribe the
        // task-completion listener twice and double-record every
        // task's timing. The plugin's `registerBuildTimingListenerOnce`
        // single-flight guard prevents that.
        //
        // We can't directly apply the BugseePlugin class in a unit
        // test (it extends KotlinCompilerPluginSupportPlugin from the
        // `compileOnly` Kotlin Gradle plugin API, which isn't on the
        // unit-test classpath). The helper sits in its own top-level
        // file precisely so referencing it doesn't drag the plugin
        // class onto the test classpath.
        val root = ProjectBuilder.builder().withName("test-root").build()
        val sub1 = ProjectBuilder.builder().withName("sub1").withParent(root).build()
        val sub2 = ProjectBuilder.builder().withName("sub2").withParent(root).build()

        val fakeRegistry = RecordingListenerRegistry()
        val fakeServiceProvider = sub1.provider { TestService() as BuildTimingService }

        // Two `apply()` invocations from different subprojects.
        registerBuildTimingListenerOnce(sub1, fakeRegistry, fakeServiceProvider)
        registerBuildTimingListenerOnce(sub2, fakeRegistry, fakeServiceProvider)

        // Listener must have been registered EXACTLY once. A
        // counter == 2 here would mean the second subproject
        // re-registered and would double-count every task event.
        assertEquals(1, fakeRegistry.onTaskCompletionCallCount)

        // The flag itself must be set on the root project's
        // extra-properties — the persistence mechanism the second
        // call observes. Flag name is duplicated as a string literal
        // here to avoid loading BugseePlugin (see above).
        val flagKey = "bugseeBuildTimingListenerRegistered"
        val rootExtra = root.extensions.extraProperties
        assertTrue("flag must be set after first call", rootExtra.has(flagKey))
        assertEquals(true, rootExtra.get(flagKey))
    }


    /**
     * Recording fake of [org.gradle.build.event.BuildEventsListenerRegistry]
     * for the single-flight guard test. Tracks the number of
     * `onTaskCompletion` calls so the test can assert exactly-once
     * registration semantics.
     */
    private class RecordingListenerRegistry :
        org.gradle.build.event.BuildEventsListenerRegistry {
        var onTaskCompletionCallCount: Int = 0
        override fun onTaskCompletion(
            provider: org.gradle.api.provider.Provider<out org.gradle.tooling.events.OperationCompletionListener>
        ) {
            onTaskCompletionCallCount++
        }
    }


    // ── helpers ────────────────────────────────────────────────────

    // ── Bounded-records (MAX_RECORDS) cap ─────────────────────────

    @Test fun `record stops accumulating past the MAX_RECORDS cap`() {
        // Pin the upper bound the new cap enforces. Pre-fix the
        // queue was unbounded; pathological builds could accumulate
        // megabytes of TaskTiming entries throughout the build
        // before they were GC'd at build end.
        val svc = TestService()
        // Push exactly MAX_RECORDS + 100 records. The first
        // MAX_RECORDS must land; the trailing 100 must be silently
        // dropped.
        val extras = 100
        for (i in 0 until MAX_RECORDS + extras) {
            svc.record(":t:$i", 0L, 1L)
        }
        // `snapshot(topN = ...)` rolls up everything in the queue.
        // Using a topN >= MAX_RECORDS makes the size of topTasks
        // equal the queue size — that's our memoryless probe.
        val rollup = svc.snapshot(topN = MAX_RECORDS + extras)
        assertEquals(
            "queue must have stopped accumulating exactly at MAX_RECORDS records",
            MAX_RECORDS, rollup.topTasks.size,
        )
    }

    @Test fun `record at the cap drops new records (not old ones)`() {
        // The drop-newest policy is the load-bearing choice — it
        // preserves the earliestStart for the wall-clock totalMs
        // computation. Pin the policy: the FIRST record's path
        // must remain visible in the snapshot after the cap is
        // exceeded.
        val svc = TestService()
        svc.record(":t:first-and-important", 0L, 100L)
        for (i in 0 until MAX_RECORDS + 50) {
            svc.record(":t:filler-$i", 0L, 1L)
        }
        val rollup = svc.snapshot(topN = MAX_RECORDS + 50)
        val firstStillPresent = rollup.topTasks.any { it.path == ":t:first-and-important" }
        assertTrue(
            "drop-newest policy must preserve early records; the first record was evicted",
            firstStillPresent,
        )
    }

    @Test fun `record below the cap is unconstrained (regression guard)`() {
        // Pin that the cap doesn't accidentally fire for normal-size
        // builds. A 1000-record probe is still a small Android build
        // and must record every entry.
        val svc = TestService()
        for (i in 0 until 1000) {
            svc.record(":t:$i", 0L, 1L)
        }
        val rollup = svc.snapshot(topN = 1000)
        assertEquals(
            "queue must accumulate every record below the cap",
            1000, rollup.topTasks.size,
        )
    }

    // ── Zero-duration filter in top_tasks ─────────────────────────

    @Test fun `top_tasks excludes zero-duration tasks (UP-TO-DATE noise filter)`() {
        // On an incremental rebuild most tasks finish in <1ms and
        // would otherwise fill the top-10 with no-op noise, pushing
        // real bottleneck signal off the report. Pin the filter:
        // mix of zero and non-zero durations → top_tasks contains
        // ONLY the non-zero ones.
        val svc = TestService()
        svc.record(":app:assembleDebug",       0L, 5_000L)  // 5s — real work
        svc.record(":app:compileDebugKotlin",  0L, 3_000L)  // 3s — real work
        svc.record(":app:up-to-date-1",        0L, 0L)      // UP-TO-DATE
        svc.record(":app:up-to-date-2",        0L, 0L)      // UP-TO-DATE
        svc.record(":app:no-source",           500L, 500L)  // 0ms (start==end)
        svc.record(":app:short-real",          0L, 1L)      // 1ms — real (kept)

        val rollup = svc.snapshot()
        val paths = rollup.topTasks.map { it.path }.toSet()
        assertTrue(
            "real-work tasks must appear; got $paths",
            ":app:assembleDebug" in paths &&
                ":app:compileDebugKotlin" in paths &&
                ":app:short-real" in paths,
        )
        assertTrue(
            "zero-duration tasks must NOT appear in top_tasks; got $paths",
            ":app:up-to-date-1" !in paths &&
                ":app:up-to-date-2" !in paths &&
                ":app:no-source" !in paths,
        )
        assertEquals(
            "exactly 3 non-zero-duration tasks expected in top_tasks",
            3, rollup.topTasks.size,
        )
    }

    @Test fun `top_tasks is empty when every task is zero-duration (full UP-TO-DATE build)`() {
        // The all-UP-TO-DATE case is the strongest expression of
        // the filter: a fully-incremental rebuild has zero real
        // work, and `top_tasks` should reflect that as an empty
        // list — NOT a list of 10 zero-ms entries which the prior
        // code would have produced.
        val svc = TestService()
        repeat(20) { i -> svc.record(":t:$i", 1000L, 1000L) }
        val rollup = svc.snapshot()
        assertTrue(
            "all-zero-duration build must produce empty top_tasks; got ${rollup.topTasks}",
            rollup.topTasks.isEmpty(),
        )
    }

    @Test fun `category sums still include zero-duration tasks (the filter is rollup-only)`() {
        // Sanity: the filter is only on top_tasks (the listing).
        // Per-category sums and totalMs still cover the full
        // observed graph — a category sum is naturally unaffected
        // by adding 0 to it, but we pin this so a refactor that
        // moved the filter earlier in the pipeline doesn't change
        // the category-sum semantics.
        val svc = TestService()
        svc.record(":app:compileDebugKotlin", 0L, 5_000L)  // 5s managed code
        svc.record(":app:bundleRelease",      6_000L, 6_000L)  // 0ms packaging (UP-TO-DATE)

        val rollup = svc.snapshot()
        assertEquals(
            "managed code sum must include the real-work task",
            5_000L, rollup.managedCodeMs,
        )
        // packaging is 0 either way (the zero-duration task
        // contributes 0); pin the wall-clock total is correctly
        // computed from earliestStart/latestEnd regardless of the
        // top_tasks filter.
        assertEquals(
            "totalMs is wall-clock span regardless of top_tasks filter",
            6_000L, rollup.totalMs,
        )
    }

    /**
     * A minimal [FinishEvent] that is NOT a TaskFinishEvent. Used to
     * exercise the early-return in `onFinish`.
     */
    private fun nonTaskFinishEvent(): FinishEvent {
        val descriptor = object : OperationDescriptor {
            override fun getName() = "non-task-op"
            override fun getDisplayName() = "non-task-op"
            override fun getParent(): OperationDescriptor? = null
        }
        val result = object : OperationResult {
            override fun getStartTime() = 0L
            override fun getEndTime() = 1L
        }
        val eventTime = 1L
        return object : FinishEvent {
            override fun getEventTime() = eventTime
            override fun getDisplayName() = "non-task finish"
            override fun getDescriptor() = descriptor
            override fun getResult() = result
        }
    }
}
