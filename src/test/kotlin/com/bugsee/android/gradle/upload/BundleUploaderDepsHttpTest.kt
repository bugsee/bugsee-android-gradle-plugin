package com.bugsee.android.gradle.upload

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.gradle.api.logging.Logger
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HTTP-driven integration test for [BundleUploader.uploadData]'s
 * dependencies-PUT branch. Exercises the same single-JVM in-process
 * `HttpServer` shape as `ChunkedBundleUploaderHttpTest` so the deps
 * PUT is exercised end-to-end — `BundleUploadTask` swallows
 * `Exception` from this layer, so an undetected regression here
 * silently disables the feature for every consumer.
 */
class BundleUploaderDepsHttpTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private data class Recorded(
        val method: String,
        val path: String,
        val contentType: String?,
        val body: ByteArray,
    )

    private lateinit var server: HttpServer
    private lateinit var logger: Logger
    private val requests = ConcurrentLinkedQueue<Recorded>()
    // Per-test configuration of the createBuild response — tests
    // populate these BEFORE calling `BundleUploader.uploadData`.
    @Volatile private var includeArtifactEndpoint = false
    @Volatile private var includeDependenciesEndpoint = false
    @Volatile private var artifactPutStatus = 200
    @Volatile private var depsPutStatus = 200

    private val appToken = "test-token"
    private val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    @Before fun setUp() {
        // No-op logger: we only care about HTTP wire behaviour, not
        // log messages. A real `org.gradle.api.logging.Logging.getLogger`
        // would also work but spams stderr.
        logger = org.gradle.api.logging.Logging.getLogger("BundleUploaderDepsHttpTest")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()

        // POST /v2/apps/<token>/builds — responds with whichever
        // presigned URLs the test enabled, plus a build_id.
        server.createContext("/v2/apps/$appToken/builds") { ex: HttpExchange ->
            recordExchange(ex)
            val resp = JSONObject().apply {
                put("ok", true)
                val result = JSONObject().apply {
                    put("build_id", "build-1")
                    put("size_analysis_status",
                        if (includeArtifactEndpoint) "uploading" else "unavailable")
                    if (includeArtifactEndpoint) {
                        put("endpoint", "$baseUrl/presigned/artifact?sig=ABC")
                    }
                    if (includeDependenciesEndpoint) {
                        put("dependencies_upload_endpoint",
                            "$baseUrl/presigned/deps?sig=DEF")
                    }
                }
                put("result", result)
            }
            replyJson(ex, 200, resp)
        }

        // The two presigned PUT targets. Each accepts the body and
        // records it for assertions.
        server.createContext("/presigned/artifact") { ex ->
            recordExchange(ex)
            ex.sendResponseHeaders(artifactPutStatus, -1)
            ex.responseBody.close()
        }
        server.createContext("/presigned/deps") { ex ->
            recordExchange(ex)
            ex.sendResponseHeaders(depsPutStatus.toLong().toInt(), -1)
            ex.responseBody.close()
        }

        server.start()
    }

    @After fun tearDown() {
        server.stop(0)
    }

    private fun recordExchange(ex: HttpExchange) {
        val body = ex.requestBody.readBytes()
        requests.add(Recorded(
            method = ex.requestMethod,
            path = ex.requestURI.path,
            contentType = ex.requestHeaders.getFirst("Content-Type"),
            body = body
        ))
    }

    private fun replyJson(ex: HttpExchange, status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun freshArtifact(): java.io.File {
        // Stand-in for the AAB/APK zip; only the bytes themselves are
        // relevant for the PUT assertion.
        val f = tempFolder.newFile("artifact.zip")
        f.writeBytes(ByteArray(64) { it.toByte() })
        return f
    }

    private fun freshDepsGz(): java.io.File {
        // Real gzipped JSON so the body bytes start with the gzip
        // magic, the way the producer (DependencyPayloadSerializer)
        // would emit it. The deps endpoint doesn't parse — only that
        // the body round-trips intact.
        val entries = listOf(DependencyEntry(
            group = "g", name = "n", version = "1",
            direct = true, scope = "implementation",
            type = DependencyEntry.Type.LIBRARY
        ))
        val f = tempFolder.newFile("deps.json.gz")
        val summary = DependenciesSummary.from(
            entries, false, 0L,
            CollectionConfig(scope = "runtime", includeSelectedReason = false, maxCount = 5000)
        )
        DependencyPayloadSerializer.writeEntriesGz(entries, summary, f)
        return f
    }

    private fun metadataJson(withDepsFlag: Boolean): String =
        JSONObject().apply {
            put("uuid", "u-1")
            put("package_id", "com.example")
            put("request_artifact_upload", includeArtifactEndpoint)
            if (withDepsFlag) put("request_dependencies_upload", true)
        }.toString()

    // ── Tests ──────────────────────────────────────────────────────

    @Test fun `deps PUT carries Content-Type octet-stream and the gz body bytes`() {
        includeArtifactEndpoint = false
        includeDependenciesEndpoint = true
        val deps = freshDepsGz()
        val depsBytes = deps.readBytes()

        BundleUploader.uploadData(
            file = freshArtifact(),
            json = metadataJson(withDepsFlag = true),
            appToken = appToken,
            endpoint = baseUrl,
            requestArtifactUpload = false,
            logger = logger,
            debug = false,
            dependenciesGzFile = deps,
        )

        val depsReq = requests.firstOrNull { it.path == "/presigned/deps" }
        assertNotNull(depsReq, "deps PUT must fire when both gz file + server endpoint are present")
        assertEquals("PUT", depsReq.method)
        assertEquals("application/octet-stream", depsReq.contentType)
        // Body bytes match the producer-side gz blob — no
        // re-compression, no re-serialisation along the way.
        assertTrue(depsReq.body.contentEquals(depsBytes),
                   "deps PUT body must be the same bytes the producer wrote")
        // First two bytes are the gzip magic — extra defence against a
        // mutation that drops the gzip wrapper.
        assertEquals(0x1F.toByte(), depsReq.body[0])
        assertEquals(0x8B.toByte(), depsReq.body[1])
    }

    @Test fun `both PUTs fire when artifact and deps are requested together`() {
        includeArtifactEndpoint = true
        includeDependenciesEndpoint = true
        val artifact = freshArtifact()
        val deps = freshDepsGz()

        BundleUploader.uploadData(
            file = artifact,
            json = metadataJson(withDepsFlag = true),
            appToken = appToken,
            endpoint = baseUrl,
            requestArtifactUpload = true,
            logger = logger,
            debug = false,
            dependenciesGzFile = deps,
        )

        // Both presigned PUTs hit, in addition to the metadata POST.
        val pathSeq = requests.map { it.path }.toList()
        assertEquals(3, pathSeq.size)
        assertEquals("/v2/apps/$appToken/builds", pathSeq[0])
        assertEquals("/presigned/artifact", pathSeq[1])
        assertEquals("/presigned/deps",     pathSeq[2])
    }

    @Test fun `deps PUT does not fire when caller passes no gz file`() {
        includeArtifactEndpoint = false
        // Server would offer one, but the caller didn't supply a file.
        // Verifies the caller-side opt-in is respected even if the
        // server were to (incorrectly) hand back a URL.
        includeDependenciesEndpoint = true

        BundleUploader.uploadData(
            file = freshArtifact(),
            json = metadataJson(withDepsFlag = false),
            appToken = appToken,
            endpoint = baseUrl,
            requestArtifactUpload = false,
            logger = logger,
            debug = false,
            dependenciesGzFile = null,
        )

        assertTrue(
            requests.none { it.path == "/presigned/deps" },
            "deps PUT must not fire when no gz file is supplied"
        )
    }

    @Test fun `deps PUT failure does not fail the call (best-effort)`() {
        // Both endpoints offered; deps endpoint returns 500. The
        // artefact PUT must still fire and the call returns normally.
        includeArtifactEndpoint = true
        includeDependenciesEndpoint = true
        depsPutStatus = 500

        BundleUploader.uploadData(
            file = freshArtifact(),
            json = metadataJson(withDepsFlag = true),
            appToken = appToken,
            endpoint = baseUrl,
            requestArtifactUpload = true,
            logger = logger,
            debug = false,
            dependenciesGzFile = freshDepsGz(),
        )

        // Artefact PUT did fire.
        val artifactReq = requests.firstOrNull { it.path == "/presigned/artifact" }
        assertNotNull(artifactReq, "artefact PUT must fire regardless of deps PUT outcome")
        // Deps PUT was attempted.
        val depsReq = requests.firstOrNull { it.path == "/presigned/deps" }
        assertNotNull(depsReq, "deps PUT must be attempted")
        // If the call had thrown, this assertion would never execute —
        // reaching here proves the best-effort posture works.
    }

    @Test fun `deps PUT fires even when artifact upload was not requested`() {
        // The deps path is independent of the artefact upload path —
        // a build-info-only build (no size analysis) still ships
        // dependencies if the client asked.
        includeArtifactEndpoint = false
        includeDependenciesEndpoint = true

        BundleUploader.uploadData(
            file = freshArtifact(),
            json = metadataJson(withDepsFlag = true),
            appToken = appToken,
            endpoint = baseUrl,
            requestArtifactUpload = false,
            logger = logger,
            debug = false,
            dependenciesGzFile = freshDepsGz(),
        )

        // Metadata POST + deps PUT; NO artefact PUT.
        assertTrue(requests.any { it.path == "/v2/apps/$appToken/builds" })
        assertTrue(requests.any { it.path == "/presigned/deps" })
        assertTrue(requests.none { it.path == "/presigned/artifact" },
                   "artefact PUT must not fire when not requested")
    }

    @Test fun `artefact PUT failure does not skip deps PUT`() {
        // Symmetric regression for the artefact-PUT-independence fix:
        // a 5xx on the artefact PUT used to throw out of the
        // `client.use { }` block and skip the subsequent deps PUT,
        // even though the KDoc claimed independence. The fix wrapped
        // the artefact PUT in its own try/catch so a failure on one
        // best-effort upload does not break the others. Without this
        // test, a future "tidy-up" that re-introduces the bare throw
        // ships green through CI.
        includeArtifactEndpoint = true
        includeDependenciesEndpoint = true
        artifactPutStatus = 500

        BundleUploader.uploadData(
            file = freshArtifact(),
            json = metadataJson(withDepsFlag = true),
            appToken = appToken,
            endpoint = baseUrl,
            requestArtifactUpload = true,
            logger = logger,
            debug = false,
            dependenciesGzFile = freshDepsGz(),
        )

        // Artefact PUT was attempted (5xx); deps PUT MUST still fire.
        val artifactReq = requests.firstOrNull { it.path == "/presigned/artifact" }
        assertNotNull(artifactReq,
                      "artefact PUT must be attempted even when it'll fail")
        val depsReq = requests.firstOrNull { it.path == "/presigned/deps" }
        assertNotNull(depsReq,
                      "deps PUT must fire even when artefact PUT returned 5xx")
        // Reaching here at all means uploadData did not throw — the
        // best-effort posture on the artefact branch survives the 5xx.
    }
}
