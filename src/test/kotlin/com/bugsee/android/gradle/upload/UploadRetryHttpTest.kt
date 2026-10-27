package com.bugsee.android.gradle.upload

import com.bugsee.android.gradle.config.RecordingLogger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Transient-status retry of the build-registration POST ([BundleUploader]) and of the size-check baseline
 * GET ([BaselineClient]), against a local HTTP server. The server answers each request on the path with
 * the next status of a script (the last one repeats).
 */
class UploadRetryHttpTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val appToken = "tok_SECRET_0123456789abcdef"
    private lateinit var server: HttpServer
    private val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
    private val hits = AtomicInteger()
    private val seenBodies = CopyOnWriteArrayList<String>()
    private val fast = HttpRetry.RetryPolicy(backoffMs = 0)

    private val okBuild = JSONObject().put("ok", true).put("result", JSONObject().put("build_id", "b-1")).toString()
    private val okBaseline = JSONObject().put("ok", true).put(
        "result",
        JSONObject().put("build", JSONObject().put("artifact_size", 1234L).put("version", "1.0").put("build", "7")),
    ).toString()

    @Before fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newSingleThreadExecutor()
    }

    @After fun tearDown() {
        server.stop(0)
    }

    /** Serve [path]: statuses in order (the last repeats); a 2xx status replies with [okBody]. */
    private fun script(path: String, statuses: List<Int>, okBody: String) {
        server.createContext(path) { ex: HttpExchange ->
            val n = hits.getAndIncrement()
            seenBodies.add(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val status = statuses[minOf(n, statuses.size - 1)]
            val bytes = (if (status in 200..299) okBody else """{"ok":false}""").toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun register(logger: RecordingLogger = RecordingLogger(), endpoint: String = baseUrl) {
        BundleUploader.uploadData(
            file = tempFolder.newFile(),
            json = """{"uuid":"u-1","package_id":"com.example"}""",
            appToken = appToken,
            endpoint = endpoint,
            requestArtifactUpload = false,
            logger = logger,
            debug = false,
            retryPolicy = fast,
        )
    }

    private fun baseline(logger: RecordingLogger = RecordingLogger()) = BaselineClient.fetchBaseline(
        endpoint = baseUrl, appToken = appToken, packageId = "com.example", format = "aab",
        buildConfiguration = "release", logger = logger, debug = false, retryPolicy = fast,
    )

    // ── BundleUploader registration POST ─────────────────────────────

    @Test fun `503 then 502 then 200 on the registration POST succeeds after three identical requests`() {
        script("/v2/apps/$appToken/builds", listOf(503, 502, 200), okBuild)
        register()
        assertEquals(3, hits.get())
        assertEquals(1, seenBodies.toSet().size, "every retry must resend the same body")
    }

    @Test fun `persistent 503 on the registration POST gives up after three requests and fails`() {
        script("/v2/apps/$appToken/builds", listOf(503), okBuild)
        val logger = RecordingLogger()
        val ex = assertFailsWith<RuntimeException> { register(logger) }
        assertTrue(ex.message!!.contains("status=503"), "actual: ${ex.message}")
        assertEquals(3, hits.get())
        assertEquals(2, logger.warnings.count { it.contains("retrying") })
    }

    @Test fun `429 on the registration POST is retried`() {
        script("/v2/apps/$appToken/builds", listOf(429, 200), okBuild)
        register()
        assertEquals(2, hits.get())
    }

    @Test fun `500 on the registration POST is not retried since the build may already exist`() {
        script("/v2/apps/$appToken/builds", listOf(500, 200), okBuild)
        assertFailsWith<RuntimeException> { register() }
        assertEquals(1, hits.get())
    }

    @Test fun `4xx on the registration POST is not retried`() {
        script("/v2/apps/$appToken/builds", listOf(403, 200), okBuild)
        assertFailsWith<RuntimeException> { register() }
        assertEquals(1, hits.get())
    }

    @Test fun `a failed registration POST logs neither the token nor a retry line with a URL`() {
        script("/v2/apps/$appToken/builds", listOf(503), okBuild)
        val logger = RecordingLogger()
        val ex = assertFailsWith<RuntimeException> { register(logger) }
        assertFalse(ex.message!!.contains(appToken), "actual: ${ex.message}")
        assertFalse(logger.warnings.any { it.contains(appToken) }, "actual: ${logger.warnings}")
    }

    // ── malformed endpoint: the token is part of the URL path ────────

    @Test fun `a malformed endpoint throws, and the call-site scrub masks the token in the message`() {
        // The request URL embeds the token (`/v2/apps/<token>/builds`), so the parse error echoes it:
        // the raw message is NOT safe to print; every catch block in BundleUploadTask goes through
        // scrubForLog with the token. Pin both facts.
        val ex = assertFailsWith<Exception> { register(endpoint = "http://bad host") }
        val raw = ex.message ?: ""
        assertTrue(raw.contains(appToken), "premise: the raw message carries the token; got: $raw")
        val scrubbed = BundleUploader.scrubForLog(raw, appToken)
        assertFalse(scrubbed.contains(appToken), "scrubbed message must not carry the token; got: $scrubbed")
    }

    @Test fun `a malformed endpoint on the chunked path is masked by the call-site scrub too`() {
        val ex = assertFailsWith<Exception> {
            ChunkedBundleUploader.upload(
                uploadZip = tempFolder.newFile(), metadata = JSONObject(), appToken = appToken,
                endpoint = "http://bad host", logger = RecordingLogger(), debug = false,
            )
        }
        val scrubbed = BundleUploader.scrubForLog(ex.message ?: "", appToken)
        assertFalse(scrubbed.contains(appToken), "scrubbed message must not carry the token; got: $scrubbed")
    }

    // ── BaselineClient GET ───────────────────────────────────────────

    @Test fun `503 then 200 on the baseline GET returns the baseline after a retry`() {
        script("/v2/apps/$appToken/builds/baseline", listOf(503, 200), okBaseline)
        val b = baseline()
        assertNotNull(b)
        assertEquals(1234L, b.artifactSize)
        assertEquals(2, hits.get())
    }

    @Test fun `a 500 on the baseline GET is retried too (read-only)`() {
        script("/v2/apps/$appToken/builds/baseline", listOf(500, 200), okBaseline)
        assertNotNull(baseline())
        assertEquals(2, hits.get())
    }

    @Test fun `persistent 503 on the baseline GET gives up after three requests and skips the check`() {
        script("/v2/apps/$appToken/builds/baseline", listOf(503), okBaseline)
        val logger = RecordingLogger()
        assertNull(baseline(logger))
        assertEquals(3, hits.get())
        assertTrue(logger.warnings.any { it.contains("returned 503") }, "actual: ${logger.warnings}")
        assertFalse(logger.warnings.any { it.contains(appToken) }, "actual: ${logger.warnings}")
    }

    @Test fun `404 on the baseline GET is not retried`() {
        script("/v2/apps/$appToken/builds/baseline", listOf(404, 200), okBaseline)
        assertNull(baseline())
        assertEquals(1, hits.get())
    }
}
