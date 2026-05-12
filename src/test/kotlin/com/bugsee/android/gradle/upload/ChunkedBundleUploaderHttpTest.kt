package com.bugsee.android.gradle.upload

import org.gradle.api.logging.Logger
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * HTTP-driven tests for [ChunkedBundleUploader]. The single-thread
 * [MockBuildsServer] stands in for the appserver chunked-upload
 * endpoints and the S3 presigned-PUT targets, so the entire four-phase
 * client flow runs end-to-end inside one JVM with no external deps.
 *
 * The companion [ChunkedBundleUploaderTest] still covers the chunk-
 * hashing algorithm in isolation; this file covers the HTTP wire
 * protocol and every fault-injection scenario the caller needs to
 * survive (since `BundleUploadTask` silently falls back to single-PUT
 * on any throw, an undetected regression here means "chunked is
 * silently broken for everyone").
 */
class ChunkedBundleUploaderHttpTest {

    private lateinit var server: MockBuildsServer
    private lateinit var logger: Logger
    private val appToken = "test-token"
    private val chunkSize = 5 * 1024 * 1024  // S3_MIN_PART_BYTES — smallest legal value

    @Before fun setUp() {
        server = MockBuildsServer(appToken).apply {
            setChunkOptions(chunkSize = chunkSize, maxChunks = 16)
            start()
        }
        logger = SilentLogger()
    }

    @After fun tearDown() {
        server.stop()
    }

    // ── Happy paths ───────────────────────────────────────────────

    @Test fun `all chunks new — every chunk gets a PUT and the final submit fires`() {
        val zip = tempZipOfSize(chunkSize.toLong())  // exactly one chunk
        val metadata = JSONObject().put("package_id", "com.x").put("uuid", "u-1")

        val buildId = ChunkedBundleUploader.upload(
            uploadZip = zip,
            metadata  = metadata,
            appToken  = appToken,
            endpoint  = server.baseUrl,
            logger    = logger,
            debug     = false,
        )

        val reqs = server.recordedRequests()
        assertEquals(1, reqs.countMatching("GET",  "/chunk-options"))
        assertEquals(1, reqs.countMatching("POST", "/chunks/check"))
        assertEquals(1, reqs.countMatching("PUT",  "/chunk-store/*"))
        assertEquals(1, reqs.countMatching("POST", "/chunked"))
        assertEquals("build-from-mock", buildId)
        // Stored chunk bytes round-trip to the zip's actual bytes.
        val storedSha = server.storedChunks().keys.single()
        assertEquals(sha1Hex(zip.readBytes()), storedSha)
    }

    @Test fun `full reuse — zero PUTs when every chunk is already present`() {
        val zip = tempZipOfSize(chunkSize.toLong())
        val onlySha = sha1Hex(zip.readBytes())
        server.setPresentChunks(setOf(onlySha))

        ChunkedBundleUploader.upload(
            uploadZip = zip,
            metadata  = JSONObject().put("package_id", "com.x"),
            appToken  = appToken,
            endpoint  = server.baseUrl,
            logger    = logger,
            debug     = false,
        )

        val reqs = server.recordedRequests()
        assertEquals(0, reqs.countMatching("PUT", "/chunk-store/*"))
        assertEquals(1, reqs.countMatching("POST", "/chunked"))
        // And we never tried to write any chunks to the store.
        assertTrue(server.storedChunks().isEmpty(), "no chunks should be stored on full reuse")
    }

    @Test fun `partial reuse — only the missing chunks get PUTs`() {
        // Three chunks. We mark chunk 0 and chunk 2 as already present;
        // the middle one must still be uploaded.
        val zip = tempZipOfSize((chunkSize.toLong() * 3))
        val hashes = chunkHashes(zip, chunkSize)
        check(hashes.size == 3) { "expected 3 chunks, got ${hashes.size}" }
        // The generator must produce distinct content per chunk —
        // otherwise this test silently degenerates (see comment on
        // tempZipOfSize).
        check(hashes.toSet().size == 3) {
            "expected 3 distinct chunk hashes, got ${hashes.toSet().size}"
        }
        server.setPresentChunks(setOf(hashes[0], hashes[2]))

        ChunkedBundleUploader.upload(
            uploadZip = zip,
            metadata  = JSONObject().put("package_id", "com.x"),
            appToken  = appToken,
            endpoint  = server.baseUrl,
            logger    = logger,
            debug     = false,
        )

        val reqs = server.recordedRequests()
        assertEquals(1, reqs.countMatching("PUT", "/chunk-store/*"))
        assertEquals(setOf(hashes[1]), server.storedChunks().keys)
    }

    // ── Phase-level failures ─────────────────────────────────────

    @Test fun `5xx on chunk-options throws`() {
        server.overrideAlways("GET", "/v2/apps/$appToken/builds/chunk-options", 500, "options down")
        val zip = tempZipOfSize(1024)

        val ex = assertFailsWith<RuntimeException> {
            ChunkedBundleUploader.upload(
                uploadZip = zip, metadata = JSONObject(),
                appToken = appToken, endpoint = server.baseUrl,
                logger = logger, debug = false,
            )
        }
        assertTrue(ex.message!!.contains("chunk-options"), "actual: ${ex.message}")
    }

    @Test fun `5xx on chunks slash check throws`() {
        server.overrideAlways("POST", "/v2/apps/$appToken/builds/chunks/check", 503)
        val zip = tempZipOfSize(1024)

        val ex = assertFailsWith<RuntimeException> {
            ChunkedBundleUploader.upload(
                uploadZip = zip, metadata = JSONObject(),
                appToken = appToken, endpoint = server.baseUrl,
                logger = logger, debug = false,
            )
        }
        assertTrue(ex.message!!.contains("chunks/check"), "actual: ${ex.message}")
    }

    @Test fun `5xx on builds slash chunked throws`() {
        server.overrideAlways("POST", "/v2/apps/$appToken/builds/chunked", 502)
        val zip = tempZipOfSize(1024)

        val ex = assertFailsWith<RuntimeException> {
            ChunkedBundleUploader.upload(
                uploadZip = zip, metadata = JSONObject(),
                appToken = appToken, endpoint = server.baseUrl,
                logger = logger, debug = false,
            )
        }
        assertTrue(ex.message!!.contains("/builds/chunked"), "actual: ${ex.message}")
    }

    @Test fun `2xx on builds slash chunked without build_id throws (fallback signal)`() {
        // A 200 with no build_id means the worker pipeline has no key
        // — the uploader must fail loud so the caller falls back to
        // single-PUT rather than thinking everything's fine.
        server.overrideAlways(
            "POST", "/v2/apps/$appToken/builds/chunked", 200,
            """{"result":{}}""",
        )
        val zip = tempZipOfSize(1024)

        val ex = assertFailsWith<RuntimeException> {
            ChunkedBundleUploader.upload(
                uploadZip = zip, metadata = JSONObject(),
                appToken = appToken, endpoint = server.baseUrl,
                logger = logger, debug = false,
            )
        }
        assertTrue(ex.message!!.contains("without build_id"), "actual: ${ex.message}")
    }

    // ── Chunk-PUT retry semantics ────────────────────────────────

    @Test fun `chunk PUT retries on 5xx and succeeds`() {
        // Two transient 503s on the only chunk PUT, then natural 200.
        // Uploader has CHUNK_PUT_MAX_ATTEMPTS=3 so the third attempt
        // must succeed.
        server.overrideTimes("PUT", "/chunk-store/*", 503, count = 2)
        val zip = tempZipOfSize(1024)

        ChunkedBundleUploader.upload(
            uploadZip = zip, metadata = JSONObject(),
            appToken = appToken, endpoint = server.baseUrl,
            logger = logger, debug = false,
        )

        // 3 PUT attempts total: two 503s + one 200.
        assertEquals(3, server.recordedRequests().countMatching("PUT", "/chunk-store/*"))
        assertEquals(1, server.storedChunks().size)
    }

    @Test fun `chunk PUT 4xx fails immediately without retry`() {
        // 4xx is permanent (expired URL, bad signature) — retrying
        // wastes bandwidth.
        server.overrideAlways("PUT", "/chunk-store/*", 403, "denied")
        val zip = tempZipOfSize(1024)

        val ex = assertFailsWith<RuntimeException> {
            ChunkedBundleUploader.upload(
                uploadZip = zip, metadata = JSONObject(),
                appToken = appToken, endpoint = server.baseUrl,
                logger = logger, debug = false,
            )
        }
        assertTrue(ex.message!!.contains("403"), "actual: ${ex.message}")
        assertEquals(1, server.recordedRequests().countMatching("PUT", "/chunk-store/*"))
    }

    @Test fun `chunk PUT exhausts retries on persistent 5xx`() {
        server.overrideAlways("PUT", "/chunk-store/*", 500, "transient")
        val zip = tempZipOfSize(1024)

        assertFailsWith<RuntimeException> {
            ChunkedBundleUploader.upload(
                uploadZip = zip, metadata = JSONObject(),
                appToken = appToken, endpoint = server.baseUrl,
                logger = logger, debug = false,
            )
        }
        // CHUNK_PUT_MAX_ATTEMPTS is 3 in the uploader.
        assertEquals(3, server.recordedRequests().countMatching("PUT", "/chunk-store/*"))
    }

    // ── Validation / boundaries ──────────────────────────────────

    @Test fun `archive too large for max_chunks throws`() {
        // chunkSize=5 MiB, maxChunks=2 → a 12 MiB file produces 3
        // chunks which exceeds the cap.
        server.setChunkOptions(chunkSize = chunkSize, maxChunks = 2)
        val zip = tempZipOfSize(chunkSize.toLong() * 3)

        val ex = assertFailsWith<RuntimeException> {
            ChunkedBundleUploader.upload(
                uploadZip = zip, metadata = JSONObject(),
                appToken = appToken, endpoint = server.baseUrl,
                logger = logger, debug = false,
            )
        }
        assertTrue(ex.message!!.contains("Archive too large"), "actual: ${ex.message}")
        // No PUTs should have been attempted.
        assertEquals(0, server.recordedRequests().countMatching("PUT", "/chunk-store/*"))
    }

    @Test fun `malformed chunk-options (chunk_size below S3 minimum) throws`() {
        // 4 MiB is below the S3 multipart 5 MiB minimum. A buggy
        // server config that hands this back must fail loud before
        // any chunks are uploaded — otherwise the stitch would 400
        // and we'd have orphaned chunks.
        server.setChunkOptions(chunkSize = 4 * 1024 * 1024, maxChunks = 16)
        val zip = tempZipOfSize(1024)

        val ex = assertFailsWith<IllegalArgumentException> {
            ChunkedBundleUploader.upload(
                uploadZip = zip, metadata = JSONObject(),
                appToken = appToken, endpoint = server.baseUrl,
                logger = logger, debug = false,
            )
        }
        assertTrue(ex.message!!.contains("chunk_size"), "actual: ${ex.message}")
    }

    @Test fun `duplicate-content chunks PUT exactly once`() {
        // Three identical chunks → one unique sha1 → one PUT, then
        // the final POST advertises the same hash three times so the
        // server's stitch can reference it at three PartNumbers.
        val zip = tempZipOfSizeFilled(chunkSize.toLong() * 3, fill = 0x42)
        val hashes = chunkHashes(zip, chunkSize)
        check(hashes.toSet().size == 1) {
            "expected one unique hash for identical-content chunks, got ${hashes.toSet().size}"
        }

        ChunkedBundleUploader.upload(
            uploadZip = zip, metadata = JSONObject(),
            appToken = appToken, endpoint = server.baseUrl,
            logger = logger, debug = false,
        )

        assertEquals(1, server.recordedRequests().countMatching("PUT", "/chunk-store/*"))
        assertEquals(1, server.storedChunks().size)
        // The final POST must still reference the chunk three times —
        // the stitch side relies on PartNumbers, not unique keys.
        val submit = server.recordedRequests().single { it.method == "POST" && it.path.endsWith("/chunked") }
        val submitted = JSONObject(String(submit.body, Charsets.UTF_8))
        val chunks = submitted.getJSONArray("chunks")
        assertEquals(3, chunks.length())
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Create a temp file of `size` bytes with deterministic pseudo-random
     * content. A fixed-seed `java.util.Random` gives reproducible bytes
     * across runs *and* a sequence long enough that even adjacent
     * chunk-sized windows differ — earlier versions used `(offset+i) & 0xff`,
     * which repeats every 256 bytes and made every 5 MiB chunk identical
     * (5 MiB ≡ 0 mod 256), so multi-chunk tests silently degenerated
     * into single-unique-hash files.
     */
    private fun tempZipOfSize(size: Long): File {
        val f = File.createTempFile("bugsee-test", ".zip")
        f.deleteOnExit()
        val rnd = java.util.Random(1234L)
        f.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var remaining = size
            while (remaining > 0) {
                val n = minOf(buf.size.toLong(), remaining).toInt()
                rnd.nextBytes(buf)
                out.write(buf, 0, n)
                remaining -= n
            }
        }
        return f
    }

    /** Create a temp file of `size` bytes filled with a single byte. */
    private fun tempZipOfSizeFilled(size: Long, fill: Int): File {
        val f = File.createTempFile("bugsee-test-fill", ".zip")
        f.deleteOnExit()
        f.outputStream().use { out ->
            val buf = ByteArray(64 * 1024) { fill.toByte() }
            var remaining = size
            while (remaining > 0) {
                val n = minOf(buf.size.toLong(), remaining).toInt()
                out.write(buf, 0, n)
                remaining -= n
            }
        }
        return f
    }

    private fun chunkHashes(file: File, chunkSize: Int): List<String> =
        ChunkedBundleUploader.computeChunkHashes(file, chunkSize)

    private fun sha1Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1").apply { update(bytes) }
        val hex = StringBuilder(40)
        for (b in md.digest()) {
            val v = b.toInt() and 0xff
            hex.append("0123456789abcdef"[v ushr 4])
            hex.append("0123456789abcdef"[v and 0x0f])
        }
        return hex.toString()
    }
}

/**
 * Minimal Logger stub. Gradle's [Logger] interface is enormous and
 * Mockito isn't on the test classpath, so we hand-roll a no-op.
 * Tests don't assert on log output today; if that changes, swap in a
 * recording implementation.
 */
private class SilentLogger : Logger {
    override fun getName(): String = "silent"
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
    override fun isWarnEnabled(): Boolean = false
    override fun isWarnEnabled(p: org.slf4j.Marker?): Boolean = false
    override fun warn(msg: String?) {}
    override fun warn(format: String?, arg: Any?) {}
    override fun warn(format: String?, arg1: Any?, arg2: Any?) {}
    override fun warn(format: String?, vararg arguments: Any?) {}
    override fun warn(msg: String?, t: Throwable?) {}
    override fun warn(marker: org.slf4j.Marker?, msg: String?) {}
    override fun warn(marker: org.slf4j.Marker?, format: String?, arg: Any?) {}
    override fun warn(marker: org.slf4j.Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun warn(marker: org.slf4j.Marker?, format: String?, vararg arguments: Any?) {}
    override fun warn(marker: org.slf4j.Marker?, msg: String?, t: Throwable?) {}
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
    // Gradle Logger-specific methods (LogLevel-based).
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
