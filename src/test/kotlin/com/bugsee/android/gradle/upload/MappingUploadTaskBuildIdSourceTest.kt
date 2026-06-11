package com.bugsee.android.gradle.upload

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.gradle.testfixtures.ProjectBuilder
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals

/**
 * Pin the contract that [MappingUploadTask] uses the resolved
 * BUILD_UUID — written by `BugseeBuildIdResolveTask` — as the upload
 * identity, NOT the manifest meta-data fallback.
 *
 * Why this matters: with R8 enabled, the SDK reports crashes at
 * runtime under the *mapping-derived* UUID (read from the asset
 * channel). If the upload task keys the mapping under the *manifest*
 * fallback UUID — as the pre-fix code did — the appserver lookup
 * `crash.build_uuid → mapping_file` silently misses for every
 * minified release build, and symbolication never resolves.
 *
 * The test sets the resolved file to a UUID different from the
 * manifest's meta-data, runs the task against an in-process HTTP
 * server, and asserts the POSTed JSON's `uuid` field carries the
 * RESOLVED value. A regression that swapped the source back to
 * `ManifestModifier.getMetaDataValue(...)` would fail loudly.
 */
class MappingUploadTaskBuildIdSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: HttpServer
    private val capturedPostJson = AtomicReference<String?>(null)
    private val appToken = "test-token"
    private val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    @Before fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()

        // POST /apps/<token>/symbols — records the JSON payload, then
        // returns a presigned URL pointing back at this same server.
        server.createContext("/apps/$appToken/symbols") { ex: HttpExchange ->
            val bos = ByteArrayOutputStream()
            ex.requestBody.use { it.copyTo(bos) }
            capturedPostJson.set(bos.toString(Charsets.UTF_8))
            val body = JSONObject().apply {
                put("endpoint", "$baseUrl/presigned/blob")
            }.toString().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        // PUT presigned URL — drain and return 200; we don't care
        // about the body bytes here, only the POST JSON identity.
        server.createContext("/presigned/blob") { ex: HttpExchange ->
            ex.requestBody.use { it.copyTo(ByteArrayOutputStream()) }
            ex.sendResponseHeaders(200, -1L)
        }
        server.start()
    }

    @After fun tearDown() {
        server.stop(0)
    }

    private val manifestUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val resolvedUuid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

    private fun manifestXml(buildUuid: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  package="com.example"
                  android:versionName="1.0"
                  android:versionCode="42">
            <application android:label="X">
                <meta-data android:name="com.bugsee.android.BUILD_UUID"
                           android:value="$buildUuid" />
            </application>
        </manifest>
    """.trimIndent()

    @Test
    fun `upload payload carries the resolved UUID, not the manifest meta-data`() {
        // Mismatched inputs: the manifest meta-data UUID and the
        // resolve-task output UUID are intentionally different. The
        // upload must follow the resolved file. The pre-fix code
        // would emit the manifest UUID here and silently break
        // crash-to-mapping correlation in production.
        val manifest = tempFolder.newFile("AndroidManifest.xml").apply {
            writeText(manifestXml(manifestUuid))
        }
        val mapping = tempFolder.newFile("mapping.txt").apply {
            writeText("# compiler: R8\ncom.example.Foo -> a:\n")
        }
        val resolvedFile = tempFolder.newFile("build-id.txt").apply {
            writeText(resolvedUuid)
        }

        val project = ProjectBuilder.builder().withProjectDir(tempFolder.newFolder()).build()
        val task = project.tasks.register("upload", MappingUploadTask::class.java) { t ->
            t.debug.set(false)
            t.variantName.set("release")
            t.endpoint.set(baseUrl)
            t.uploader.set(UploaderStrategy.KOTLIN)
            t.manifestFile.set(manifest)
            t.mappingFile.set(mapping)
            t.resolvedBuildIdFile.set(resolvedFile)
            t.preResolvedAppToken.set(appToken)
            t.rootProjectDirectory.set(project.layout.projectDirectory)
        }.get()

        task.execute()

        val body = capturedPostJson.get()
            ?: error("server never received the POST — upload short-circuited")
        val json = JSONObject(body)
        assertEquals(
            resolvedUuid,
            json.getString("uuid"),
            "upload payload must carry the RESOLVED BUILD_UUID, not the manifest meta-data — " +
                "otherwise the server's mapping record will never match the SDK's runtime " +
                "asset-channel UUID for any R8-minified build",
        )
    }

    @Test
    fun `whitespace-only resolved BUILD_UUID file short-circuits the upload`() {
        // The production code does `readText().trim()` before the
        // empty-check. A mutation that dropped the `.trim()` would
        // pass the zero-byte test below (already empty) yet ship a
        // whitespace UUID to the server. Pin the trim contract here
        // — newlines + spaces + tabs only, no real content.
        val manifest = tempFolder.newFile("AndroidManifest.xml").apply {
            writeText(manifestXml(manifestUuid))
        }
        val mapping = tempFolder.newFile("mapping.txt").apply {
            writeText("# compiler: R8\n")
        }
        val resolvedFile = tempFolder.newFile("build-id.txt").apply {
            writeText("   \n\t \n")
        }

        val project = ProjectBuilder.builder().withProjectDir(tempFolder.newFolder()).build()
        val task = project.tasks.register("upload", MappingUploadTask::class.java) { t ->
            t.debug.set(false)
            t.variantName.set("release")
            t.endpoint.set(baseUrl)
            t.uploader.set(UploaderStrategy.KOTLIN)
            t.manifestFile.set(manifest)
            t.mappingFile.set(mapping)
            t.resolvedBuildIdFile.set(resolvedFile)
            t.preResolvedAppToken.set(appToken)
            t.rootProjectDirectory.set(project.layout.projectDirectory)
        }.get()

        task.execute()

        assertEquals(
            null,
            capturedPostJson.get(),
            "whitespace-only resolved file must short-circuit — the .trim() " +
                "before the empty-check is load-bearing; without it a " +
                "whitespace UUID would ship and the server would key the " +
                "mapping under it",
        )
    }

    @Test
    fun `empty resolved BUILD_UUID file short-circuits the upload`() {
        // Defensive: if the resolve task somehow produced an empty
        // file (R8 disabled and the fallback chain returned empty),
        // we'd rather skip than upload under an empty key. Pin that
        // contract — otherwise a regression that fell back to the
        // manifest meta-data on empty would re-introduce the bug
        // this test class exists to prevent.
        val manifest = tempFolder.newFile("AndroidManifest.xml").apply {
            writeText(manifestXml(manifestUuid))
        }
        val mapping = tempFolder.newFile("mapping.txt").apply {
            writeText("# compiler: R8\n")
        }
        val resolvedFile = tempFolder.newFile("build-id.txt")  // 0 bytes

        val project = ProjectBuilder.builder().withProjectDir(tempFolder.newFolder()).build()
        val task = project.tasks.register("upload", MappingUploadTask::class.java) { t ->
            t.debug.set(false)
            t.variantName.set("release")
            t.endpoint.set(baseUrl)
            t.uploader.set(UploaderStrategy.KOTLIN)
            t.manifestFile.set(manifest)
            t.mappingFile.set(mapping)
            t.resolvedBuildIdFile.set(resolvedFile)
            t.preResolvedAppToken.set(appToken)
            t.rootProjectDirectory.set(project.layout.projectDirectory)
        }.get()

        task.execute()

        assertEquals(
            null,
            capturedPostJson.get(),
            "empty resolved file must short-circuit BEFORE the POST — got body: " +
                "${capturedPostJson.get()}",
        )
    }
}
