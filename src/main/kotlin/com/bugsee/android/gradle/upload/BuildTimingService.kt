package com.bugsee.android.gradle.upload

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One executed task's timing record. Immutable; safe to share across
 * threads.
 */
internal data class TaskTiming(
    val path: String,
    val startTime: Long,
    val endTime: Long
) {
    val durationMs: Long get() = endTime - startTime
}


/**
 * Trimmed task entry mirroring the on-wire JSON shape for
 * `build_metadata.timings.top_tasks[]`. Used inside [BuildTimings] so
 * the rollup doesn't carry dead `startTime`/`endTime` fields all the
 * way to [BuildTimings.toJson].
 */
internal data class TopTaskEntry(
    val path: String,
    val durationMs: Long
)


/**
 * Rolled-up timings matching the `build_metadata.timings` wire format
 * the appserver expects. All fields are optional on the wire; absent
 * counters are omitted from the emitted JSON.
 */
internal data class BuildTimings(
    val javaMs: Long,
    val nativeMs: Long,
    val resourcesMs: Long,
    val packagingMs: Long,
    val otherMs: Long,
    val totalMs: Long,
    val topTasks: List<TopTaskEntry>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (javaMs      > 0) put("java_ms",      javaMs)
        if (nativeMs    > 0) put("native_ms",    nativeMs)
        if (resourcesMs > 0) put("resources_ms", resourcesMs)
        if (packagingMs > 0) put("packaging_ms", packagingMs)
        if (otherMs     > 0) put("other_ms",     otherMs)
        if (totalMs     > 0) put("total_ms",     totalMs)
        if (topTasks.isNotEmpty()) {
            val arr = JSONArray()
            for (t in topTasks) {
                arr.put(JSONObject().apply {
                    put("name", t.path)
                    put("duration_ms", t.durationMs)
                })
            }
            put("top_tasks", arr)
        }
    }

    companion object {
        val EMPTY = BuildTimings(0L, 0L, 0L, 0L, 0L, 0L, emptyList())
    }
}


/**
 * Pure aggregator, split from the Gradle BuildService for unit
 * testability (BuildService can't be `new`-ed in plain JUnit).
 *
 * - Per-category sums come from [TaskCategoryClassifier].
 * - `totalMs` is the wall-clock span from the earliest task start to
 *   the latest task end. NOT the arithmetic sum of durations — parallel
 *   tasks would otherwise inflate it past wall time. This matches what
 *   a developer expects "total build time" to mean.
 * - `topTasks` keeps up to [topN] slowest entries, descending.
 *
 * @param timings all observed task timings (any order)
 * @param topN    max entries to return in [BuildTimings.topTasks]
 */
internal fun buildTimings(
    timings: Collection<TaskTiming>,
    topN: Int = DEFAULT_TOP_N
): BuildTimings {
    if (timings.isEmpty()) return BuildTimings.EMPTY

    var java = 0L
    var native = 0L
    var resources = 0L
    var packaging = 0L
    var other = 0L
    var earliestStart = Long.MAX_VALUE
    var latestEnd = Long.MIN_VALUE

    for (t in timings) {
        val d = t.durationMs
        when (TaskCategoryClassifier.classify(t.path)) {
            TaskCategory.JAVA      -> java += d
            TaskCategory.NATIVE    -> native += d
            TaskCategory.RESOURCES -> resources += d
            TaskCategory.PACKAGING -> packaging += d
            TaskCategory.OTHER     -> other += d
        }
        if (t.startTime < earliestStart) earliestStart = t.startTime
        if (t.endTime > latestEnd)       latestEnd = t.endTime
    }

    val total = (latestEnd - earliestStart).coerceAtLeast(0L)
    val top = timings.asSequence()
        .sortedByDescending { it.durationMs }
        .take(topN)
        .map { TopTaskEntry(it.path, it.durationMs) }
        .toList()

    return BuildTimings(java, native, resources, packaging, other, total, top)
}


internal const val DEFAULT_TOP_N = 10


/**
 * Gradle BuildService that observes every task completion in the
 * current build and accumulates timings. Registered once per build
 * via [BuildEventsListenerRegistry.onTaskCompletion] from the
 * plugin's `apply()` method; the upload task reads a snapshot at
 * execution time via [snapshot].
 *
 * Thread-safe: task-completion events arrive on the Gradle build
 * thread, but the upload task runs later on the same build. A
 * [ConcurrentLinkedQueue] keeps the listener allocation-free on
 * the hot path and avoids a lock contention point when multiple
 * parallel executors are finishing short tasks.
 *
 * Configuration-cache safe: the service is stateless across
 * configuration and execution phases because we accumulate during
 * execution and snapshot at execution. There are no captured
 * Project references.
 */
abstract class BuildTimingService :
    BuildService<BuildServiceParameters.None>,
    OperationCompletionListener {

    private val records = ConcurrentLinkedQueue<TaskTiming>()

    override fun onFinish(event: FinishEvent) {
        if (event !is TaskFinishEvent) return
        val result = event.result
        record(
            path = event.descriptor.taskPath,
            startTime = result.startTime,
            endTime = result.endTime
        )
    }

    // Separated from `onFinish` so the recording logic is testable
    // without constructing a whole tooling-events object graph. The
    // `onFinish` adapter above has a single responsibility: filter
    // non-task events and extract the three fields.
    internal fun record(path: String, startTime: Long, endTime: Long) {
        records.add(TaskTiming(path, startTime, endTime))
    }

    /**
     * Produce a rollup of every task observed so far. The snapshot is
     * a consistent point-in-time read — not synchronized — so a task
     * completing concurrently with the snapshot may or may not be
     * visible. Safe because the upload task is typically last in the
     * graph. `topN` is parameterised to keep the aggregator testable;
     * consumers use the default.
     */
    internal fun snapshot(topN: Int = DEFAULT_TOP_N): BuildTimings =
        buildTimings(records.toList(), topN)
}
