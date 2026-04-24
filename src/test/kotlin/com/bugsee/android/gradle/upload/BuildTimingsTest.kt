package com.bugsee.android.gradle.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuildTimingsTest {

    private fun t(path: String, start: Long, end: Long): TaskTiming =
        TaskTiming(path = path, startTime = start, endTime = end)

    @Test fun `empty input returns EMPTY rollup`() {
        val out = buildTimings(emptyList())
        assertEquals(BuildTimings.EMPTY, out)
    }

    @Test fun `per-category sums match the classifier buckets`() {
        val timings = listOf(
            t(":app:compileReleaseKotlin",     1_000, 9_000),  // JAVA: 8000
            t(":app:mergeReleaseResources",    2_000, 3_500),  // RESOURCES: 1500
            t(":app:mergeReleaseAssets",       3_000, 3_700),  // RESOURCES: 700
            t(":app:externalNativeBuildRelease", 1_500, 6_500),// NATIVE: 5000
            t(":app:bundleRelease",            9_000, 10_200), // PACKAGING: 1200
            t(":app:lintVitalRelease",         1_200, 1_900)   // OTHER: 700
        )
        val rollup = buildTimings(timings)
        assertEquals(8_000L, rollup.managedCodeMs)
        assertEquals(5_000L, rollup.nativeMs)
        assertEquals(2_200L, rollup.resourcesMs)  // 1500 + 700
        assertEquals(1_200L, rollup.packagingMs)
        assertEquals(700L,   rollup.otherMs)
    }

    @Test fun `totalMs is wall clock (earliest start to latest end) not arithmetic sum`() {
        // Two tasks running in parallel — arithmetic sum would be 2200,
        // wall clock is 1200 (earliest=1000, latest=2200).
        val timings = listOf(
            t(":a:one", 1_000, 2_100),   // dur 1100
            t(":b:two", 1_100, 2_200)    // dur 1100
        )
        val rollup = buildTimings(timings)
        assertEquals(1_200L, rollup.totalMs)
    }

    @Test fun `totalMs is zero for a single-point task (start equals end)`() {
        val timings = listOf(t(":x:y", 5_000, 5_000))
        val rollup = buildTimings(timings)
        assertEquals(0L, rollup.totalMs)
    }

    @Test fun `top tasks returns highest durations first, capped at topN`() {
        val timings = listOf(
            t(":a:slow1", 0, 5_000),   // 5000
            t(":a:slow2", 0, 4_000),   // 4000
            t(":a:slow3", 0, 3_000),   // 3000
            t(":a:fast1", 0,   500),   //  500
            t(":a:fast2", 0,   100)    //  100
        )
        val rollup = buildTimings(timings, topN = 3)
        assertEquals(3, rollup.topTasks.size)
        assertEquals(":a:slow1", rollup.topTasks[0].path)
        assertEquals(":a:slow2", rollup.topTasks[1].path)
        assertEquals(":a:slow3", rollup.topTasks[2].path)
    }

    @Test fun `toJson omits zero counters and empty top_tasks`() {
        val json = BuildTimings.EMPTY.toJson()
        assertEquals(0, json.length())
    }

    @Test fun `toJson emits only non-zero counters`() {
        val rollup = BuildTimings(
            managedCodeMs = 1000, nativeMs = 0, resourcesMs = 500,
            packagingMs = 0, otherMs = 0, totalMs = 2000,
            topTasks = listOf(TopTaskEntry(":app:compileReleaseKotlin", 1000))
        )
        val json = rollup.toJson()
        assertEquals(1000L, json.getLong("managed_code_ms"))
        assertEquals(500L,  json.getLong("resources_ms"))
        assertEquals(2000L, json.getLong("total_ms"))
        // Zero buckets are omitted so the server sees a clean shape.
        assertEquals(false, json.has("native_ms"))
        assertEquals(false, json.has("packaging_ms"))
        assertEquals(false, json.has("other_ms"))
        // top_tasks carries {name, duration_ms} entries.
        val topArr = json.getJSONArray("top_tasks")
        assertEquals(1, topArr.length())
        val first = topArr.getJSONObject(0)
        assertEquals(":app:compileReleaseKotlin", first.getString("name"))
        assertEquals(1000L, first.getLong("duration_ms"))
    }

    @Test fun `toJson top_tasks entries use full task path`() {
        val rollup = buildTimings(listOf(
            t(":feature:payment:compileReleaseKotlin", 0, 100)
        ), topN = 1)
        val json = rollup.toJson()
        assertEquals(":feature:payment:compileReleaseKotlin",
            json.getJSONArray("top_tasks").getJSONObject(0).getString("name"))
    }
}
