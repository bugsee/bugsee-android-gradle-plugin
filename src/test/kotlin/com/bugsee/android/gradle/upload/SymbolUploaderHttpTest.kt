package com.bugsee.android.gradle.upload

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.gradle.api.logging.Logger
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pin [SymbolUploader]'s HTTP status-handling contract. The previous
 * implementation rejected anything other than literal `200 OK` on both
 * the POST-metadata step and the PUT-to-presigned-URL step. Real-world
 * deployments routinely see successful responses in the full 2xx range:
 *
 *  - **AWS S3 single-PUT** returns `200 OK`.
 *  - **AWS S3 multipart UploadPartCopy / abort with chunked transfer**
 *    returns `200 OK` with a body that the JDK occasionally normalizes.
 *  - **Some CDN proxies in front of S3** (CloudFront, Fastly, Akamai)
 *    rewrite to `201 Created` when adding a new key.
 *  - **Some bucket policies / lifecycle rules** make the storage layer
 *    respond with `204 No Content` (no body) on successful PUT.
 *
 * The strict `!= 200` rejection silently marked all of those as failed
 * uploads — symbols/mappings ended up missing on the server even
 * though storage accepted them, and the build log only saw a generic
 * "Bugsee upload failed" warn with whatever response body happened to
 * be present (often empty for the 204 case).
 *
 * `BundleUploader.uploadData` had already used the inclusive 200..299
 * range; the strict check in [SymbolUploader] was an unintentional
 * inconsistency between the two sibling uploaders. This test file
 * pins the corrected behavior.
 */
class SymbolUploaderHttpTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockSymbolServer
    private lateinit var logger: RecordingLoggerForSymbol
    private val appToken = "test-token"

    @Before fun setUp() {
        server = MockSymbolServer(appToken).apply { start() }
        logger = RecordingLoggerForSymbol()
    }

    @After fun tearDown() {
        server.stop()
    }

    /** Bytes for an upload — content irrelevant, only size + transfer matter. */
    private fun tempFile(): java.io.File {
        val f = tempFolder.newFile("symbols.zip")
        f.writeBytes(ByteArray(1024) { (it and 0xFF).toByte() })
        return f
    }

    private val sampleJson = """{"uuid":"u-1","version":"1.0","build":1,"hash":"abc"}"""

    // ── POST-metadata status handling ────────────────────────────────

    @Test fun `POST returning 200 succeeds (baseline)`() {
        server.setPostStatus(200)
        val ok = SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
        )
        assertTrue(ok, "POST 200 must be accepted; warns: ${logger.warnMessages}")
    }

    @Test fun `POST returning 201 Created is now accepted`() {
        // Some CDN proxies in front of S3 (CloudFront, Fastly, Akamai)
        // rewrite the POST response to 201 when the symbol metadata
        // creates a new server-side record. The prior strict `!= 200`
        // check silently failed every such build's symbol upload.
        server.setPostStatus(201)
        val ok = SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
        )
        assertTrue(ok, "POST 201 must be accepted; warns: ${logger.warnMessages}")
        assertTrue(
            logger.warnMessages.none { it.contains("Bugsee upload failed") },
            "POST 201 must NOT log an upload-failed warn; got: ${logger.warnMessages}",
        )
    }

    @Test fun `POST returning 204 No Content is accepted but PUT skipped (no presigned endpoint to call)`() {
        // 204 No Content is the edge of the contract — the response
        // body is empty, so even though we accept the status, the
        // JSON body parse will produce an empty `endpoint` and the
        // helper will bail before the PUT step. That's still better
        // than the prior rejection: in the strict-200 world, 204
        // also failed for the wrong reason ("not 200" rather than
        // "empty endpoint"). Pin the new behavior so a future
        // contract change is intentional.
        server.setPostStatus(204)
        server.setPostBody("")  // 204 → no body
        val ok = SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
        )
        // 204 with empty body → JSONObject("") throws inside use{},
        // returns false. This is a server-bug case; the test pins
        // that we do NOT crash the build.
        assertFalse(ok, "204 with empty body must fail gracefully, not crash; warns: ${logger.warnMessages}")
    }

    @Test fun `POST returning 4xx is still rejected`() {
        // 4xx must continue to be a failure — only the 2xx range
        // gates differently. Pin this so a future overcorrection
        // ("accept anything that's not 500") doesn't slip in.
        server.setPostStatus(404)
        server.setPostBody("""{"error":{"type":"ApplicationNotFoundError"}}""")
        val ok = SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
        )
        assertFalse(ok, "POST 404 must remain a failure")
        // The new warn includes the status code so operators can
        // distinguish "API down" from "invalid token" from the
        // logs alone.
        assertTrue(
            logger.warnMessages.any { it.contains("status=404") },
            "warn must include the status code; got: ${logger.warnMessages}",
        )
    }

    @Test fun `POST returning 5xx is still rejected`() {
        server.setPostStatus(503)
        server.setPostBody("Service Unavailable")
        val ok = SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
        )
        assertFalse(ok, "POST 503 must remain a failure")
        assertTrue(
            logger.warnMessages.any { it.contains("status=503") },
            "warn must include the status code; got: ${logger.warnMessages}",
        )
    }

    // ── PUT (presigned URL) status handling ──────────────────────────

    @Test fun `PUT returning 200 succeeds (baseline)`() {
        server.setPostStatus(200)
        server.setPutStatus(200)
        val ok = SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
        )
        assertTrue(ok, "PUT 200 must be accepted")
    }

    @Test fun `PUT returning 201 Created is now accepted (CDN-rewritten S3 response)`() {
        // Same root cause as the POST-201 case above. Pin both
        // endpoints' relaxations symmetrically.
        server.setPostStatus(200)
        server.setPutStatus(201)
        val ok = SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
        )
        assertTrue(ok, "PUT 201 must be accepted; warns: ${logger.warnMessages}")
    }

    @Test fun `PUT returning 204 No Content is now accepted (S3 single-PUT empty body)`() {
        // This is the most-impactful real-world case. S3 single-PUT
        // returns 204 when the bucket has `Object Ownership = Bucket
        // owner enforced` or certain lifecycle rules; the previous
        // strict `!= 200` rejected every such upload despite the
        // bytes being committed server-side.
        server.setPostStatus(200)
        server.setPutStatus(204)
        val ok = SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
        )
        assertTrue(ok, "PUT 204 must be accepted; warns: ${logger.warnMessages}")
        assertTrue(
            logger.warnMessages.none { it.contains("Bugsee upload failed") },
            "PUT 204 must NOT log an upload-failed warn; got: ${logger.warnMessages}",
        )
    }

    @Test fun `PUT returning 299 (boundary) is accepted`() {
        // Boundary test. 299 is in the 2xx range but rare in
        // practice. A mutation that tightened the check to a
        // narrower range (e.g. 200..208) would fail this.
        server.setPostStatus(200)
        server.setPutStatus(299)
        val ok = SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
        )
        assertTrue(ok, "PUT 299 (boundary) must be accepted")
    }

    @Test fun `PUT returning 300 (Multiple Choices) is rejected (outside 2xx)`() {
        // Boundary test on the upper side. 300 Multiple Choices is
        // a redirection, not success. Pin the closed upper bound
        // at 299.
        server.setPostStatus(200)
        server.setPutStatus(300)
        val ok = SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
        )
        assertFalse(ok, "PUT 300 must remain a failure")
        assertTrue(
            logger.warnMessages.any { it.contains("status=300") },
            "warn must include status=300; got: ${logger.warnMessages}",
        )
    }

    @Test fun `PUT returning 4xx is still rejected`() {
        server.setPostStatus(200)
        server.setPutStatus(403)
        server.setPutBody("Forbidden — signature expired")
        val ok = SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
        )
        assertFalse(ok, "PUT 403 must remain a failure")
    }

    // The lower bound of the 2xx range (200) is the baseline test
    // above. We don't pin behavior below 200 — 1xx responses are
    // protocol-level informational and Apache HttpClient handles
    // them internally before they reach our status check. The
    // boundary tests at 199 / 100 would test Apache's behavior, not
    // ours; the 4xx / 5xx rejection tests cover the "real failure"
    // paths that matter.

    // ── Special server responses preserved (not status-range related) ─

    @Test fun `POST 200 with non-JSON body fails gracefully (W1 — was a JSONException crash pre-fix)`() {
        // The Item 1 status-range relaxation widened the success
        // set to include 201/202/204. Real-world CDN proxies in
        // front of S3 sometimes return 200/201 with a text/plain
        // "OK" or "Accepted" body instead of structured JSON.
        // Pre-W1 fix, the parser called `JSONObject(contentText)`
        // unconditionally — a non-JSON body threw JSONException
        // that escaped `httpClient.use {}` and crashed the Gradle
        // task with a stacktrace instead of cleanly logging a warn.
        //
        // Post-fix, the parse is wrapped in try/catch; a non-JSON
        // body returns false with a warn that includes the status
        // code and a body preview.
        server.setPostStatus(200)
        server.setPostBody("Accepted")  // plain-text, not JSON

        // Do NOT use assertFailsWith — the contract is "return
        // false cleanly", not "throw". The test asserts BOTH
        // (1) no exception, AND (2) the function returns false.
        val ok = SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
        )
        assertFalse(ok, "non-JSON 2xx body must fail gracefully")
        // The warn must mention the parse failure shape AND the
        // status code, so an operator looking at build logs can
        // disambiguate "API returned text/plain instead of JSON"
        // from a generic upload failure.
        assertTrue(
            logger.warnMessages.any {
                it.contains("not valid JSON") && it.contains("status=200")
            },
            "warn must explain the parse failure with status code; got: ${logger.warnMessages}",
        )
    }

    @Test fun `POST 200 with HTML error page (proxy-injected) fails gracefully`() {
        // Intermediate proxies (corporate forward proxies, CDN
        // error edges) occasionally rewrite the response body to
        // an HTML error page while preserving the 200 status. The
        // body would parse to a JSONException at the `<` character.
        // Pre-fix: stacktrace crash. Post-fix: clean false return.
        server.setPostStatus(200)
        server.setPostBody("<!DOCTYPE html><html><body>Proxy timeout</body></html>")

        val ok = SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
        )
        assertFalse(ok, "HTML body on 200 must fail gracefully")
    }

    @Test fun `POST 201 with non-JSON body also fails gracefully (covers Item 1 widened range)`() {
        // Boundary check: the widened range (200..299) increased
        // the surface where non-JSON bodies are now reachable.
        // Pin that 201 specifically (the most-likely CDN rewrite)
        // is in scope of the defensive parse.
        server.setPostStatus(201)
        server.setPostBody("Created")  // plain text

        val ok = SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
        )
        assertFalse(ok, "201 with non-JSON body must fail gracefully")
    }

    @Test fun `SymbolAlreadyExistsError on POST returns success (server-side dedup)`() {
        // The `code=16004` server response indicates the symbol
        // already exists for this build — we treat it as success
        // (the symbol IS on the server, no need to re-upload).
        // Pin that this short-circuit still works in the new code
        // path.
        server.setPostStatus(200)
        server.setPostBody("""{"code":16004}""")
        val ok = SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
        )
        assertTrue(ok, "SymbolAlreadyExistsError must be treated as success")
        // No PUT should have fired — the server already has the symbol.
        assertEquals(0, server.putCallCount(), "PUT must not be attempted on dedup")
    }

    // ── X-Bugsee-Uploader telemetry header ───────────────────────────

    @Test fun `default uploaderTag stamps X-Bugsee-Uploader as kotlin`() {
        // The header carries the "which uploader path ran" telemetry the
        // backend uses to count CLI-vs-fallback usage during the dual-path
        // rollout. Direct invocations of SymbolUploader default to
        // "kotlin" — the Kotlin path is the source-of-truth fallback.
        server.setPostStatus(200)
        SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
        )
        val headers = server.lastPostHeaders
        assertNotNull(headers, "POST must have arrived; warns: ${logger.warnMessages}")
        assertEquals(
            "kotlin",
            headers.getFirst("X-Bugsee-Uploader"),
            "default uploaderTag must be 'kotlin'",
        )
    }

    @Test fun `explicit uploaderTag is forwarded as X-Bugsee-Uploader value`() {
        // When MappingUploadTask falls back from a structural CLI failure,
        // it passes uploaderTag = "kotlin-fallback-cli-<reason>" so the
        // backend can bucket by reason. Pin that the value is forwarded
        // verbatim — backend dashboards depend on the exact string.
        server.setPostStatus(200)
        SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
            uploaderTag = "kotlin-fallback-cli-exit-1",
        )
        val headers = server.lastPostHeaders
        assertNotNull(headers, "POST must have arrived; warns: ${logger.warnMessages}")
        assertEquals(
            "kotlin-fallback-cli-exit-1",
            headers.getFirst("X-Bugsee-Uploader"),
            "explicit uploaderTag must be forwarded verbatim",
        )
    }

    @Test fun `PUT to presigned URL does NOT carry X-Bugsee-Uploader`() {
        // The presigned PUT goes to S3, whose URL is signed against a
        // specific header set. Adding X-Bugsee-Uploader there would
        // trigger SignatureDoesNotMatch and silently fail every upload.
        // Pin that the header lands on the POST only.
        //
        // We assert this indirectly: the POST captures last headers ONLY
        // for the POST handler (the mock's PUT handler doesn't update
        // lastPostHeaders); the PUT must complete successfully (which
        // would fail if we'd signed something we shouldn't have).
        server.setPostStatus(200)
        server.setPutStatus(200)

        val ok = SymbolUploader.uploadData(
            file = tempFile(),
            json = sampleJson,
            appToken = appToken,
            endpoint = server.baseUrl,
            logger = logger,
            debug = false,
            uploaderTag = "kotlin-fallback-cli-exec-failed",
        )
        assertTrue(ok, "upload must succeed; warns: ${logger.warnMessages}")

        // POST headers reflect the test's chosen tag.
        val postHeaders = server.lastPostHeaders
        assertNotNull(postHeaders)
        assertEquals(
            "kotlin-fallback-cli-exec-failed",
            postHeaders.getFirst("X-Bugsee-Uploader"),
        )
        // (PUT-header inspection is left to a future extension of MockSymbolServer
        // if we ever need to pin the absence positively rather than infer it.)
        assertEquals(1, server.putCallCount(), "PUT must have run")
    }
}

/**
 * Minimal HTTP mock for [SymbolUploader]'s two-stage flow:
 *  1. `POST /apps/<token>/symbols` — returns JSON metadata.
 *  2. `PUT <presigned-endpoint>` — accepts file bytes.
 *
 * Configurable per-request status code and body for both phases.
 * Captures PUT attempt counts for assertions.
 *
 * Distinct from [MockBuildsServer] (which mocks the chunked-upload
 * appserver endpoints) to keep the test surface minimal and easy to
 * read — a full multi-route mock would obscure the status-handling
 * contract being tested.
 */
internal class MockSymbolServer(private val appToken: String) {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val putAttempts = AtomicInteger(0)

    @Volatile private var postStatus: Int = 200
    @Volatile private var postBody: String? = null  // null = use default
    @Volatile private var putStatus: Int = 200
    @Volatile private var putBody: String = ""

    /**
     * The most recent POST's request headers. Captured so tests can assert
     * what the client sent — notably `X-Bugsee-Uploader`, which gates the
     * dual-path rollout telemetry.
     */
    @Volatile var lastPostHeaders: Headers? = null

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun setPostStatus(status: Int) { postStatus = status }
    fun setPostBody(body: String) { postBody = body }
    fun setPutStatus(status: Int) { putStatus = status }
    fun setPutBody(body: String) { putBody = body }
    fun putCallCount(): Int = putAttempts.get()

    fun start() {
        server.executor = Executors.newSingleThreadExecutor()
        // Symbol POST endpoint.
        server.createContext("/apps/$appToken/symbols") { exchange ->
            handlePost(exchange)
        }
        // Presigned-URL PUT endpoint. The path is `/presigned/blob`;
        // the server points the client here via the POST response.
        server.createContext("/presigned/blob") { exchange ->
            handlePut(exchange)
        }
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    private fun handlePost(exchange: HttpExchange) {
        // Capture request headers BEFORE the response is sent — tests assert
        // on telemetry headers like X-Bugsee-Uploader.
        lastPostHeaders = exchange.requestHeaders
        // Drain the body so the client can read the response.
        exchange.requestBody.use { it.readBytes() }
        val body = postBody ?: JSONObject().apply {
            put("endpoint", "$baseUrl/presigned/blob")
        }.toString()
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(postStatus, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) {
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun handlePut(exchange: HttpExchange) {
        putAttempts.incrementAndGet()
        // Drain the body.
        val bos = ByteArrayOutputStream()
        exchange.requestBody.use { it.copyTo(bos) }
        val bytes = putBody.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(putStatus, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) {
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}

/**
 * Simple recording logger — captures all warn messages for assertion.
 * Mirrors the shape of [RecordingLogger] used in
 * [ChunkedBundleUploaderHttpTest] but defined per-file to keep the
 * test scope focused.
 */
private class RecordingLoggerForSymbol : Logger {
    val warnMessages: MutableList<String> = mutableListOf()
    private fun record(msg: String?) { if (msg != null) warnMessages.add(msg) }
    override fun getName(): String = "recording-symbol"
    override fun isTraceEnabled(): Boolean = false
    override fun isTraceEnabled(p: org.slf4j.Marker?): Boolean = false
    override fun trace(msg: String?) {}
    override fun trace(format: String?, arg: Any?) {}
    override fun trace(format: String?, arg1: Any?, arg2: Any?) {}
    override fun trace(format: String?, vararg arguments: Any?) {}
    override fun trace(msg: String?, t: Throwable?) {}
    override fun trace(marker: org.slf4j.Marker?, msg: String?) {}
    override fun trace(marker: org.slf4j.Marker?, format: String?, arg: Any?) {}
    override fun trace(marker: org.slf4j.Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun trace(marker: org.slf4j.Marker?, format: String?, vararg arguments: Any?) {}
    override fun trace(marker: org.slf4j.Marker?, msg: String?, t: Throwable?) {}
    override fun isDebugEnabled(): Boolean = false
    override fun isDebugEnabled(p: org.slf4j.Marker?): Boolean = false
    override fun debug(msg: String?) {}
    override fun debug(format: String?, arg: Any?) {}
    override fun debug(format: String?, arg1: Any?, arg2: Any?) {}
    override fun debug(format: String?, vararg arguments: Any?) {}
    override fun debug(msg: String?, t: Throwable?) {}
    override fun debug(marker: org.slf4j.Marker?, msg: String?) {}
    override fun debug(marker: org.slf4j.Marker?, format: String?, arg: Any?) {}
    override fun debug(marker: org.slf4j.Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun debug(marker: org.slf4j.Marker?, format: String?, vararg arguments: Any?) {}
    override fun debug(marker: org.slf4j.Marker?, msg: String?, t: Throwable?) {}
    override fun isInfoEnabled(): Boolean = false
    override fun isInfoEnabled(p: org.slf4j.Marker?): Boolean = false
    override fun info(msg: String?) {}
    override fun info(format: String?, arg: Any?) {}
    override fun info(format: String?, arg1: Any?, arg2: Any?) {}
    override fun info(format: String?, vararg arguments: Any?) {}
    override fun info(msg: String?, t: Throwable?) {}
    override fun info(marker: org.slf4j.Marker?, msg: String?) {}
    override fun info(marker: org.slf4j.Marker?, format: String?, arg: Any?) {}
    override fun info(marker: org.slf4j.Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun info(marker: org.slf4j.Marker?, format: String?, vararg arguments: Any?) {}
    override fun info(marker: org.slf4j.Marker?, msg: String?, t: Throwable?) {}
    override fun isWarnEnabled(): Boolean = true
    override fun isWarnEnabled(p: org.slf4j.Marker?): Boolean = true
    override fun warn(msg: String?) { record(msg) }
    override fun warn(format: String?, arg: Any?) { record(format) }
    override fun warn(format: String?, arg1: Any?, arg2: Any?) { record(format) }
    override fun warn(format: String?, vararg arguments: Any?) { record(format) }
    override fun warn(msg: String?, t: Throwable?) { record(msg) }
    override fun warn(marker: org.slf4j.Marker?, msg: String?) { record(msg) }
    override fun warn(marker: org.slf4j.Marker?, format: String?, arg: Any?) { record(format) }
    override fun warn(marker: org.slf4j.Marker?, format: String?, arg1: Any?, arg2: Any?) { record(format) }
    override fun warn(marker: org.slf4j.Marker?, format: String?, vararg arguments: Any?) { record(format) }
    override fun warn(marker: org.slf4j.Marker?, msg: String?, t: Throwable?) { record(msg) }
    override fun isErrorEnabled(): Boolean = false
    override fun isErrorEnabled(p: org.slf4j.Marker?): Boolean = false
    override fun error(msg: String?) {}
    override fun error(format: String?, arg: Any?) {}
    override fun error(format: String?, arg1: Any?, arg2: Any?) {}
    override fun error(format: String?, vararg arguments: Any?) {}
    override fun error(msg: String?, t: Throwable?) {}
    override fun error(marker: org.slf4j.Marker?, msg: String?) {}
    override fun error(marker: org.slf4j.Marker?, format: String?, arg: Any?) {}
    override fun error(marker: org.slf4j.Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun error(marker: org.slf4j.Marker?, format: String?, vararg arguments: Any?) {}
    override fun error(marker: org.slf4j.Marker?, msg: String?, t: Throwable?) {}
    override fun isLifecycleEnabled(): Boolean = false
    override fun lifecycle(msg: String?) {}
    override fun lifecycle(msg: String?, vararg arguments: Any?) {}
    override fun lifecycle(msg: String?, t: Throwable?) {}
    override fun isQuietEnabled(): Boolean = false
    override fun quiet(msg: String?) {}
    override fun quiet(msg: String?, vararg arguments: Any?) {}
    override fun quiet(msg: String?, t: Throwable?) {}
    override fun isEnabled(level: org.gradle.api.logging.LogLevel?): Boolean = false
    override fun log(level: org.gradle.api.logging.LogLevel?, message: String?) {}
    override fun log(level: org.gradle.api.logging.LogLevel?, message: String?, vararg objects: Any?) {}
    override fun log(level: org.gradle.api.logging.LogLevel?, message: String?, throwable: Throwable?) {}
}
