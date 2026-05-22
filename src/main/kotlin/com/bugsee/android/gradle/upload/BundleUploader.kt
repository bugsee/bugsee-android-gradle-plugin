package com.bugsee.android.gradle.upload

import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.conn.ssl.DefaultHostnameVerifier
import org.apache.http.entity.FileEntity
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.client.StandardHttpRequestRetryHandler
import org.apache.http.message.BasicHeader
import org.apache.http.protocol.HTTP
import org.apache.http.util.EntityUtils
import org.gradle.api.logging.Logger
import org.json.JSONObject
import java.io.File

/**
 * Uploader for the Bugsee `/builds` endpoint. Two flows share this
 * code path:
 *
 *   - **build-info only** (default): one POST with metadata. Server
 *     creates the record at `size_analysis_status='unavailable'` and
 *     returns just the `build_id` — no presigned URL, no PUT.
 *   - **build-info + size-analysis**: same POST, but with
 *     `request_artifact_upload: true` in the body. Server signs a PUT
 *     URL and we ship the artefact bytes. Status starts at
 *     `'uploading'` and the worker promotes it once the artefact lands.
 *
 * Mirrors [SymbolUploader] but targets the `/builds` endpoint
 * instead of `/symbols`.
 */
internal object BundleUploader {

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val SOCKET_TIMEOUT_MS = 300_000

    /**
     * Log-safe rendering of the app token. Full tokens grant write
     * access to a project's builds so they must not land verbatim in
     * CI logs / exception messages / bug reports.
     */
    private fun maskAppToken(token: String): String =
        if (token.length <= 8) "****" else "${token.take(4)}…${token.takeLast(4)}"

    /**
     * Strip the query string from a presigned S3 URL so the
     * signature / token / expiry parameters never land in CI logs.
     * The path (bucket + key) is harmless on its own — useful for
     * correlating which artefact a log line refers to without
     * exposing the 7-day-valid PUT credential. Returns the input
     * verbatim when there's no `?` separator.
     */
    private fun redactPresignedUrl(url: String): String {
        val q = url.indexOf('?')
        return if (q >= 0) url.substring(0, q) + "?…<redacted>" else url
    }

    /**
     * Log-safe projection of the createBuild JSON response. The full
     * response carries two presigned PUT URLs (`endpoint`,
     * `dependencies_upload_endpoint`) that grant anonymous arbitrary-
     * write access to known S3 keys for 7 days; logging them verbatim
     * is a leak vector via CI artifact stores / Slack / SaaS log
     * aggregators. Surface only what's useful for debugging: build_id,
     * status, presence flags for each presigned URL.
     */
    private fun redactedResponseSummary(payload: org.json.JSONObject): String =
        org.json.JSONObject().apply {
            put("build_id", payload.optString("build_id", ""))
            put("size_analysis_status", payload.optString("size_analysis_status", ""))
            put("has_artifact_endpoint", payload.optString("endpoint", "").isNotEmpty())
            put("has_dependencies_endpoint",
                payload.optString("dependencies_upload_endpoint", "").isNotEmpty())
        }.toString()

    /**
     * POST metadata to `/builds`, optionally followed by a PUT of the
     * artefact bytes when the server signed a presigned URL.
     *
     * The metadata POST runs whenever this method is invoked. The PUT
     * is conditional: when [requestArtifactUpload] is `true` the
     * server returns an `endpoint` URL and we stream [file] there;
     * when `false`, the response carries no `endpoint` and the
     * request is metadata-only (build-info path).
     *
     * @param file The AAB or APK file to upload (only consumed when
     *             the response includes a presigned URL).
     * @param json JSON metadata string. The caller is responsible for
     *             setting `request_artifact_upload` inside the body
     *             to match [requestArtifactUpload].
     * @param appToken The Bugsee app token.
     * @param endpoint The Bugsee API endpoint base URL.
     * @param requestArtifactUpload Whether the body has asked the
     *             server for a presigned PUT URL. When `false` the
     *             absence of `endpoint` in the response is success
     *             (build-info-only). When `true` it's an error.
     * @param logger Gradle logger.
     * @param debug Whether debug logging is enabled.
     */
    fun uploadData(
        file: File,
        json: String,
        appToken: String,
        endpoint: String,
        requestArtifactUpload: Boolean,
        logger: Logger,
        debug: Boolean,
        dependenciesGzFile: File? = null,
        timingsGzFile: File? = null,
    ) {
        if (debug) logger.warn("Bugsee: Starting bundle upload. Body: $json")

        val httpPost = HttpPost(ApiEndpoint.buildsUrl(endpoint, appToken))
        val body = StringEntity(json)
        body.contentType = BasicHeader(HTTP.CONTENT_TYPE, "application/json")
        httpPost.entity = body

        val requestConfig = RequestConfig.custom()
            .setConnectTimeout(CONNECT_TIMEOUT_MS)
            .setSocketTimeout(SOCKET_TIMEOUT_MS)
            .setConnectionRequestTimeout(CONNECT_TIMEOUT_MS)
            .build()

        val httpClient = HttpClients.custom()
            .setDefaultRequestConfig(requestConfig)
            .setSSLHostnameVerifier(DefaultHostnameVerifier(null))
            .setRetryHandler(StandardHttpRequestRetryHandler())
            .build()

        httpClient.use { client ->
            val response = client.execute(httpPost)
            val statusCode = response.statusLine.statusCode

            // 2xx is success; some reverse proxies normalise to 201/204.
            // Anything outside that is a hard failure — surface it rather
            // than quietly returning and letting the Gradle task report
            // green while the backend never saw the build.
            if (statusCode !in 200..299) {
                val body = EntityUtils.toString(response.entity, "utf-8")
                throw RuntimeException("Bugsee bundle upload step 1 failed (status=$statusCode): $body")
            }

            val resEntity = response.entity
                ?: throw RuntimeException("Bugsee bundle upload step 1: no response body")

            val contentText = EntityUtils.toString(resEntity, "utf-8")
            // NEVER log `contentText` verbatim — the response contains
            // presigned PUT URLs that grant 7-day arbitrary-write
            // access to known S3 keys. See `redactedResponseSummary`.

            // Server may reply with XML (S3 or CDN) or HTML error page —
            // catch the parse failure so the user sees a meaningful
            // message instead of a stack trace.
            val responseBody = try {
                JSONObject(contentText)
            } catch (e: Exception) {
                throw RuntimeException("Bugsee bundle upload step 1: non-JSON response: ${contentText.take(200)}")
            }
            val payload = ApiEndpoint.unwrapResult(responseBody)
            if (debug) logger.warn(
                "Bugsee: Bundle upload response (redacted): ${redactedResponseSummary(payload)}"
            )

            val presignedEndpoint = payload.optString("endpoint", "")

            // The server includes a second presigned URL when the
            // metadata POST carried `request_dependencies_upload: true`.
            // The two URLs are independent — each is sent only when
            // the caller asked for it, and a missing one is not a
            // hard error in itself (handled per-branch below).
            val depsPresignedEndpoint = payload.optString("dependencies_upload_endpoint", "")
            // A third independent presigned URL — the build-timings
            // detail blob. Same posture as the deps URL: sent only
            // when the POST body carried `request_timings_upload: true`,
            // PUT handled per-branch with its own best-effort wrap.
            val timingsPresignedEndpoint = payload.optString("timings_upload_endpoint", "")

            // Build-info-only path: no artefact `endpoint` is expected.
            // A deps-only upload still flows through here (no artefact
            // PUT, but possibly a deps PUT).
            if (!requestArtifactUpload) {
                if (presignedEndpoint.isNotEmpty()) {
                    // Server returned a URL we didn't ask for. Treat
                    // as a server bug rather than a hard failure —
                    // we don't have the artefact ready and shouldn't
                    // upload one we weren't planning to.
                    logger.warn(
                        "Bugsee: build-info upload received an unexpected presigned URL — ignoring."
                    )
                }
                if (debug) logger.warn("Bugsee: Build-info upload complete.")
                // Falls through to the deps PUT below.
            } else {
                // Size-analysis path: presigned URL is required.
                if (presignedEndpoint.isEmpty()) {
                    val error = responseBody.optJSONObject("error") ?: payload.optJSONObject("error")
                    if (error != null) {
                        val errorType = error.optString("type", "")
                        if (errorType == "ApplicationNotFoundError") {
                            throw RuntimeException(
                                "Bugsee: App token is invalid: ${maskAppToken(appToken)}"
                            )
                        }
                        throw RuntimeException("Bugsee bundle upload failed: $error")
                    }
                    throw RuntimeException("Bugsee bundle upload failed: server returned no endpoint")
                }

                // Upload to presigned URL. Log path-only so the
                // signature query string (the actual credential)
                // never lands in CI logs.
                //
                // Wrapped in its own try/catch — independent of the
                // deps/timings PUTs below. A transient artefact-PUT
                // failure must not skip the subsequent best-effort
                // PUTs (deps/timings), and the KDoc on this method
                // states the per-PUT branches are independent.
                try {
                    if (debug) logger.warn(
                        "Bugsee: Uploading bundle to endpoint: ${redactPresignedUrl(presignedEndpoint)}"
                    )
                    val httpPut = HttpPut(presignedEndpoint)
                    httpPut.entity = FileEntity(file)
                    val putResponse = client.execute(httpPut)

                    if (putResponse.statusLine.statusCode !in 200..299) {
                        val body = EntityUtils.toString(putResponse.entity, "utf-8")
                        logger.warn(
                            "Bugsee: bundle upload step 2 failed " +
                            "(status=${putResponse.statusLine.statusCode}): $body"
                        )
                    } else if (debug) {
                        logger.warn("Bugsee: Bundle upload complete.")
                    }
                } catch (e: Exception) {
                    logger.warn("Bugsee: bundle upload step 2 failed: ${e.message}")
                }
            }

            // Best-effort deps PUT — runs whether or not the artefact
            // PUT happened, but only when the caller provided a gz
            // blob to upload AND the server returned an endpoint for
            // it. Wrapped in its own try/catch so a transient deps
            // PUT failure cannot break the (successful) artefact PUT
            // or vice versa.
            if (dependenciesGzFile != null) {
                if (depsPresignedEndpoint.isEmpty()) {
                    logger.warn(
                        "Bugsee: dependencies blob ready but server did not return a presigned URL — skipping."
                    )
                } else {
                    try {
                        if (debug) logger.warn(
                            "Bugsee: Uploading dependencies blob to endpoint: " +
                            redactPresignedUrl(depsPresignedEndpoint)
                        )
                        // Content-Type matches the server-side sign
                        // (`application/octet-stream`) — anything
                        // else and SigV2 mismatches.
                        val depsPut = HttpPut(depsPresignedEndpoint)
                        val depsEntity = FileEntity(dependenciesGzFile)
                        depsEntity.contentType = BasicHeader(
                            HTTP.CONTENT_TYPE, "application/octet-stream"
                        )
                        depsPut.entity = depsEntity
                        val depsResp = client.execute(depsPut)
                        if (depsResp.statusLine.statusCode !in 200..299) {
                            val body = EntityUtils.toString(depsResp.entity, "utf-8")
                            logger.warn(
                                "Bugsee: dependencies upload failed " +
                                "(status=${depsResp.statusLine.statusCode}): $body"
                            )
                        } else if (debug) {
                            logger.warn("Bugsee: Dependencies upload complete.")
                        }
                    } catch (e: Exception) {
                        logger.warn("Bugsee: dependencies upload failed: ${e.message}")
                    }
                }
            }

            // Best-effort timings PUT — same posture as the deps PUT
            // above. Independent of artefact and deps PUTs: a
            // failure here cannot break either of those, and vice
            // versa. The inline timings summary is already in the
            // build doc (via `build_metadata.timings`); the detail
            // blob is what the viewer's Gantt-chart renderer
            // lazy-fetches.
            if (timingsGzFile != null) {
                if (timingsPresignedEndpoint.isEmpty()) {
                    logger.warn(
                        "Bugsee: timings blob ready but server did not return a presigned URL — skipping."
                    )
                } else {
                    try {
                        if (debug) logger.warn(
                            "Bugsee: Uploading timings blob to endpoint: " +
                            redactPresignedUrl(timingsPresignedEndpoint)
                        )
                        // Content-Type matches the server-side sign
                        // (`application/octet-stream`) — anything
                        // else and SigV2 mismatches.
                        val timingsPut = HttpPut(timingsPresignedEndpoint)
                        val timingsEntity = FileEntity(timingsGzFile)
                        timingsEntity.contentType = BasicHeader(
                            HTTP.CONTENT_TYPE, "application/octet-stream"
                        )
                        timingsPut.entity = timingsEntity
                        val timingsResp = client.execute(timingsPut)
                        if (timingsResp.statusLine.statusCode !in 200..299) {
                            val body = EntityUtils.toString(timingsResp.entity, "utf-8")
                            logger.warn(
                                "Bugsee: timings upload failed " +
                                "(status=${timingsResp.statusLine.statusCode}): $body"
                            )
                        } else if (debug) {
                            logger.warn("Bugsee: Timings upload complete.")
                        }
                    } catch (e: Exception) {
                        logger.warn("Bugsee: timings upload failed: ${e.message}")
                    }
                }
            }
        }
    }
}
