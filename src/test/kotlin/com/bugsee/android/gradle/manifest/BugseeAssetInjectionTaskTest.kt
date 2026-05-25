package com.bugsee.android.gradle.manifest

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

/**
 * Pins [BugseeAssetInjectionTask]'s output shape:
 *   1. Input assets are copied verbatim into the output directory.
 *   2. `bugsee_build_id.properties` is appended with the resolved
 *      UUID under the key `bugsee.build_id`.
 *
 * The asset format is the SDK's runtime contract — see
 * `BugseeEnvironment.readBuildIdFromAsset` on the SDK side, which
 * uses `java.util.Properties.load` and reads the same key. A regression
 * in either side independently would silently break runtime build-id
 * resolution.
 */
class BugseeAssetInjectionTaskTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun execute_writesBuildIdAssetWithStandardPropertiesFormat() {
        val inDir = tempFolder.newFolder("in")
        val outDir = tempFolder.newFolder("out")
        val resolvedFile = tempFolder.newFile("build-id.txt").apply {
            writeText("12345678-1234-1234-1234-123456789abc")
        }

        val task = createTask(inDir, outDir, resolvedFile)
        task.execute()

        val asset = File(outDir, "bugsee_build_id.properties")
        assertTrue("asset file must exist after task runs", asset.isFile)

        // Round-trip through java.util.Properties — the same parser
        // the SDK will use. Any future drift in our wire format will
        // surface here.
        val props = Properties()
        asset.inputStream().use { props.load(it) }
        assertEquals(
            "asset must carry the UUID under the documented key",
            "12345678-1234-1234-1234-123456789abc",
            props.getProperty("bugsee.build_id"),
        )
    }

    @Test
    fun execute_copiesExistingAssetsVerbatim() {
        val inDir = tempFolder.newFolder("in")
        val outDir = tempFolder.newFolder("out")
        File(inDir, "fonts").mkdirs()
        File(inDir, "fonts/title.ttf").writeBytes(byteArrayOf(0x00, 0x01, 0xFF.toByte()))
        File(inDir, "config.json").writeText("""{"endpoint":"api"}""")
        val resolvedFile = tempFolder.newFile("build-id.txt").apply {
            writeText("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        }

        val task = createTask(inDir, outDir, resolvedFile)
        task.execute()

        // Pre-existing assets survive byte-for-byte.
        assertTrue(File(outDir, "fonts/title.ttf").isFile)
        assertEquals(
            "binary asset must round-trip byte-identical",
            listOf(0x00.toByte(), 0x01.toByte(), 0xFF.toByte()),
            File(outDir, "fonts/title.ttf").readBytes().toList(),
        )
        assertEquals(
            """{"endpoint":"api"}""",
            File(outDir, "config.json").readText(),
        )
        // And the new file is also there.
        assertTrue(File(outDir, "bugsee_build_id.properties").isFile)
    }

    @Test
    fun execute_trimsResolvedFileContent() {
        // If a future refactor of BugseeBuildIdResolveTask started
        // emitting a trailing newline, this task should still write
        // a clean key=value line. The defence-in-depth `.trim()` in
        // the task action makes that contract resilient.
        val inDir = tempFolder.newFolder("in")
        val outDir = tempFolder.newFolder("out")
        val resolvedFile = tempFolder.newFile("build-id.txt").apply {
            writeText("\nbbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\n  \n")
        }

        val task = createTask(inDir, outDir, resolvedFile)
        task.execute()

        val props = Properties()
        File(outDir, "bugsee_build_id.properties").inputStream().use { props.load(it) }
        assertEquals(
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            props.getProperty("bugsee.build_id"),
        )
    }

    @Test
    fun execute_overwritesExistingOutputDirectoryContents() {
        // Gradle's incremental-build semantics let an OutputDirectory
        // hold stale files between runs. The task must produce a
        // self-consistent output directory — i.e. clear stale entries
        // before re-emitting.
        val inDir = tempFolder.newFolder("in")
        File(inDir, "kept.txt").writeText("new")
        val outDir = tempFolder.newFolder("out")
        File(outDir, "stale.txt").writeText("should be gone")
        val resolvedFile = tempFolder.newFile("build-id.txt").apply {
            writeText("cccccccc-cccc-cccc-cccc-cccccccccccc")
        }

        val task = createTask(inDir, outDir, resolvedFile)
        task.execute()

        assertTrue(File(outDir, "kept.txt").isFile)
        assertEquals("new", File(outDir, "kept.txt").readText())
        assertTrue(File(outDir, "bugsee_build_id.properties").isFile)
        assertEquals(
            "stale.txt from a previous run must be cleared",
            false,
            File(outDir, "stale.txt").exists(),
        )
    }

    private fun createTask(
        inDir: File,
        outDir: File,
        resolvedFile: File,
    ): BugseeAssetInjectionTask {
        val project = ProjectBuilder.builder().withProjectDir(tempFolder.root).build()
        val task = project.tasks.register(
            "test${System.nanoTime()}",
            BugseeAssetInjectionTask::class.java,
        ).get()
        task.inputAssetsDir.set(inDir)
        task.outputAssetsDir.set(outDir)
        task.resolvedBuildIdFile.set(resolvedFile)
        return task
    }
}
