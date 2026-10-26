package com.bugsee.android.gradle.upload

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The unstripped-libraries directory upload consults [com.bugsee.android.gradle.util.SymbolHashCache]
 * (issue #57): an unchanged tree is not handed to bugsee-cli again, a failed upload is never
 * remembered, and `force` bypasses the check. Driven through the real task with a counting shell
 * script standing in for bugsee-cli.
 */
class NativeLibsDirectoryCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var project: Project
    private lateinit var libs: File
    private lateinit var counter: File
    private lateinit var exitCode: File
    private lateinit var cli: File

    @Before
    fun setUp() {
        project = ProjectBuilder.builder().withProjectDir(temp.newFolder("proj")).build()
        libs = File(project.projectDir, "build/intermediates/merged_native_libs/release/out/lib/arm64-v8a")
            .apply { mkdirs() }
        File(libs, "libfoo.so").writeText("foo-v1")
        File(libs, "libbar.so").writeText("bar-v1")
        counter = File(temp.root, "invocations")
        exitCode = File(temp.root, "exit").apply { writeText("0") }
        cli = File(temp.root, "bugsee-cli").apply {
            writeText("#!/bin/sh\necho run >> '${counter.path}'\nexit \$(cat '${exitCode.path}')\n")
            setExecutable(true)
        }
    }

    private fun invocations() = if (counter.exists()) counter.readLines().size else 0

    private fun runTask(force: Boolean = false, endpoint: String = "https://api.example.com", token: String = "tok") {
        val manifest = File(temp.root, "AndroidManifest.xml").apply {
            writeText(
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                    "android:versionCode=\"7\" android:versionName=\"1.0\"><application/></manifest>",
            )
        }
        val buildId = File(temp.root, "build-id").apply { writeText("uuid-1") }
        val task = project.tasks.create("native${System.nanoTime()}", NativeUploadTask::class.java)
        task.debug.set(false)
        task.variantName.set("release")
        task.endpoint.set(endpoint)
        task.forceUpload.set(force)
        task.useMergedNativeLibs.set(true)
        task.mergedNativeLibsDir.set(project.layout.projectDirectory.dir("build/intermediates/merged_native_libs/release"))
        task.cliPath.set(cli.path)
        task.cliVersion.set("0.8.1")
        task.cliAutoUpdate.set(false)
        task.gradleUserHomeDir.set(temp.newFolder())
        task.uploader.set(UploaderStrategy.CLI)
        task.manifestFile.set(manifest)
        task.resolvedBuildIdFile.set(buildId)
        task.preResolvedAppToken.set(token)
        task.rootProjectDirectory.set(project.layout.projectDirectory)
        task.buildDirectory.set(project.layout.buildDirectory)
        task.execute()
    }

    @Test fun `an unchanged tree is not uploaded twice`() {
        runTask()
        runTask()
        assertEquals(1, invocations())
    }

    @Test fun `a changed library is uploaded again`() {
        runTask()
        File(libs, "libfoo.so").writeText("foo-v2")
        runTask()
        assertEquals(2, invocations())
    }

    @Test fun `a failed upload is not remembered`() {
        exitCode.writeText("21") // substantive failure (e.g. server / token)
        runTask()
        runTask()
        assertEquals(2, invocations(), "both runs reached the CLI")
        exitCode.writeText("0")
        runTask()
        assertEquals(3, invocations())
        runTask()
        assertEquals(3, invocations(), "success is now remembered")
    }

    @Test fun `force bypasses the check`() {
        runTask()
        runTask(force = true)
        assertEquals(2, invocations())
    }

    @Test fun `a different endpoint or token is a different entry`() {
        runTask()
        runTask(endpoint = "https://staging.example.com")
        runTask(token = "other")
        assertEquals(3, invocations())
        runTask()
        assertEquals(3, invocations(), "the original entry survives")
    }

    @Test fun `digest follows content, names and sizes but not mtime`() {
        val before = nativeLibsDigest(libs)
        File(libs, "libfoo.so").setLastModified(0L)
        assertEquals(before, nativeLibsDigest(libs))
        File(libs, "libfoo.so").writeText("foo-v1!")
        assertNotEquals(before, nativeLibsDigest(libs))
        File(libs, "libfoo.so").writeText("foo-v1")
        assertEquals(before, nativeLibsDigest(libs))
        File(libs, "libbar.so").renameTo(File(libs, "libbaz.so"))
        assertNotEquals(before, nativeLibsDigest(libs))
    }

    @Test fun `digest ignores files the CLI would not read`() {
        val before = nativeLibsDigest(libs)
        File(libs, "notes.txt").writeText("x")
        assertEquals(before, nativeLibsDigest(libs))
        File(libs, "libqux.so.dbg").writeText("x")
        assertNotEquals(before, nativeLibsDigest(libs))
    }
}
