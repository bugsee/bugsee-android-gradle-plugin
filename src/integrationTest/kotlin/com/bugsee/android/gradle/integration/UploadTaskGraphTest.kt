package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #41: the mapping upload (and the other uploads) used to be finalizers of
 * `assemble<V>` / `bundle<V>` only, so a pipeline that requests a task below those
 * (`package<V>`, `install<V>`, `sign<V>Bundle`, Gradle Play Publisher's
 * `publish<V>Bundle`) built a mapping-derived artifact and never uploaded the mapping.
 *
 * Asserted through `--dry-run` (the task graph, no execution) on a real AGP build.
 */
class UploadTaskGraphTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val mapping = ":app:uploadBugseeReleaseMapping"

    private fun uploadsInGraph(vararg task: String): Pair<List<String>, String> {
        val fixture = FixtureProject.materialize("build-type-gating", temp.newFolder())
        val (ok, out) = fixture.runTasksAllowingFailure(
            task.toList() + "--dry-run",
            "-PbugseeFixtureMinifyRelease=true",
        )
        assertTrue("dry-run of ${task.toList()} failed:\n$out", ok)
        val uploads = out.lineSequence().map { it.substringBefore(' ') }
            .filter { it.startsWith(":app:uploadBugsee") }.toList()
        return uploads to out
    }

    @Test
    fun entryTasksBelowAssembleAndBundleAreFinalizedByTheMappingUpload() {
        for (task in listOf(
            ":app:assembleRelease", ":app:bundleRelease", ":app:packageRelease",
            // (`installRelease` exists only for a signed release; it depends on packageRelease.)
            ":app:packageReleaseBundle", ":app:signReleaseBundle",
        )) {
            val (uploads, out) = uploadsInGraph(task)
            assertTrue("$task must pull $mapping into the graph:\n$out", mapping in uploads)
            // A finalizer is scheduled once however many finalized tasks the graph holds.
            assertEquals("$task: no upload may be listed twice", uploads.distinct(), uploads)
        }
    }

    @Test
    fun apkAndBundleSizeUploadsFollowTheirOwnEntryTasks() {
        val apk = ":app:uploadBugseeReleaseApk"
        val bundle = ":app:uploadBugseeReleaseBundle"
        assertTrue(apk in uploadsInGraph(":app:packageRelease").first)
        assertTrue(bundle !in uploadsInGraph(":app:packageRelease").first)
        assertTrue(bundle in uploadsInGraph(":app:signReleaseBundle").first)
        assertTrue(apk !in uploadsInGraph(":app:signReleaseBundle").first)
    }

    @Test
    fun compileAndLintDoNotTriggerUploads() {
        for (task in listOf(":app:compileReleaseJavaWithJavac", ":app:lintRelease", ":app:minifyReleaseWithR8")) {
            val (uploads, out) = uploadsInGraph(task)
            assertEquals("$task must not schedule any upload:\n$out", emptyList<String>(), uploads)
        }
    }
}
