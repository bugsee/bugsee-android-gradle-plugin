package com.bugsee.android.gradle.upload

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * In-process HTTP server stand-in for the appserver's chunked-upload
 * endpoints. Used by [ChunkedBundleUploaderHttpTest] to drive the
 * full client flow without spinning up a real server or adding a
 * MockWebServer dependency to the gradle plugin.
 *
 * Behaviour:
 * - `GET /v2/apps/<token>/builds/chunk-options` returns the configured
 *   chunk size / max chunks.
 * - `POST /v2/apps/<token>/builds/chunks/check` accepts `{sha1_list}`
 *   and returns `{missing, upload_urls}` based on [presentChunks].
 *   Missing-chunk URLs point back at this server so the client's PUTs
 *   land in [chunkStore].
 * - `PUT /chunk-store/<sha1>` accepts chunk bytes and stores them
 *   keyed by sha1.
 * - `POST /v2/apps/<token>/builds/chunked` returns `{build_id}` and
 *   captures the final body for assertions.
 *
 * Any path can be overridden via [overrideOnce] / [overrideAlways] to
 * inject HTTP errors at specific phases. Per-call counters survive
 * across overrides — tests can assert "retried 3 times".
 */
internal class MockBuildsServer(
    private val appToken: String = "test-token",
) {
    data class Recorded(
        val method: String,
        val path: String,
        val body: ByteArray,
        val headers: Map<String, String>
    )

    private data class Override(val status: Int, val body: String, var remaining: Int)

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val requestLog = ConcurrentLinkedQueue<Recorded>()
    private val presentChunks = mutableSetOf<String>()
    private val chunkStore = mutableMapOf<String, ByteArray>()
    // Map of "<METHOD> <pathSuffix>" → override stack. pathSuffix may
    // be the literal trailing path or a prefix ending in `*`.
    private val overrides = mutableMapOf<String, ArrayDeque<Override>>()

    @Volatile private var chunkSize: Int = 8 * 1024 * 1024
    @Volatile private var maxChunks: Int = 512
    @Volatile private var buildId: String = "build-from-mock"

    val port: Int get() = server.address.port
    val baseUrl: String get() = "http://127.0.0.1:$port"

    /** Snapshot of every request the client has made, in arrival order. */
    fun recordedRequests(): List<Recorded> = requestLog.toList()

    /** Sha1s that the mock should report as already present. */
    fun setPresentChunks(shas: Collection<String>) {
        presentChunks.clear()
        presentChunks.addAll(shas)
    }

    /** Chunk bytes captured by the PUT handler, keyed by sha1. */
    fun storedChunks(): Map<String, ByteArray> = chunkStore.toMap()

    /** Override chunk-options response values. */
    fun setChunkOptions(chunkSize: Int, maxChunks: Int) {
        this.chunkSize = chunkSize
        this.maxChunks = maxChunks
    }

    fun setBuildIdResponse(buildId: String) {
        this.buildId = buildId
    }

    /**
     * Return [status] for the next [count] matching requests, then
     * resume normal handling. Path may end in `*` for a prefix match
     * (e.g. `PUT /chunk-store/` + `*` covers every per-chunk PUT).
     */
    fun overrideTimes(method: String, pathSuffix: String, status: Int, count: Int = 1, body: String = "") {
        val key = "$method $pathSuffix"
        overrides.getOrPut(key) { ArrayDeque() }.addLast(Override(status, body, count))
    }

    fun overrideAlways(method: String, pathSuffix: String, status: Int, body: String = "") {
        overrideTimes(method, pathSuffix, status, Int.MAX_VALUE, body)
    }

    fun start() {
        server.createContext("/", Handler())
        // Tiny pool — the client is sequential so we never need more than
        // one or two concurrent handlers, but bumping to 4 means a
        // hung handler in one test can't starve the next.
        server.executor = Executors.newFixedThreadPool(4)
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    private fun matchOverride(method: String, path: String): Override? {
        val exact = overrides["$method $path"]?.firstOrNull()
        if (exact != null) return consume(exact, "$method $path")
        // Prefix match: any registered key ending in `*` whose stripped
        // prefix matches the path. First match wins.
        for ((key, queue) in overrides) {
            if (!key.startsWith("$method ")) continue
            val pat = key.substringAfter(' ')
            if (!pat.endsWith('*')) continue
            val prefix = pat.removeSuffix("*")
            if (path.startsWith(prefix)) {
                val o = queue.firstOrNull() ?: continue
                return consume(o, key)
            }
        }
        return null
    }

    private fun consume(o: Override, key: String): Override {
        if (o.remaining != Int.MAX_VALUE) {
            o.remaining -= 1
            if (o.remaining <= 0) {
                overrides[key]?.removeFirst()
            }
        }
        return o
    }

    private inner class Handler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                val method = exchange.requestMethod
                val path = exchange.requestURI.path
                val body = exchange.requestBody.readAllBytes()
                // Capture headers lower-cased so tests don't have to
                // know HTTP/1.1's case-insensitive name semantics.
                // HttpExchange returns the first value as the only
                // value for the headers we care about (Content-Type,
                // Content-Length); multi-valued headers aren't
                // expected here.
                val headers = exchange.requestHeaders.entries.associate { (k, v) ->
                    k.lowercase() to (v.firstOrNull() ?: "")
                }
                requestLog.add(Recorded(method, path, body, headers))

                matchOverride(method, path)?.let { o ->
                    sendString(exchange, o.status, o.body)
                    return
                }

                when {
                    method == "GET" && path.endsWith("/builds/chunk-options") ->
                        handleChunkOptions(exchange)
                    method == "POST" && path.endsWith("/builds/chunks/check") ->
                        handleChunksCheck(exchange, body)
                    method == "PUT" && path.startsWith("/chunk-store/") ->
                        handleChunkPut(exchange, path, body)
                    method == "POST" && path.endsWith("/builds/chunked") ->
                        handleSubmitChunked(exchange, body)
                    else -> sendString(exchange, 404, "no handler for $method $path")
                }
            } catch (e: Exception) {
                sendString(exchange, 500, "harness error: ${e.message}")
            }
        }
    }

    private fun handleChunkOptions(exchange: HttpExchange) {
        val result = JSONObject().apply {
            put("chunk_size", chunkSize)
            put("max_chunks", maxChunks)
            put("chunk_upload_expires_sec", 3600)
        }
        sendJsonResult(exchange, 200, result)
    }

    private fun handleChunksCheck(exchange: HttpExchange, body: ByteArray) {
        val req = JSONObject(String(body, Charsets.UTF_8))
        val list = req.getJSONArray("sha1_list")
        val missing = JSONArray()
        val uploadUrls = JSONObject()
        for (i in 0 until list.length()) {
            val sha = list.getString(i)
            if (sha !in presentChunks) {
                missing.put(sha)
                uploadUrls.put(sha, "$baseUrl/chunk-store/$sha")
            }
        }
        val result = JSONObject().apply {
            put("missing", missing)
            put("upload_urls", uploadUrls)
        }
        sendJsonResult(exchange, 200, result)
    }

    private fun handleChunkPut(exchange: HttpExchange, path: String, body: ByteArray) {
        val sha = path.removePrefix("/chunk-store/")
        chunkStore[sha] = body
        sendString(exchange, 200, "")
    }

    private fun handleSubmitChunked(exchange: HttpExchange, body: ByteArray) {
        // Body is recorded; assertions read it from requestLog.
        val result = JSONObject().apply { put("build_id", buildId) }
        sendJsonResult(exchange, 200, result)
    }

    private fun sendJsonResult(exchange: HttpExchange, status: Int, result: JSONObject) {
        val wrapped = JSONObject().put("result", result).toString()
        val bytes = wrapped.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun sendString(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        // sendResponseHeaders contract: -1 length means "no body", 0
        // means "chunked", positive means "fixed-length". Always send
        // a fixed length here so the client never blocks waiting for
        // a body that isn't coming.
        val len = if (bytes.isEmpty()) -1L else bytes.size.toLong()
        exchange.sendResponseHeaders(status, len)
        if (bytes.isNotEmpty()) {
            exchange.responseBody.use { it.write(bytes) }
        } else {
            exchange.responseBody.close()
        }
    }
}

/** Returns count of recorded requests whose method+path match. */
internal fun List<MockBuildsServer.Recorded>.countMatching(method: String, pathSuffixOrPrefix: String): Int =
    count { r ->
        r.method == method && (
            r.path.endsWith(pathSuffixOrPrefix) ||
            (pathSuffixOrPrefix.endsWith("*") && r.path.startsWith(pathSuffixOrPrefix.removeSuffix("*")))
        )
    }
