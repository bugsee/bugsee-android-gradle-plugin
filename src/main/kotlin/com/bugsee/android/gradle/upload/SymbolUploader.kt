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

internal object SymbolUploader {

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val SOCKET_TIMEOUT_MS = 120_000

    /**
     * Two-stage upload:
     * 1. POST JSON metadata to get a presigned URL
     * 2. PUT the file to the presigned URL
     *
     * @param file The file to upload
     * @param json JSON metadata string
     * @param appToken The Bugsee app token
     * @param endpoint The Bugsee API endpoint
     * @param logger Gradle logger
     * @param debug Whether debug logging is enabled
     * @param uploaderTag Value for the `X-Bugsee-Uploader` request header on
     *   the metadata POST. Defaults to `"kotlin"` for direct invocations.
     *   When this uploader runs as a fallback after `bugsee-cli` failed
     *   structurally, the caller passes `"kotlin-fallback-cli-<reason>"`
     *   (where `<reason>` matches `CliUploadResult.fallbackReason`) so the
     *   backend can count both paths without touching customer code. See
     *   [CliUploader] and the dual-path rollout plan.
     *
     *   The header is NOT added to the presigned-URL PUT — that goes to S3,
     *   whose signature is bound to a specific header set; adding extras
     *   there would trigger `SignatureDoesNotMatch`.
     * @return `true` if the upload succeeded or the symbol already exists on the server
     */
    fun uploadData(
        file: File,
        json: String,
        appToken: String,
        endpoint: String,
        logger: Logger,
        debug: Boolean,
        uploaderTag: String = "kotlin",
    ): Boolean {
        if (debug) logger.warn("Bugsee: Starting upload. Body: $json")

        // 1. Create request, get presigned URL
        val httpPost = HttpPost("$endpoint/apps/$appToken/symbols")
        // Encode the JSON body as UTF-8 explicitly: the single-arg StringEntity
        // defaults to ISO-8859-1, which would mangle any non-ASCII symbol
        // metadata on the wire. Match the UTF-8 the CLI and chunked transport use.
        val body = StringEntity(json, "UTF-8")
        body.contentType = BasicHeader(HTTP.CONTENT_TYPE, "application/json; charset=utf-8")
        httpPost.entity = body
        httpPost.addHeader("X-Bugsee-Uploader", uploaderTag)

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

        return httpClient.use { client ->
            val response = client.execute(httpPost)
            val statusCode = response.statusLine.statusCode

            // Accept the full 2xx range. S3 and CDN proxies routinely
            // normalize successful responses to 201 Created or 204 No
            // Content; the prior strict `!= 200` check made those
            // appear as failed uploads even though the symbol was
            // accepted server-side. `BundleUploader.uploadData` (the
            // sibling uploader on the chunked path) already used the
            // 200..299 range; this aligns the two.
            if (statusCode !in 200..299) {
                logger.warn(
                    "Bugsee upload failed (status=$statusCode): " +
                        EntityUtils.toString(response.entity, "utf-8"),
                )
                return@use false
            }

            val resEntity = response.entity
            if (resEntity == null) {
                logger.warn("Bugsee upload failed: no response from server")
                return@use false
            }

            val contentText = EntityUtils.toString(resEntity, "utf-8")
            if (debug) logger.warn("Bugsee: Upload step 2. Response: $contentText")

            // Parse the body defensively. The 2xx range covers
            // 201 Created / 202 Accepted / 204 No Content responses
            // that CDN proxies and some appserver deployments
            // return with non-JSON bodies (text/plain "Accepted",
            // HTML error pages from intermediate proxies, empty
            // body, etc.). The prior strict `JSONObject(contentText)`
            // call threw `JSONException` on any of those, escaping
            // through `httpClient.use { }` and crashing the Gradle
            // task with a stacktrace instead of cleanly logging
            // a warn and returning false.
            //
            // The contract this guards: status-range relaxation
            // (Item 1) widened the set of "successful POST"
            // responses; the body parser must keep pace or the
            // widened range becomes a crash hazard.
            val responseBody = try {
                JSONObject(contentText)
            } catch (e: Exception) {
                logger.warn(
                    "Bugsee upload failed: response body is not valid JSON " +
                        "(status=$statusCode): ${e.message}. Body preview: " +
                        contentText.take(200),
                )
                return@use false
            }

            // Check for SymbolAlreadyExistsError
            if (responseBody.optInt("code") == 16004) {
                if (debug) logger.warn("Bugsee: Got SymbolAlreadyExistsError from server")
                return@use true
            }

            // Check for presigned endpoint URL
            val presignedEndpoint = responseBody.optString("endpoint", "")
            if (presignedEndpoint.isEmpty()) {
                val error = responseBody.optJSONObject("error")
                if (error != null) {
                    val errorType = error.optString("type", "")
                    if (errorType == "ApplicationNotFoundError") {
                        logger.warn("App token is invalid: $appToken")
                    } else {
                        logger.warn("Bugsee upload failed with error: $error")
                    }
                } else {
                    logger.warn("Bugsee upload failed: null endpoint")
                }
                return@use false
            }

            // 2. Upload to presigned URL
            if (debug) logger.warn("Bugsee: Uploading to endpoint: $presignedEndpoint")
            val httpPut = HttpPut(presignedEndpoint)
            httpPut.entity = FileEntity(file)
            val putResponse = client.execute(httpPut)

            // Same 2xx-range relaxation as the POST step above —
            // presigned-URL PUTs land on S3 (or whichever CDN the
            // appserver minted), and those return 200/201/204
            // interchangeably depending on multipart vs single-PUT
            // and storage-class settings.
            val putStatus = putResponse.statusLine.statusCode
            if (putStatus !in 200..299) {
                logger.warn(
                    "Bugsee upload failed (status=$putStatus): " +
                        EntityUtils.toString(putResponse.entity, "utf-8"),
                )
                return@use false
            }

            if (debug) logger.warn("Bugsee: Upload complete.")
            true
        }
    }
}
