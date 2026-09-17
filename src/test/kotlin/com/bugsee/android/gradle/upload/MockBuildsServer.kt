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
    // Body captured by the single-PUT artefact store (the presigned PUT the
    // converged `upload build` flow performs after registration). Keyed by the
    // trailing store path so multiple single PUTs (if ever) don't collide.
    private val singlePutStore = mutableMapOf<String, ByteArray>()
    // Bodies captured by the presigned-symbol metadata POST
    // (`/apps/<token>/symbols`), in arrival order. The presigned-symbol
    // protocol (proguard / elf debug-files uploads) POSTs `{uuid, version,
    // build, hash, transform?}`, gets back a presigned PUT URL, then PUTs the
    // symbol zip. See [handleSymbolMetadata] / [handleSymbolPut].
    private val symbolMetadataBodies = ConcurrentLinkedQueue<ByteArray>()
    // Body captured by the presigned-symbol PUT (the symbol zip).
    @Volatile private var symbolPutBody: ByteArray? = null
    // When true, the symbol metadata POST replies with the appserver's nested
    // DuplicateSymbolsFoundError envelope (code 16004) instead of signing a presigned URL — so a test can
    // exercise the already-exists short-circuit (no PUT performed).
    @Volatile private var symbolAlreadyExists: Boolean = false
    // Last registration body captured by the single-PUT `/builds` POST handler.
    @Volatile private var lastRegistrationBody: ByteArray? = null
    // Captured bodies from the auxiliary-blob PUTs (deps / timings).
    // Keyed by label ("dependencies" / "timings") so tests can assert
    // that the chunked-upload follow-up PUTs landed AND that the body
    // bytes match what the client uploaded.
    private val auxBlobStore = mutableMapOf<String, ByteArray>()
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

    /**
     * Bytes captured by the single-PUT artefact store, keyed by the trailing
     * store path segment. Populated by the converged `upload build` single-PUT
     * flow: registration POST → presigned PUT of the upload ZIP. Empty until
     * the client performs the PUT.
     */
    fun storedSinglePuts(): Map<String, ByteArray> = singlePutStore.toMap()

    /** The body of the most recent single-PUT `/builds` registration POST,
     *  or `null` if the client never registered. */
    fun lastRegistrationBody(): ByteArray? = lastRegistrationBody

    /** Auxiliary-blob bytes captured by the deps / timings PUT handlers,
     *  keyed by label ("dependencies" / "timings"). Empty when the
     *  chunked-submit body didn't carry the corresponding
     *  `request_*_upload` flag (mock won't return a URL, client
     *  won't PUT, store stays empty). */
    fun storedAuxBlobs(): Map<String, ByteArray> = auxBlobStore.toMap()

    /**
     * Bodies captured by the presigned-symbol metadata POST
     * (`POST /apps/<token>/symbols`), in arrival order. One entry per symbol
     * the client (proguard / elf upload) registered. Empty until the client
     * POSTs metadata.
     */
    fun symbolMetadataBodies(): List<ByteArray> = symbolMetadataBodies.toList()

    /**
     * The symbol zip body captured by the presigned-symbol PUT, or `null` if
     * the client never PUT one (e.g. the metadata POST replied
     * SymbolAlreadyExists). Single slot — the symbol tests upload exactly one
     * artefact each.
     */
    fun storedSymbolPut(): ByteArray? = symbolPutBody

    /**
     * Make the next symbol metadata POST reply with the appserver's nested
     * DuplicateSymbolsFoundError envelope (code 16004), so the client skips the presigned PUT. Off by
     * default — the standard flow signs a presigned URL and accepts the PUT.
     */
    fun setSymbolAlreadyExists(value: Boolean) {
        this.symbolAlreadyExists = value
    }

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
                    method == "POST" && path.endsWith("/symbols") ->
                        handleSymbolMetadata(exchange, body)
                    method == "PUT" && path.startsWith("/symbol-put/") ->
                        handleSymbolPut(exchange, body)
                    method == "POST" && path.endsWith("/builds") ->
                        handleRegisterSingle(exchange, body)
                    method == "PUT" && path.startsWith("/single-put/") ->
                        handleSinglePut(exchange, path, body)
                    method == "GET" && path.endsWith("/builds/chunk-options") ->
                        handleChunkOptions(exchange)
                    method == "POST" && path.endsWith("/builds/chunks/check") ->
                        handleChunksCheck(exchange, body)
                    method == "PUT" && path.startsWith("/chunk-store/") ->
                        handleChunkPut(exchange, path, body)
                    method == "PUT" && path.startsWith("/aux-blob-store/") ->
                        handleAuxBlobPut(exchange, path, body)
                    method == "POST" && path.endsWith("/builds/chunked") ->
                        handleSubmitChunked(exchange, body)
                    else -> sendString(exchange, 404, "no handler for $method $path")
                }
            } catch (e: Exception) {
                sendString(exchange, 500, "harness error: ${e.message}")
            }
        }
    }

    /**
     * Single-PUT build registration: `POST /v2/apps/<token>/builds`. Mirrors the
     * appserver's registration response for the converged `upload build`
     * single-PUT path — returns a `build_id` plus a presigned `endpoint` that
     * points back at this server's [handleSinglePut] store, so the client's
     * follow-up PUT of the upload ZIP lands in [singlePutStore].
     *
     * Echoes the build-info upload endpoint only when the registration body
     * carried `request_build_info_upload` (the same gating the chunked submit
     * uses), so a build with deps/timings exercises the second PUT too.
     */
    private fun handleRegisterSingle(exchange: HttpExchange, body: ByteArray) {
        lastRegistrationBody = body
        val req = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }
        val result = JSONObject().apply {
            put("build_id", buildId)
            put("endpoint", "$baseUrl/single-put/artefact")
            if (req.optBoolean("request_build_info_upload", false)) {
                put("build_info_upload_endpoint", "$baseUrl/single-put/build-info")
            }
        }
        sendJsonResult(exchange, 200, result)
    }

    /** Presigned single PUT store: captures the upload ZIP (or build-info
     *  bundle) the converged single-PUT flow uploads, keyed by trailing path. */
    private fun handleSinglePut(exchange: HttpExchange, path: String, body: ByteArray) {
        val key = path.removePrefix("/single-put/")
        singlePutStore[key] = body
        sendString(exchange, 200, "")
    }

    /**
     * Presigned-symbol metadata POST: `POST /apps/<token>/symbols`. Mirrors
     * the appserver's symbol-registration response that both the real CLI
     * (`upload/presigned.rs`) and the Kotlin fallback ([SymbolUploader])
     * consume:
     *
     *  - Captures the metadata body (`{uuid, version, build, hash, transform?}`)
     *    for assertions.
     *  - Normally signs a presigned PUT URL by returning a TOP-LEVEL
     *    `{"code": 0, "endpoint": "<baseUrl>/symbol-put/blob"}` (NOT wrapped in
     *    the `{result: ...}` envelope — the symbol protocol reads top-level
     *    fields), so the client's follow-up PUT lands in [handleSymbolPut].
     *  - When [symbolAlreadyExists] is set, replies with the appserver's real
     *    duplicate envelope — `{"ok": false, "error": {"type":
     *    "DuplicateSymbolsFoundError", "code": 16004}}` — so the client
     *    short-circuits and performs no PUT.
     */
    private fun handleSymbolMetadata(exchange: HttpExchange, body: ByteArray) {
        symbolMetadataBodies.add(body)
        val response = JSONObject().apply {
            if (symbolAlreadyExists) {
                // The appserver's real duplicate reply: the code NESTED in its error envelope.
                put("ok", false)
                put(
                    "error",
                    JSONObject().apply {
                        put("type", "DuplicateSymbolsFoundError")
                        put("message", "A symbol file with the same identifier already exists")
                        put("code", 16004)
                    },
                )
            } else {
                put("code", 0)
                put("endpoint", "$baseUrl/symbol-put/blob")
            }
        }
        // Top-level (un-wrapped) JSON — the presigned-symbol protocol reads
        // `code` / `endpoint` at the root, not under `result`.
        val bytes = response.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** Presigned-symbol PUT store: captures the symbol zip the two-stage
     *  presigned flow uploads after the metadata POST signs a URL. */
    private fun handleSymbolPut(exchange: HttpExchange, body: ByteArray) {
        symbolPutBody = body
        sendString(exchange, 200, "")
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

    private fun handleAuxBlobPut(exchange: HttpExchange, path: String, body: ByteArray) {
        val label = path.removePrefix("/aux-blob-store/")
        auxBlobStore[label] = body
        sendString(exchange, 200, "")
    }

    private fun handleSubmitChunked(exchange: HttpExchange, body: ByteArray) {
        // Body is recorded for assertion; we additionally inspect
        // it here to mirror the appserver's gating of the
        // auxiliary-blob presigned URLs on the client's
        // `request_*_upload` flags. Without the gate the chunked
        // path would receive URLs it never asked for and treat the
        // unexpected presence as a server bug.
        val req = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }
        val result = JSONObject().apply {
            put("build_id", buildId)
            if (req.optBoolean("request_dependencies_upload", false)) {
                put("dependencies_upload_endpoint", "$baseUrl/aux-blob-store/dependencies")
            }
            if (req.optBoolean("request_timings_upload", false)) {
                put("timings_upload_endpoint", "$baseUrl/aux-blob-store/timings")
            }
        }
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
