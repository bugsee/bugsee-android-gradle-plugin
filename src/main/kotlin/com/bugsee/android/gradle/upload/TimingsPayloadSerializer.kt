package com.bugsee.android.gradle.upload

import org.gradle.api.logging.Logging
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Serialises the full per-task build timeline into the gzipped JSON
 * blob the worker uploads to S3 under
 * `{org}/{app}/builds/{build}/timings.json`.
 *
 * Wire shape:
 * ```
 * {
 *   "schema_version": 1,
 *   "build_started_at_ms": <wall-clock epoch of first task start>,
 *   "wall_clock_ms":       <span from first task start to last task end>,
 *   "tasks": [
 *     { "path": ":app:compileReleaseKotlin",
 *       "category": "managed_code",
 *       "start_ms": <offset from build_started_at_ms>,
 *       "end_ms":   <offset from build_started_at_ms> },
 *     ...
 *   ]
 * }
 * ```
 *
 * Design notes:
 *   - `start_ms` / `end_ms` are OFFSETS from `build_started_at_ms`
 *     rather than absolute epoch values. Keeps the JSON compact
 *     (3-4 digits per task vs 13 epoch digits) and gzip-friendly.
 *   - `category` is the wire-string value from
 *     [TaskCategory.wire]; consumers (viewer) reuse the same
 *     palette/classification across summary chips + Gantt bars.
 *   - The inline summary on the build POST body (see
 *     [BundleUploadTask] `dependencies_summary`-equivalent) carries
 *     `total_ms` + per-category cumulative + top tasks — sufficient
 *     for the chip strip and bar visualisation. The detail blob
 *     emitted here is fetched lazily by the viewer only when the
 *     user opens the timings tab (and the Gantt UI is implemented).
 *
 * Schema version is independent from the dependencies blob — they
 * evolve separately. Both currently sit at 1.
 */
internal object TimingsPayloadSerializer {

    // Route through Gradle's logger so the truncation notice lands in
    // the same `Bugsee:`-prefixed stream as every other producer-side
    // warning instead of JUL's default handler (which writes to stderr
    // with a different prefix and at a different level threshold).
    private val LOG = Logging.getLogger(TimingsPayloadSerializer::class.java)

    /** Wire-format schema version. Bump in lockstep with the
     *  worker's `_SUPPORTED_SCHEMA_VERSIONS` set. */
    const val SCHEMA_VERSION = 1

    /** Maximum number of task records emitted. Pathological builds
     *  (e.g. a Gradle wrapper that fires thousands of trivial tasks)
     *  shouldn't be able to balloon the blob unbounded. Beyond this
     *  cap we truncate. Mirrors the producer-side cap on
     *  dependencies. */
    const val MAX_TASKS = 10_000

    /**
     * Build the timeline blob from the BuildTimingService snapshot
     * + an optional truncation budget. Returns the gzipped bytes
     * ready to PUT.
     *
     * `clockEpochMs` is the wall-clock epoch (typically
     * `System.currentTimeMillis()` at the moment of upload — used
     * ONLY as a fallback when `timings` is empty so the receiver
     * gets a coherent blob). When `timings` is non-empty,
     * `build_started_at_ms` is the earliest task start, exactly
     * matching the offset arithmetic.
     */
    fun blobJsonObject(
        timings: Collection<TaskTiming>,
        maxTasks: Int = MAX_TASKS,
        clockEpochMs: Long = System.currentTimeMillis()
    ): JSONObject {
        if (timings.isEmpty()) {
            return JSONObject().apply {
                put("schema_version",      SCHEMA_VERSION)
                put("build_started_at_ms", clockEpochMs)
                put("wall_clock_ms",       0)
                put("tasks",               JSONArray())
            }
        }

        var earliestStart = Long.MAX_VALUE
        var latestEnd     = Long.MIN_VALUE
        for (t in timings) {
            if (t.startTime < earliestStart) earliestStart = t.startTime
            if (t.endTime   > latestEnd)     latestEnd     = t.endTime
        }
        val wallClockMs = (latestEnd - earliestStart).coerceAtLeast(0L)

        val cap = maxTasks.coerceAtLeast(0)
        // When truncation kicks in we want to keep the SLOWEST tasks
        // (linkers, packagers, big-module compiles) — those are what
        // a user opens the timings tab to inspect. A naïve
        // chronological-then-drop-tail truncation lops off exactly
        // those entries, since the slow heavy-hitters typically run
        // near the end of the build. Strategy:
        //   1. If the count exceeds the cap, pre-sort by duration
        //      descending and slice the top `cap` entries.
        //   2. Sort the kept slice by start time so the array still
        //      reads chronologically — the viewer's Gantt renderer
        //      assumes start-ordered input for greedy-lane packing.
        val kept: List<TaskTiming> = if (timings.size > cap) {
            LOG.warn(
                "Bugsee: build-timings detail blob truncated " +
                "(${timings.size} tasks → $cap slowest kept)"
            )
            timings.sortedByDescending { it.durationMs }.take(cap)
        } else {
            timings.toList()
        }
        val sorted = kept.sortedBy { it.startTime }

        val tasksArr = JSONArray()
        for (t in sorted) {
            val category = TaskCategoryClassifier.classify(t.path).wire
            tasksArr.put(JSONObject().apply {
                put("path",     t.path)
                put("category", category)
                put("start_ms", t.startTime - earliestStart)
                put("end_ms",   t.endTime   - earliestStart)
            })
        }

        return JSONObject().apply {
            put("schema_version",      SCHEMA_VERSION)
            put("build_started_at_ms", earliestStart)
            put("wall_clock_ms",       wallClockMs)
            put("tasks",               tasksArr)
        }
    }

    /**
     * Render `blob` as compact JSON and gzip the bytes. Same
     * compression-level + writer posture as the dependencies blob
     * (see [DependencyPayloadSerializer.entriesGzBytes]).
     */
    fun gzipBytes(blob: JSONObject): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            gz.write(blob.toString().toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    /** Convenience: build + gzip in one call. */
    fun gzipBytes(timings: Collection<TaskTiming>,
                  maxTasks: Int = MAX_TASKS,
                  clockEpochMs: Long = System.currentTimeMillis()): ByteArray =
        gzipBytes(blobJsonObject(timings, maxTasks, clockEpochMs))

    /**
     * Write the gzipped blob to a file (caller-supplied — typically
     * a temp file under `task.temporaryDir`). Returns the same File
     * for call-site chaining.
     */
    fun writeGz(timings: Collection<TaskTiming>, target: File): File {
        target.parentFile?.mkdirs()
        target.writeBytes(gzipBytes(timings))
        return target
    }
}
