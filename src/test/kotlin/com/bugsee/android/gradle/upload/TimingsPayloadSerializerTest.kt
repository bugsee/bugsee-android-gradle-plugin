package com.bugsee.android.gradle.upload

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Tests for the gzipped timings detail blob serializer
 * ([TimingsPayloadSerializer]). The blob is fetched by the viewer when
 * a user opens the timings tab, so the on-wire shape, offset
 * arithmetic, and truncation strategy all need to be pinned.
 */
class TimingsPayloadSerializerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun parseGz(bytes: ByteArray): JSONObject {
        val text = GZIPInputStream(ByteArrayInputStream(bytes)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        return JSONObject(text)
    }

    private fun t(path: String, start: Long, end: Long): TaskTiming =
        TaskTiming(path = path, startTime = start, endTime = end)

    @Test fun `emits the wire shape exactly`() {
        // Pin every top-level field name + presence. Drift on any of
        // these breaks the viewer's renderer / the worker's validator.
        val timings = listOf(
            t(":app:compileReleaseKotlin", 1_000, 5_000),
            t(":app:bundleRelease",        5_000, 5_800)
        )
        val blob = TimingsPayloadSerializer.blobJsonObject(timings)
        assertEquals(TimingsPayloadSerializer.SCHEMA_VERSION, blob.getInt("schema_version"))
        assertEquals(1_000L, blob.getLong("build_started_at_ms"))
        assertEquals(4_800L, blob.getLong("wall_clock_ms"))
        val tasks = blob.getJSONArray("tasks")
        assertEquals(2, tasks.length())
        val first = tasks.getJSONObject(0)
        // Each task entry: path, category, start_ms, end_ms.
        assertEquals(":app:compileReleaseKotlin", first.getString("path"))
        assertEquals("managed_code", first.getString("category"))
        assertEquals(0L,     first.getLong("start_ms"))
        assertEquals(4_000L, first.getLong("end_ms"))
    }

    @Test fun `offset arithmetic uses build_started_at_ms as origin`() {
        // Absolute-epoch task t0 + 1000 / t0 + 2000 -> offsets 1000 / 2000
        // and build_started_at_ms == t0. Pins the rule that the
        // earliest task start anchors the timeline (the receiver
        // doesn't have to re-derive it from absolute epochs).
        val t0 = 1_700_000_000_000L
        val timings = listOf(
            t(":a:earliest", t0,         t0 + 500),
            t(":a:later",    t0 + 1_000, t0 + 2_000)
        )
        val blob = TimingsPayloadSerializer.blobJsonObject(timings)
        assertEquals(t0, blob.getLong("build_started_at_ms"))
        val later = blob.getJSONArray("tasks").getJSONObject(1)
        assertEquals(":a:later",   later.getString("path"))
        assertEquals(1_000L,       later.getLong("start_ms"))
        assertEquals(2_000L,       later.getLong("end_ms"))
    }

    @Test fun `wall_clock_ms is latest end minus earliest start`() {
        // Three overlapping tasks; wall_clock matches the outer
        // envelope (earliest start → latest end), NOT the arithmetic
        // sum of durations.
        val timings = listOf(
            t(":a:one",   1_000, 3_500),
            t(":a:two",   1_200, 4_800),
            t(":a:three", 2_000, 4_500)
        )
        val blob = TimingsPayloadSerializer.blobJsonObject(timings)
        assertEquals(3_800L, blob.getLong("wall_clock_ms")) // 4800 - 1000
    }

    @Test fun `truncation kicks in at MAX_TASKS`() {
        val maxTasks = 5
        val timings = (1..maxTasks + 5).map {
            t(":t:$it", it.toLong() * 100, it.toLong() * 100 + 50)
        }
        val blob = TimingsPayloadSerializer.blobJsonObject(timings, maxTasks = maxTasks)
        assertEquals(maxTasks, blob.getJSONArray("tasks").length())
    }

    @Test fun `truncation preserves the slowest tasks`() {
        // Regression for the fix: previously truncation dropped the
        // LAST N tasks by start time — wiping out the linker /
        // packager / large-module-compile entries that run near the
        // end of the build and are exactly what users want to see.
        // The fixed implementation pre-sorts by duration descending,
        // takes the top MAX_TASKS, and re-sorts the kept slice by
        // start_ms for the Gantt renderer.
        val cap = 5
        // 10 short tasks at the start (durations 1..10), 10 long
        // tasks at the end (durations 100..109). The kept slice
        // must contain the 5 LONGEST entries.
        val shorts = (1..10).map { i ->
            t(":short:$i", i.toLong(), i.toLong() + i.toLong())
        }
        val longs = (1..10).map { i ->
            val start = 10_000L + i.toLong()
            t(":long:$i", start, start + (99L + i.toLong()))
        }
        val blob = TimingsPayloadSerializer.blobJsonObject(shorts + longs, maxTasks = cap)
        val tasksArr = blob.getJSONArray("tasks")
        assertEquals(cap, tasksArr.length())
        // Kept entries must ALL come from the longs bucket — assert
        // every path starts with `:long:`.
        for (i in 0 until tasksArr.length()) {
            val path = tasksArr.getJSONObject(i).getString("path")
            assertTrue(
                "kept entry should be from the slowest tasks, got: $path",
                path.startsWith(":long:")
            )
        }
        // And the kept tasks must be sorted by start_ms (chronological)
        // — the viewer's Gantt renderer assumes start-ordered input.
        var prev = -1L
        for (i in 0 until tasksArr.length()) {
            val start = tasksArr.getJSONObject(i).getLong("start_ms")
            assertTrue("tasks must be sorted by start_ms (prev=$prev, curr=$start)",
                       start >= prev)
            prev = start
        }
    }

    @Test fun `empty input produces empty tasks array with consistent wall_clock_ms=0`() {
        // Cache-replay edge case: BuildTimingService captured no
        // events. The blob still needs to be well-formed so the
        // viewer doesn't fall into an undefined branch.
        val clock = 1_700_000_000_000L
        val blob = TimingsPayloadSerializer.blobJsonObject(
            timings = emptyList(),
            clockEpochMs = clock
        )
        assertEquals(TimingsPayloadSerializer.SCHEMA_VERSION, blob.getInt("schema_version"))
        assertEquals(clock, blob.getLong("build_started_at_ms"))
        assertEquals(0L,    blob.getLong("wall_clock_ms"))
        assertEquals(0,     blob.getJSONArray("tasks").length())
    }

    @Test fun `gz round-trips identically`() {
        val timings = listOf(
            t(":app:compileReleaseKotlin", 1_000, 5_000),
            t(":app:mergeReleaseResources", 2_000, 3_500),
            t(":app:bundleRelease",        5_000, 5_800)
        )
        val direct = TimingsPayloadSerializer.blobJsonObject(timings)
        val bytes  = TimingsPayloadSerializer.gzipBytes(timings)
        val roundTripped = parseGz(bytes)
        // Compare via toString — JSON objects' equality isn't field-by-
        // field semantic, but toString is stable for a given key/value
        // set in this version of org.json.
        assertEquals(direct.toString(), roundTripped.toString())
    }

    @Test fun `category wire token matches classifier`() {
        // Every emitted task must carry one of the five wire tokens
        // declared on [TaskCategory.wire]. Drift between producer and
        // viewer here breaks the chip / Gantt palette mapping.
        val allowed = setOf("managed_code", "native", "resources", "packaging", "other")
        val timings = listOf(
            t(":app:compileReleaseKotlin",       1_000, 2_000), // managed_code
            t(":app:externalNativeBuildRelease", 1_000, 3_000), // native
            t(":app:mergeReleaseResources",      1_000, 1_500), // resources
            t(":app:packageRelease",             3_000, 4_000), // packaging
            t(":app:lintVitalRelease",           1_000, 1_200)  // other
        )
        val blob = TimingsPayloadSerializer.blobJsonObject(timings)
        val tasks = blob.getJSONArray("tasks")
        for (i in 0 until tasks.length()) {
            val token = tasks.getJSONObject(i).getString("category")
            assertTrue("category token '$token' must be one of $allowed",
                       token in allowed)
        }
    }

    @Test fun `writeGz round-trips bytes through a file`() {
        val target = tempFolder.newFile("timings.json.gz")
        val timings = listOf(t(":app:compileReleaseKotlin", 1_000, 5_000))
        TimingsPayloadSerializer.writeGz(timings, target)
        assertTrue("output must exist", target.exists())
        val parsed = parseGz(target.readBytes())
        assertEquals(1, parsed.getJSONArray("tasks").length())
    }
}
