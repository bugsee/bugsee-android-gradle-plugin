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
        debug: Boolean
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
            if (debug) logger.warn("Bugsee: Bundle upload response: $contentText")

            // Server may reply with XML (S3 or CDN) or HTML error page —
            // catch the parse failure so the user sees a meaningful
            // message instead of a stack trace.
            val responseBody = try {
                JSONObject(contentText)
            } catch (e: Exception) {
                throw RuntimeException("Bugsee bundle upload step 1: non-JSON response: ${contentText.take(200)}")
            }
            val payload = ApiEndpoint.unwrapResult(responseBody)

            val presignedEndpoint = payload.optString("endpoint", "")

            // Build-info-only path: no `endpoint` is expected, the
            // POST already did everything we need. Confirm the
            // response carries the build_id and return — no PUT.
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
                return@use
            }

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

            // Upload to presigned URL
            if (debug) logger.warn("Bugsee: Uploading bundle to endpoint: $presignedEndpoint")
            val httpPut = HttpPut(presignedEndpoint)
            httpPut.entity = FileEntity(file)
            val putResponse = client.execute(httpPut)

            if (putResponse.statusLine.statusCode !in 200..299) {
                val body = EntityUtils.toString(putResponse.entity, "utf-8")
                throw RuntimeException(
                    "Bugsee bundle upload step 2 failed (status=${putResponse.statusLine.statusCode}): $body"
                )
            }

            if (debug) logger.warn("Bugsee: Bundle upload complete.")
        }
    }
}
