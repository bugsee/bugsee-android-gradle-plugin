package com.bugsee.android.gradle.upload

import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.entity.ByteArrayEntity
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.gradle.api.logging.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Phase 6 chunked-upload client. Splits the normalised upload zip into
 * fixed-size chunks, dedup-checks them against the server, uploads only
 * the missing ones via presigned PUT URLs, then POSTs the stitched-build
 * descriptor.
 *
 * Any failure at any step throws — the caller is expected to catch and
 * fall back to the single-PUT path.
 */
internal object ChunkedBundleUploader {

    private const val HASH_CHUNK_BYTES = 64 * 1024

    // Mirrors BundleUploader's HTTP configuration so the chunked path
    // behaves consistently under slow or flaky CI links. Without these
    // caps, HttpClient defaults to "wait forever" — a hanging chunk PUT
    // would freeze the whole Gradle task.
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val SOCKET_TIMEOUT_MS  = 300_000
    private const val USER_AGENT         = "bugsee-gradle-plugin/chunked-upload"

    private fun newHttpClient(): CloseableHttpClient {
        val cfg = RequestConfig.custom()
            .setConnectTimeout(CONNECT_TIMEOUT_MS)
            .setSocketTimeout(SOCKET_TIMEOUT_MS)
            .setConnectionRequestTimeout(CONNECT_TIMEOUT_MS)
            .build()
        return HttpClients.custom()
            .setDefaultRequestConfig(cfg)
            .setUserAgent(USER_AGENT)
            .build()
    }

    /**
     * Upload `uploadZip` via the chunked protocol. Returns the server's
     * `build_id` for logging.
     *
     * @param metadata JSON describing the build (uuid, package_id, vcs
     *                 fields, etc.). `chunks` is injected by this
     *                 function; callers should not include it.
     */
    fun upload(
        uploadZip: File,
        metadata: JSONObject,
        appToken: String,
        endpoint: String,
        logger: Logger,
        debug: Boolean,
    ): String {
        val http = newHttpClient()
        try {
            val options = fetchChunkOptions(http, endpoint, appToken)
            val chunkSize = options.getInt("chunk_size")
            val maxChunks = options.getInt("max_chunks")
            if (debug) logger.warn("Bugsee: chunked upload — chunk_size=$chunkSize max_chunks=$maxChunks")

            val chunkHashes = computeChunkHashes(uploadZip, chunkSize)
            if (chunkHashes.size > maxChunks) {
                throw RuntimeException(
                    "Archive too large for chunked upload: ${chunkHashes.size} chunks > server max $maxChunks"
                )
            }
            if (debug) logger.warn("Bugsee: computed ${chunkHashes.size} chunks")

            val check = checkChunks(http, endpoint, appToken, chunkHashes)
            val missing = check.getJSONArray("missing").let { arr ->
                List(arr.length()) { arr.getString(it) }
            }
            val uploadUrls = check.getJSONObject("upload_urls")

            if (debug) logger.warn("Bugsee: ${missing.size} / ${chunkHashes.size} chunks need upload")

            if (missing.isNotEmpty()) {
                // Reuse a single heap buffer across every chunk PUT to
                // keep GC pressure low on large archives. ByteArrayEntity
                // (buf, 0, len) copies internally so reuse is safe.
                val reusable = ByteArray(chunkSize)
                for (sha1 in missing) {
                    val url = uploadUrls.getString(sha1)
                    val index = chunkHashes.indexOf(sha1)
                    uploadChunk(http, uploadZip, index, chunkSize, url, reusable)
                }
            }

            val buildId = submitChunked(http, endpoint, appToken, metadata, chunkHashes)
            if (debug) logger.warn("Bugsee: chunked upload complete build_id=$buildId")
            return buildId
        } finally {
            http.close()
        }
    }

    // ── Chunk hashing ─────────────────────────────────────────────

    /**
     * Return SHA-1 hex digests for each chunk of `file`. Reads each
     * `chunkSize`-byte block sequentially with 64 KiB buffers so memory
     * stays bounded regardless of archive size.
     */
    internal fun computeChunkHashes(file: File, chunkSize: Int): List<String> {
        val result = mutableListOf<String>()
        FileInputStream(file).use { fis ->
            val md = MessageDigest.getInstance("SHA-1")
            val buf = ByteArray(HASH_CHUNK_BYTES)
            var chunkBytesRead = 0
            while (true) {
                val wanted = minOf(buf.size, chunkSize - chunkBytesRead)
                if (wanted <= 0) {
                    // boundary — emit hash and reset
                    result.add(hex(md.digest()))
                    md.reset()
                    chunkBytesRead = 0
                    continue
                }
                val n = fis.read(buf, 0, wanted)
                if (n < 0) break
                md.update(buf, 0, n)
                chunkBytesRead += n
                if (chunkBytesRead == chunkSize) {
                    result.add(hex(md.digest()))
                    md.reset()
                    chunkBytesRead = 0
                }
            }
            if (chunkBytesRead > 0) {
                result.add(hex(md.digest()))
            }
        }
        return result
    }

    private fun hex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef".toCharArray()
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2]     = hexChars[v ushr 4]
            out[i * 2 + 1] = hexChars[v and 0x0f]
        }
        return String(out)
    }

    // ── HTTP calls ───────────────────────────────────────────────

    private fun fetchChunkOptions(http: CloseableHttpClient,
                                  endpoint: String, appToken: String): JSONObject {
        val get = HttpGet(ApiEndpoint.buildsUrl(endpoint, appToken, "/chunk-options"))
        http.execute(get).use { response ->
            val body = response.entity?.let { EntityUtils.toString(it) } ?: ""
            if (response.statusLine.statusCode !in 200..299) {
                throw RuntimeException("chunk-options failed: ${response.statusLine} $body")
            }
            return ApiEndpoint.unwrapResult(JSONObject(body))
        }
    }

    private fun checkChunks(http: CloseableHttpClient,
                            endpoint: String, appToken: String,
                            hashes: List<String>): JSONObject {
        val post = HttpPost(ApiEndpoint.buildsUrl(endpoint, appToken, "/chunks/check"))
        post.setHeader("Content-Type", "application/json")
        val body = JSONObject().apply {
            put("sha1_list", JSONArray(hashes))
        }.toString()
        post.entity = StringEntity(body, "UTF-8")
        http.execute(post).use { response ->
            val responseBody = response.entity?.let { EntityUtils.toString(it) } ?: ""
            if (response.statusLine.statusCode !in 200..299) {
                throw RuntimeException("chunks/check failed: ${response.statusLine} $responseBody")
            }
            return ApiEndpoint.unwrapResult(JSONObject(responseBody))
        }
    }

    /**
     * Upload one chunk to the presigned PUT URL. Reads exactly
     * `chunkSize` bytes (or fewer on the final chunk) starting at
     * `index * chunkSize` into the caller-supplied `reusable` buffer.
     * ByteArrayEntity copies the offset+length slice so the same
     * buffer can be reused across chunks.
     */
    private fun uploadChunk(http: CloseableHttpClient,
                            file: File, index: Int, chunkSize: Int,
                            presignedUrl: String, reusable: ByteArray) {
        val offset = index.toLong() * chunkSize.toLong()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val remaining = file.length() - offset
            val wanted = minOf(chunkSize.toLong(), remaining).toInt()
            raf.readFully(reusable, 0, wanted)
            val put = HttpPut(presignedUrl)
            put.entity = ByteArrayEntity(reusable, 0, wanted)
            http.execute(put).use { response ->
                val status = response.statusLine.statusCode
                if (status !in 200..299) {
                    val body = response.entity?.let { EntityUtils.toString(it) } ?: ""
                    throw RuntimeException("chunk PUT failed (status=$status): $body")
                }
            }
        }
    }

    private fun submitChunked(http: CloseableHttpClient,
                              endpoint: String, appToken: String,
                              metadata: JSONObject, hashes: List<String>): String {
        val post = HttpPost(ApiEndpoint.buildsUrl(endpoint, appToken, "/chunked"))
        post.setHeader("Content-Type", "application/json")
        // Mutate the caller's JSONObject rather than round-tripping
        // through string — saves a full-document parse+serialise.
        metadata.put("chunks", JSONArray(hashes))
        post.entity = StringEntity(metadata.toString(), "UTF-8")
        http.execute(post).use { response ->
            val responseBody = response.entity?.let { EntityUtils.toString(it) } ?: ""
            if (response.statusLine.statusCode !in 200..299) {
                throw RuntimeException("/builds/chunked failed: ${response.statusLine} $responseBody")
            }
            val payload = ApiEndpoint.unwrapResult(JSONObject(responseBody))
            val buildId = if (payload.has("build_id")) payload.optString("build_id", "") else ""
            if (buildId.isBlank()) {
                // A 2xx without a build_id means the server accepted the
                // submission but didn't tell us what to key on — the
                // worker pipeline is effectively off the rails. Fail
                // loud so the caller falls back to single-PUT.
                throw RuntimeException("/builds/chunked returned 2xx without build_id: $responseBody")
            }
            return buildId
        }
    }
}
