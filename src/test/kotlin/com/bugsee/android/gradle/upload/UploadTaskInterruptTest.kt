package com.bugsee.android.gradle.upload

import org.gradle.testfixtures.ProjectBuilder
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #107: a build cancelled while `bugsee-cli` runs must stay cancelled. The CLI uploader reports the killed run as a
 * plain fallback result, so the tasks must rethrow the interrupt (flag restored) instead of warning and falling
 * back to the native uploader.
 */
class UploadTaskInterruptTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var interrupter: Thread? = null

    @Before
    fun requireShell() {
        Assume.assumeTrue("needs /bin/sh", File("/bin/sh").canExecute())
    }

    @After
    fun clearFlag() {
        // Clear the test thread's own flag first: join() would throw on an interrupted thread.
        Thread.interrupted()
        interrupter?.let { it.interrupt(); it.join() }
    }

    @Test
    fun throwIfInterrupted_isNoOpWhenNotInterrupted() {
        HttpRetry.throwIfInterrupted()
        assertTrue(!Thread.currentThread().isInterrupted)
    }

    @Test
    fun throwIfInterrupted_rethrowsWithFlagRestored() {
        Thread.currentThread().interrupt()
        assertFailsWith<InterruptedException> { HttpRetry.throwIfInterrupted() }
        assertTrue(Thread.currentThread().isInterrupted)
    }

    private val started get() = File(tmp.root, "cli.started")

    /** A CLI that hangs once it has signalled it is running, so the task is interrupted while it runs. */
    private fun hangingCli(): File = File(tmp.root, "bugsee-cli").apply {
        writeText("#!/bin/sh\ntouch '${started.absolutePath}'\nexec sleep 60\n")
        setExecutable(true)
    }

    /** Interrupts [target] once the fake CLI is running (bounded wait, so a regression fails instead of hanging). */
    private fun interruptSoon(target: Thread) {
        interrupter = Thread {
            val deadline = System.currentTimeMillis() + 30_000
            while (!started.exists() && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(20) } catch (_: InterruptedException) { return@Thread }
            }
            target.interrupt()
        }.apply { isDaemon = true; start() }
    }

    @Test
    fun mappingTask_interruptDuringCli_propagates() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("p")).build()
        val manifest = tmp.newFile("AndroidManifest.xml").apply {
            writeText(
                """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="x" android:versionCode="1" android:versionName="1.0"><application/></manifest>""",
            )
        }
        val mapping = tmp.newFile("mapping.txt").apply { writeText("a -> b:\n") }
        val buildId = tmp.newFile("buildid.txt").apply { writeText("uuid-1") }
        val task = project.tasks.register("m", MappingUploadTask::class.java) {
            it.debug.set(false)
            it.variantName.set("release")
            it.endpoint.set("http://127.0.0.1:1")
            it.cliPath.set(hangingCli().absolutePath)
            it.uploader.set(UploaderStrategy.CLI)
            it.manifestFile.set(manifest)
            it.mappingFile.set(mapping)
            it.resolvedBuildIdFile.set(buildId)
            it.preResolvedAppToken.set("tok")
            it.gradleUserHomeDir.set(tmp.newFolder("guh"))
            it.rootProjectDirectory.set(project.projectDir)
        }.get()

        interruptSoon(Thread.currentThread())
        assertFailsWith<InterruptedException> { task.execute() }
        assertTrue(Thread.currentThread().isInterrupted, "interrupt flag must be restored")
    }

    @Test
    fun mappingTask_interruptedBeforeResolve_withBadCliPath_propagates() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("p")).build()
        val buildId = tmp.newFile("buildid.txt").apply { writeText("uuid-1") }
        val task = project.tasks.register("m", MappingUploadTask::class.java) {
            it.debug.set(false)
            it.variantName.set("release")
            it.endpoint.set("http://127.0.0.1:1")
            it.cliPath.set(File(tmp.root, "missing-cli").absolutePath)
            it.uploader.set(UploaderStrategy.CLI)
            it.manifestFile.set(manifest())
            it.mappingFile.set(tmp.newFile("mapping.txt").apply { writeText("a -> b:\n") })
            it.resolvedBuildIdFile.set(buildId)
            it.preResolvedAppToken.set("tok")
            it.gradleUserHomeDir.set(tmp.newFolder("guh"))
            it.rootProjectDirectory.set(project.projectDir)
        }.get()

        Thread.currentThread().interrupt()
        assertFailsWith<InterruptedException> { task.execute() }
        assertTrue(Thread.currentThread().isInterrupted, "interrupt flag must be restored")
    }

    private fun manifest(): File = tmp.newFile("AndroidManifest.xml").apply {
        writeText(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="x" android:versionCode="1" android:versionName="1.0"><application/></manifest>""",
        )
    }

    @Test
    fun nativeTask_interruptDuringCli_propagates() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("p")).build()
        val build = tmp.newFolder("b")
        File(build, "intermediates/native_debug_metadata/release/out").apply {
            mkdirs()
            File(this, "libfoo.so.sym").writeText("sym")
        }
        val buildId = tmp.newFile("buildid.txt").apply { writeText("uuid-1") }
        val task = project.tasks.register("n", NativeUploadTask::class.java) {
            it.debug.set(false)
            it.variantName.set("release")
            it.endpoint.set("http://127.0.0.1:1")
            it.forceUpload.set(true)
            it.useMergedNativeLibs.set(false)
            it.cliPath.set(hangingCli().absolutePath)
            it.uploader.set(UploaderStrategy.CLI)
            it.manifestFile.set(manifest())
            it.resolvedBuildIdFile.set(buildId)
            it.preResolvedAppToken.set("tok")
            it.gradleUserHomeDir.set(tmp.newFolder("guh"))
            it.rootProjectDirectory.set(project.projectDir)
            it.buildDirectory.set(build)
        }.get()

        interruptSoon(Thread.currentThread())
        assertFailsWith<InterruptedException> { task.execute() }
        assertTrue(Thread.currentThread().isInterrupted, "interrupt flag must be restored")
    }

    @Test
    fun bundleTask_interruptDuringCli_propagates() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("p")).build()
        val artifact = tmp.newFile("app.aab").apply { writeText("aab") }
        val buildId = tmp.newFile("buildid.txt").apply { writeText("uuid-1") }
        val task = project.tasks.register("b", BundleUploadTask::class.java) {
            it.debug.set(false)
            it.variantName.set("release")
            it.buildConfiguration.set("release")
            it.endpoint.set("http://127.0.0.1:1")
            it.format.set("aab")
            it.bundleFile.set(artifact)
            it.manifestFile.set(manifest())
            it.resolvedBuildIdFile.set(buildId)
            it.preResolvedAppToken.set("tok")
            it.requestArtifactUpload.set(true)
            it.chunkedUpload.set(false)
            it.projectDirectory.set(project.projectDir)
            it.cliPath.set(hangingCli().absolutePath)
            it.gradleUserHomeDir.set(tmp.newFolder("guh"))
        }.get()

        interruptSoon(Thread.currentThread())
        assertFailsWith<InterruptedException> { task.execute() }
        assertTrue(Thread.currentThread().isInterrupted, "interrupt flag must be restored")
    }

    @Test
    fun bundleTask_interruptDuringCliPack_propagatesAndDeletesPartialZip() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("p")).build()
        val artifact = tmp.newFile("app.aab").apply { writeText("aab") }
        val mapping = tmp.newFile("mapping.txt").apply { writeText("a -> b:\n") }
        val uuid = "uuid-pack-${System.nanoTime()}"
        val buildId = tmp.newFile("buildid.txt").apply { writeText(uuid) }
        val task = project.tasks.register("b", BundleUploadTask::class.java) {
            it.debug.set(false)
            it.variantName.set("release")
            it.buildConfiguration.set("release")
            it.endpoint.set("http://127.0.0.1:1")
            it.format.set("aab")
            it.bundleFile.set(artifact)
            it.mappingFile.set(mapping)
            it.manifestFile.set(manifest())
            it.resolvedBuildIdFile.set(buildId)
            it.preResolvedAppToken.set("tok")
            // No artifact upload: skips `upload build`, so the pack step is the first CLI call.
            it.requestArtifactUpload.set(false)
            it.chunkedUpload.set(false)
            it.projectDirectory.set(project.projectDir)
            it.cliPath.set(hangingCli().absolutePath)
            it.gradleUserHomeDir.set(tmp.newFolder("guh"))
        }.get()

        interruptSoon(Thread.currentThread())
        assertFailsWith<InterruptedException> { task.execute() }
        assertTrue(Thread.currentThread().isInterrupted, "interrupt flag must be restored")
        val leftovers = File(System.getProperty("java.io.tmpdir")).listFiles { f -> f.name.startsWith("bugsee-build-$uuid") }
        assertTrue(leftovers.isNullOrEmpty(), "partial pack zip must be deleted: ${leftovers?.toList()}")
    }

    @Test
    fun httpRetry_interruptedBeforeAttempt_sendsNothing() {
        var requests = 0
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> requests++; ex.sendResponseHeaders(200, -1); ex.close() }
        server.start()
        try {
            org.apache.http.impl.client.HttpClients.createDefault().use { client ->
                Thread.currentThread().interrupt()
                assertFailsWith<InterruptedException> {
                    HttpRetry.execute(
                        client,
                        org.apache.http.client.methods.HttpGet("http://127.0.0.1:${server.address.port}/x"),
                        "test",
                        org.gradle.api.logging.Logging.getLogger(UploadTaskInterruptTest::class.java),
                    )
                }
                assertTrue(Thread.currentThread().isInterrupted, "interrupt flag must stay set")
            }
        } finally {
            Thread.interrupted()
            server.stop(0)
        }
        kotlin.test.assertEquals(0, requests)
    }

    @Test
    fun vcsResolver_interrupted_propagatesInsteadOfReturningNoData() {
        Assume.assumeTrue(
            "needs git",
            runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false),
        )
        Thread.currentThread().interrupt()
        assertFailsWith<InterruptedException> { VcsMetadataResolver.resolve(tmp.root, emptyMap()) }
        assertTrue(Thread.currentThread().isInterrupted, "interrupt flag must be restored")
    }
}
