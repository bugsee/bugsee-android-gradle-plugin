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
import java.io.IOException
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

    // S3 multipart requires every non-terminal part to be ≥ 5 MiB. We
    // surface a misconfigured server early rather than uploading chunks
    // the stitch phase will reject.
    private const val S3_MIN_PART_BYTES = 5 * 1024 * 1024

    // Per-chunk retry parameters. Transient S3 5xx or IO hiccups should
    // not kill the whole chunked path — especially once we've already
    // uploaded several MiB of a multi-GiB archive.
    private const val CHUNK_PUT_MAX_ATTEMPTS = 3
    private const val CHUNK_PUT_BACKOFF_MS = 500L

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
     * After the chunked submit lands the build doc, this function
     * also follows up with best-effort PUTs of the optional
     * dependencies and timings detail blobs (the gz files produced
     * by the plugin's deps + timings collection passes). The server
     * returns presigned URLs for each in the submit response, gated
     * by the corresponding `request_*_upload` flag inside `metadata`.
     *
     * @param metadata JSON describing the build (uuid, package_id, vcs
     *                 fields, etc.). `chunks` is injected by this
     *                 function; callers should not include it.
     * @param dependenciesGzFile gzipped deps blob to PUT after submit
     *                 succeeds, or `null` to skip. The submit
     *                 response's `dependencies_upload_endpoint`
     *                 field is consulted only when this is non-null.
     * @param timingsGzFile gzipped timings blob, same posture as
     *                 [dependenciesGzFile].
     */
    fun upload(
        uploadZip: File,
        metadata: JSONObject,
        appToken: String,
        endpoint: String,
        logger: Logger,
        debug: Boolean,
        dependenciesGzFile: File? = null,
        timingsGzFile: File? = null,
    ): String {
        val http = newHttpClient()
        try {
            val options = fetchChunkOptions(http, endpoint, appToken)
            val chunkSize = options.getInt("chunk_size")
            val maxChunks = options.getInt("max_chunks")
            // Guard against a misconfigured server that hands back
            // nonsense. `chunk_size=0` would spin forever writing
            // zero-byte chunks; a `max_chunks` of zero or negative is
            // absurd. S3 multipart additionally requires ≥ 5 MiB for
            // non-terminal parts — surface that early.
            require(chunkSize >= S3_MIN_PART_BYTES && chunkSize <= Int.MAX_VALUE / 2) {
                "invalid chunk_size from server: $chunkSize (must be in [$S3_MIN_PART_BYTES, ${Int.MAX_VALUE / 2}])"
            }
            require(maxChunks in 1..100_000) {
                "invalid max_chunks from server: $maxChunks (must be in [1, 100000])"
            }
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
                // Iterate positions, not hashes. An archive with
                // duplicate-content chunks (zero-padded regions,
                // repeated blocks) has the same SHA-1 at multiple
                // indices; `missing` lists each unique hash once and
                // the server's UploadPartCopy stitches by hash, so
                // one PUT per unique missing hash is enough — but
                // we must key on the FIRST index for each hash, which
                // `indexOf` gave us previously. The bug was that we
                // iterated `missing` without deduping the iteration
                // side, which ran the PUT once per `missing` entry
                // but never visited later positions that share the
                // hash. Now we iterate by index, only PUT the first
                // occurrence of each hash, and skip positions whose
                // hash is already uploaded.
                val missingSet = missing.toHashSet()
                val uploaded = hashSetOf<String>()
                for (index in chunkHashes.indices) {
                    val sha1 = chunkHashes[index]
                    if (sha1 !in missingSet) continue
                    if (!uploaded.add(sha1)) continue
                    val url = uploadUrls.getString(sha1)
                    uploadChunk(http, uploadZip, index, chunkSize, url, reusable)
                }
            }

            val submitResult = submitChunked(http, endpoint, appToken, metadata, chunkHashes)
            if (debug) logger.warn("Bugsee: chunked upload complete build_id=${submitResult.buildId}")

            // Follow-up auxiliary blob PUTs. Mirror the single-PUT
            // path's contract: gated on the caller having supplied a
            // gz file AND the server having returned a corresponding
            // presigned URL (which itself is gated on the
            // `request_*_upload` flag inside `metadata`, decided by
            // the caller). Reuses the same `http` client so the TLS
            // connection pool is shared with the chunk PUTs above.
            // Each PUT is best-effort and independent — a transient
            // failure here does not unwind the (already-committed)
            // chunked artefact submission.
            BundleUploader.uploadAuxiliaryBlob(
                http, "dependencies", submitResult.depsUploadEndpoint,
                dependenciesGzFile, logger, debug,
            )
            BundleUploader.uploadAuxiliaryBlob(
                http, "timings", submitResult.timingsUploadEndpoint,
                timingsGzFile, logger, debug,
            )

            return submitResult.buildId
        } finally {
            http.close()
        }
    }

    /** Parsed `/builds/chunked` response — `build_id` plus any
     *  presigned URLs for the optional auxiliary blob PUTs. The URL
     *  fields are empty strings when the corresponding
     *  `request_*_upload` flag wasn't set in the submit body (server
     *  contract).
     */
    private data class ChunkedSubmitResult(
        val buildId: String,
        val depsUploadEndpoint: String,
        val timingsUploadEndpoint: String,
    )

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
        // Read once, retry the PUT. Bounded retry on transient
        // network/5xx so a single flaky chunk doesn't force the
        // caller to fall back to single-PUT and re-upload the whole
        // multi-GiB archive.
        val wanted: Int
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val remaining = file.length() - offset
            wanted = minOf(chunkSize.toLong(), remaining).toInt()
            raf.readFully(reusable, 0, wanted)
        }
        var lastError: Exception? = null
        for (attempt in 1..CHUNK_PUT_MAX_ATTEMPTS) {
            try {
                val put = HttpPut(presignedUrl)
                put.entity = ByteArrayEntity(reusable, 0, wanted)
                // S3 signs the presigned URL with an explicit
                // `application/octet-stream` Content-Type (see
                // chunks.service.js#checkChunks). Without this
                // header the request body is unsigned-equivalent
                // for the CT line and S3 403s with
                // "SignatureDoesNotMatch". Default HttpClient
                // omits Content-Type for ByteArrayEntity.
                put.setHeader("Content-Type", "application/octet-stream")
                http.execute(put).use { response ->
                    val status = response.statusLine.statusCode
                    if (status in 200..299) return
                    val body = response.entity?.let { EntityUtils.toString(it) } ?: ""
                    // 4xx is permanent (auth, expired URL, bad signature);
                    // retrying won't help and just wastes bytes.
                    if (status in 400..499) {
                        throw RuntimeException("chunk PUT failed (status=$status): $body")
                    }
                    throw IOException("chunk PUT status=$status: $body")
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt < CHUNK_PUT_MAX_ATTEMPTS) {
                    Thread.sleep(CHUNK_PUT_BACKOFF_MS * attempt)
                }
            }
        }
        throw RuntimeException("chunk PUT failed after $CHUNK_PUT_MAX_ATTEMPTS attempts", lastError)
    }

    private fun submitChunked(http: CloseableHttpClient,
                              endpoint: String, appToken: String,
                              metadata: JSONObject, hashes: List<String>): ChunkedSubmitResult {
        val post = HttpPost(ApiEndpoint.buildsUrl(endpoint, appToken, "/chunked"))
        post.setHeader("Content-Type", "application/json")
        // Clone so we don't mutate the caller's object. The doc on
        // `upload()` promises the caller can reuse the metadata; an
        // inlined `put("chunks", ...)` would silently stash the hash
        // list in their object and surface later as a stale field on
        // a fallback / retry.
        val body = JSONObject(metadata.toString()).apply {
            put("chunks", JSONArray(hashes))
        }
        post.entity = StringEntity(body.toString(), "UTF-8")
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
            // Optional auxiliary URLs. Empty when the caller didn't
            // ask for them (via `request_*_upload` flags inside
            // `metadata`); the auxiliary-PUT helper treats an empty
            // URL paired with a non-null gz file as a server-side
            // bug and warns. Either way, we don't fail the submit
            // on a missing URL — the artefact stitch has already
            // landed and the build doc exists; aux blobs are
            // best-effort enrichment.
            return ChunkedSubmitResult(
                buildId = buildId,
                depsUploadEndpoint = payload.optString("dependencies_upload_endpoint", ""),
                timingsUploadEndpoint = payload.optString("timings_upload_endpoint", ""),
            )
        }
    }
}
