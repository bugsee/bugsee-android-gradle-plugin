package com.bugsee.android.gradle.upload

import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationDescriptor
import org.gradle.tooling.events.OperationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertEquals(4_000L, rollup.javaMs)
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
        val svc = TestService()
        val threads = 8
        val perThread = 50
        val latch = java.util.concurrent.CountDownLatch(threads)
        for (i in 0 until threads) {
            Thread {
                for (j in 0 until perThread) {
                    svc.record(":t:${i}-$j", 0, 1L)
                }
                latch.countDown()
            }.start()
        }
        latch.await()

        val rollup = svc.snapshot(topN = threads * perThread)
        assertEquals(threads * perThread, rollup.topTasks.size)
    }


    // ── helpers ────────────────────────────────────────────────────

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
