package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import com.bugsee.android.gradle.integration.harness.MockSymbolServer
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
 * The graph is asserted through `--dry-run` on a real AGP build, and one real run
 * checks that the mapping actually reaches the (mock) server.
 */
class UploadTaskGraphTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val mapping = ":app:uploadBugseeReleaseMapping"
    private val apk = ":app:uploadBugseeReleaseApk"
    private val bundle = ":app:uploadBugseeReleaseBundle"

    private fun fixture() = FixtureProject.materialize("build-type-gating", temp.newFolder())

    /** Every task line scheduled by a dry run of [task], in graph order. */
    private fun graph(task: String): List<String> {
        val (ok, out) = fixture().runTasksAllowingFailure(
            listOf(task, "--dry-run"),
            "-PbugseeFixtureMinifyRelease=true",
        )
        assertTrue("dry-run of $task failed:\n$out", ok)
        return out.lineSequence().map { it.substringBefore(' ') }.filter { it.startsWith(":") }.toList()
    }

    /** The `:app:uploadBugsee*` tasks scheduled by a dry run of [task], in graph order. */
    private fun uploadsInGraph(task: String): List<String> = graph(task).filter { it.startsWith(":app:uploadBugsee") }

    @Test
    fun releaseEntryTasksBelowAssembleAndBundleScheduleTheUploads() {
        // (`installRelease` exists only for a signed release; it depends on packageRelease.)
        val expectApk = mapOf(
            ":app:assembleRelease" to true, ":app:packageRelease" to true,
            ":app:bundleRelease" to false, ":app:signReleaseBundle" to false,
        )
        for ((task, isApk) in expectApk) {
            val uploads = uploadsInGraph(task)
            assertTrue("$task must pull $mapping into the graph: $uploads", mapping in uploads)
            // A finalizer is scheduled once however many finalized tasks the graph holds.
            assertEquals("$task: no upload may be listed twice", uploads.distinct(), uploads)
            // The size-analysis uploads follow their own artifact only.
            if (task != ":app:assembleRelease") {
                assertEquals("$task apk upload", isApk, apk in uploads)
                assertEquals("$task bundle upload", !isApk, bundle in uploads)
            }
        }
    }

    @Test
    fun packageReleaseBundleDoesNotPullInSigning() {
        // `package<V>Bundle` writes only the unsigned intermediary bundle; the bundle upload
        // consumes the signed SingleArtifact.BUNDLE, so finalizing package<V>Bundle with it
        // would force sign<V>Bundle (and a signing config) into a stage that stops unsigned.
        val tasks = graph(":app:packageReleaseBundle")
        assertTrue("mapping upload still runs: $tasks", mapping in tasks)
        assertTrue("no bundle upload: $tasks", bundle !in tasks)
        assertTrue("signing must not be scheduled: $tasks", ":app:signReleaseBundle" !in tasks)
    }

    @Test
    fun debugDeviceAndPackageTasksDoNotScheduleUploads() {
        // New finalizers apply to release-like variants only: install/connected runs on a
        // developer machine or CI device must not start uploading symbols.
        for (task in listOf(":app:packageDebug", ":app:installDebug", ":app:connectedDebugAndroidTest")) {
            val uploads = uploadsInGraph(task)
            assertEquals("$task must not schedule any upload", emptyList<String>(), uploads)
        }
    }

    @Test
    fun compileAndLintDoNotTriggerUploads() {
        for (task in listOf(":app:compileReleaseJavaWithJavac", ":app:lintRelease", ":app:minifyReleaseWithR8")) {
            assertEquals("$task must not schedule any upload", emptyList<String>(), uploadsInGraph(task))
        }
    }

    @Test
    fun signReleaseBundleRealRunUploadsTheMapping() {
        MockSymbolServer().use { server ->
            val (ok, out) = fixture().runTasksAllowingFailure(
                listOf(":app:signReleaseBundle"),
                "-PbugseeFixtureMinifyRelease=true",
                "-PbugseeFixtureEndpoint=${server.url}",
            )
            assertTrue("build failed:\n$out", ok)
            assertTrue("uploadBugseeReleaseMapping must have run:\n$out", "Task :app:uploadBugseeReleaseMapping" in out)
            assertTrue(
                "the mapping POST must reach the server: ${server.requests.map { it.method + " " + it.path }}",
                server.symbolPosts.isNotEmpty(),
            )
        }
    }

    @Test
    fun packageDebugRealRunSendsNothing() {
        MockSymbolServer().use { server ->
            val (ok, out) = fixture().runTasksAllowingFailure(
                listOf(":app:packageDebug"),
                "-PbugseeFixtureEndpoint=${server.url}",
            )
            assertTrue("build failed:\n$out", ok)
            assertEquals(emptyList<String>(), server.requests.map { it.method + " " + it.path })
        }
    }
}
