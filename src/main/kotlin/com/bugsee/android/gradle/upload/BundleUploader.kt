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
 * Two-stage uploader for app bundles (AAB/APK) to the Bugsee backend.
 *
 * Mirrors [SymbolUploader] but targets the `/builds` endpoint
 * instead of `/symbols`.
 */
internal object BundleUploader {

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val SOCKET_TIMEOUT_MS = 300_000

    /**
     * Two-stage upload:
     * 1. POST JSON metadata to get a presigned URL
     * 2. PUT the file to the presigned URL
     *
     * @param file The AAB or APK file to upload
     * @param json JSON metadata string
     * @param appToken The Bugsee app token
     * @param endpoint The Bugsee API endpoint
     * @param logger Gradle logger
     * @param debug Whether debug logging is enabled
     */
    fun uploadData(
        file: File,
        json: String,
        appToken: String,
        endpoint: String,
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
            if (debug) logger.warn("Bugsee: Bundle upload step 2. Response: $contentText")

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
            if (presignedEndpoint.isEmpty()) {
                val error = responseBody.optJSONObject("error") ?: payload.optJSONObject("error")
                if (error != null) {
                    val errorType = error.optString("type", "")
                    if (errorType == "ApplicationNotFoundError") {
                        throw RuntimeException("Bugsee: App token is invalid: $appToken")
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
