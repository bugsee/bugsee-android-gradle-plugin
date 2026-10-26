package com.bugsee.android.gradle.upload

import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.client.methods.HttpUriRequest
import org.apache.http.conn.ConnectTimeoutException
import org.apache.http.conn.ssl.DefaultHostnameVerifier
import org.apache.http.entity.FileEntity
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.message.BasicHeader
import org.apache.http.protocol.HTTP
import org.apache.http.util.EntityUtils
import org.gradle.api.logging.Logger
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

internal object SymbolUploader {

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val SOCKET_TIMEOUT_MS = 120_000

    /** Attempts per request (1 try + 2 retries); the backoff before retry N is `N * RETRY_BACKOFF_MS`. */
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_BACKOFF_MS = 500L
    private const val RETRY_AFTER_CAP_MS = 10_000L
    private const val MAX_RETRY_AFTER_SECONDS = 3_600L
    private const val RETRY_BUDGET_MS = 180_000L
    private val QUERY_IN_MESSAGE = Regex("\\?\\S*")

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
        retryPolicy: RetryPolicy = RetryPolicy(),
    ): Boolean = try {
        doUpload(file, json, appToken, endpoint, logger, debug, uploaderTag, retryPolicy)
    } catch (e: IOException) {
        // A cancelled build (Ctrl-C, daemon stop) must stay cancelled, not turn into a warning.
        if (isInterruption(e)) {
            Thread.currentThread().interrupt()
            throw e
        }
        warnTransportFailure(e, appToken, logger)
        false
    } catch (e: RuntimeException) {
        warnTransportFailure(e, appToken, logger)
        false
    }

    /**
     * A transport failure (connection refused, DNS, timeout, TLS, ...) must not fail the build: like an
     * HTTP-status failure it is a warning and the upload is skipped. The message is masked: it can carry
     * the request URL, and the app token is part of the metadata path.
     */
    private fun warnTransportFailure(e: Exception, appToken: String, logger: Logger) {
        logger.warn("Bugsee upload failed: ${e.javaClass.simpleName}: ${sanitize(e.message ?: "", appToken)}")
        // Full detail (stack trace, causes) for diagnosis with --info; it can hide a programming error.
        // Rendered to text and sanitized: passing the Throwable itself would print the raw message and
        // cause chain, bypassing the masking.
        logger.info("Bugsee upload failure details: ${sanitize(e.stackTraceToString(), appToken)}")
    }

    /**
     * Mask the token and drop any query string: a malformed presigned URL echoed in an exception message
     * carries its signature (a write credential) in the query.
     */
    private fun sanitize(text: String, appToken: String): String =
        text.replace(appToken, BundleUploader.maskAppToken(appToken)).replace(QUERY_IN_MESSAGE, "?<redacted>")

    /** True when the thread was interrupted or [e] says the request was cancelled (not a plain timeout). */
    private fun isInterruption(e: IOException): Boolean =
        Thread.currentThread().isInterrupted ||
            (e is InterruptedIOException && e !is SocketTimeoutException && e !is ConnectTimeoutException)

    /** Retry knobs; the defaults are production values, tests inject small ones. */
    internal class RetryPolicy(
        val backoffMs: Long = RETRY_BACKOFF_MS,
        val retryAfterCapMs: Long = RETRY_AFTER_CAP_MS,
        val budgetMs: Long = RETRY_BUDGET_MS,
    )

    /**
     * Run [request], retrying I/O errors and transient statuses ([isRetryable]) up to [MAX_ATTEMPTS]
     * times. Permanent statuses (4xx, notably 401/403/404) are returned at once. The wait before a retry
     * is the server's `Retry-After` (seconds, capped) when sent, else a linear backoff. A read timeout
     * ([SocketTimeoutException]) is never retried: the server already held the request for the full
     * socket timeout, so retrying would stall the build for minutes per upload. No retry starts once
     * [RetryPolicy.budgetMs] would be exceeded. The last attempt's reply is returned (or its I/O error
     * thrown) when the attempts run out. This loop owns all retries (the client has its own disabled),
     * so one upload sends at most [MAX_ATTEMPTS] requests. Both entities used here ([StringEntity],
     * [FileEntity]) are repeatable, so the request can be re-sent.
     */
    private fun executeWithRetry(
        client: CloseableHttpClient,
        request: HttpUriRequest,
        step: String,
        logger: Logger,
        policy: RetryPolicy,
    ): Reply {
        val started = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - started) / 1_000_000
        for (attempt in 1..MAX_ATTEMPTS) {
            val last = attempt == MAX_ATTEMPTS
            var waitMs = policy.backoffMs * attempt
            try {
                val reply = client.execute(request).use { response ->
                    val retryAfterMs = response.getFirstHeader("Retry-After")?.value?.trim()?.toLongOrNull()
                        ?.takeIf { it >= 0 }?.let { minOf(it.coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1000, policy.retryAfterCapMs) }
                    Reply(response.statusLine.statusCode, response.entity?.let { EntityUtils.toString(it, "utf-8") }, retryAfterMs)
                }
                if (!isRetryable(reply.status) || last) return reply
                waitMs = reply.retryAfterMs ?: waitMs
                if (elapsedMs() + waitMs >= policy.budgetMs) return reply
                logger.warn("Bugsee: $step returned status ${reply.status}, retrying (attempt $attempt of $MAX_ATTEMPTS)")
            } catch (e: IOException) {
                if (last || isInterruption(e) || e is SocketTimeoutException || elapsedMs() + waitMs >= policy.budgetMs) throw e
                logger.warn("Bugsee: $step failed (${e.javaClass.simpleName}), retrying (attempt $attempt of $MAX_ATTEMPTS)")
            }
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        error("unreachable")
    }

    private fun isRetryable(status: Int): Boolean = status == 429 || status in 500..599

    private class Reply(val status: Int, val body: String?, val retryAfterMs: Long? = null)

    private fun doUpload(
        file: File,
        json: String,
        appToken: String,
        endpoint: String,
        logger: Logger,
        debug: Boolean,
        uploaderTag: String,
        retryPolicy: RetryPolicy,
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
            .disableAutomaticRetries() // executeWithRetry owns retries; stacking both multiplied full-body PUTs
            .build()

        return httpClient.use { client ->
            val reply = executeWithRetry(client, httpPost, "metadata request", logger, retryPolicy)
            val statusCode = reply.status

            // Accept the full 2xx range. S3 and CDN proxies routinely
            // normalize successful responses to 201 Created or 204 No
            // Content; the prior strict `!= 200` check made those
            // appear as failed uploads even though the symbol was
            // accepted server-side. `BundleUploader.uploadData` (the
            // sibling uploader on the chunked path) already used the
            // 200..299 range; this aligns the two.
            if (statusCode !in 200..299) {
                logger.warn(
                    "Bugsee upload failed (status=$statusCode): ${reply.body.orEmpty()}",
                )
                return@use false
            }

            val contentText = reply.body
            if (contentText == null) {
                logger.warn("Bugsee upload failed: no response from server")
                return@use false
            }

            if (debug) logger.warn("Bugsee: Upload step 2. Response: ${redactedResponseForLog(contentText)}")

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
                        "(status=$statusCode, ${contentText.length} chars): ${e.message}",
                )
                return@use false
            }

            // The symbol is already on the server: a success, not a failure.
            if (isAlreadyExists(responseBody)) {
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
                        logger.warn("App token is invalid: ${BundleUploader.maskAppToken(appToken)}")
                    } else {
                        logger.warn("Bugsee upload failed with error: $error")
                    }
                } else {
                    logger.warn("Bugsee upload failed: null endpoint")
                }
                return@use false
            }

            // 2. Upload to presigned URL
            // The presigned URL's query string is a write credential valid for days: never log it.
            if (debug) logger.warn("Bugsee: Uploading to endpoint: ${BundleUploader.redactPresignedUrl(presignedEndpoint)}")
            val httpPut = HttpPut(presignedEndpoint)
            httpPut.entity = FileEntity(file)
            val putReply = executeWithRetry(client, httpPut, "file upload", logger, retryPolicy)

            // Same 2xx-range relaxation as the POST step above —
            // presigned-URL PUTs land on S3 (or whichever CDN the
            // appserver minted), and those return 200/201/204
            // interchangeably depending on multipart vs single-PUT
            // and storage-class settings.
            val putStatus = putReply.status
            if (putStatus !in 200..299) {
                logger.warn(
                    "Bugsee upload failed (status=$putStatus): ${putReply.body.orEmpty()}",
                )
                return@use false
            }

            if (debug) logger.warn("Bugsee: Upload complete.")
            true
        }
    }

    /**
     * The metadata POST's reply as it is safe to log: the `endpoint` field is a presigned PUT URL whose
     * query string is a write credential valid for days, so only the query is dropped and every other field
     * stays visible. A body that is not a JSON object is reduced to its length: a proxy or CDN page can echo
     * anything, including a presigned URL.
     */
    internal fun redactedResponseForLog(contentText: String): String = try {
        val json = JSONObject(contentText)
        if (json.has("endpoint")) {
            json.put("endpoint", BundleUploader.redactPresignedUrl(json.optString("endpoint", "")))
        }
        json.toString()
    } catch (e: Exception) {
        "<not a JSON object, ${contentText.length} chars>"
    }

    /**
     * Whether the metadata POST's reply says the server already has this symbol
     * (`DuplicateSymbolsFoundError`, code 16000 + 4).
     *
     * The appserver sends HTTP 200 with the code NESTED in its error envelope —
     * `{"ok": false, "error": {"type": "DuplicateSymbolsFoundError", "code": 16004}}`
     * — never at the top level. Matching only a top-level `code` (what this used to
     * do) turned every upload of an already-uploaded symbol into a failure, so the
     * task never cached it and uploaded it again on every build. The top-level form
     * is still accepted: it is what this client first matched on, and the check is
     * free. bugsee-cli had the identical bug (fixed in 0.7.8, bugsee/bugsee-cli#35).
     */
    internal fun isAlreadyExists(responseBody: JSONObject): Boolean {
        if (responseBody.optInt("code") == CODE_ALREADY_EXISTS) return true
        val error = responseBody.optJSONObject("error") ?: return false
        return error.optInt("code") == CODE_ALREADY_EXISTS ||
            error.optString("type", "") == "DuplicateSymbolsFoundError"
    }

    private const val CODE_ALREADY_EXISTS = 16004
}
