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
     * @return `true` if the upload succeeded or the symbol already exists on the server
     */
    fun uploadData(
        file: File,
        json: String,
        appToken: String,
        endpoint: String,
        logger: Logger,
        debug: Boolean
    ): Boolean {
        if (debug) logger.warn("Bugsee: Starting upload. Body: $json")

        // 1. Create request, get presigned URL
        val httpPost = HttpPost("$endpoint/apps/$appToken/symbols")
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

        return httpClient.use { client ->
            val response = client.execute(httpPost)
            val statusCode = response.statusLine.statusCode

            if (statusCode != 200) {
                logger.warn("Bugsee upload failed: ${EntityUtils.toString(response.entity, "utf-8")}")
                return@use false
            }

            val resEntity = response.entity
            if (resEntity == null) {
                logger.warn("Bugsee upload failed: no response from server")
                return@use false
            }

            val contentText = EntityUtils.toString(resEntity, "utf-8")
            if (debug) logger.warn("Bugsee: Upload step 2. Response: $contentText")

            val responseBody = JSONObject(contentText)

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

            if (putResponse.statusLine.statusCode != 200) {
                logger.warn("Bugsee upload failed: ${EntityUtils.toString(putResponse.entity, "utf-8")}")
                return@use false
            }

            if (debug) logger.warn("Bugsee: Upload complete.")
            true
        }
    }
}
