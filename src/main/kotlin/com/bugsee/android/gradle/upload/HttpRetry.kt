package com.bugsee.android.gradle.upload

import org.apache.http.client.methods.HttpUriRequest
import org.apache.http.conn.ConnectTimeoutException
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.util.EntityUtils
import org.gradle.api.logging.Logger
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Bounded retry of transient HTTP failures, shared by the uploaders ([SymbolUploader], [BundleUploader],
 * [ChunkedBundleUploader], [BaselineClient]).
 */
internal object HttpRetry {

    /** Attempts per request (1 try + 2 retries); the backoff before retry N is `N * backoffMs`. */
    internal const val MAX_ATTEMPTS = 3
    private const val RETRY_BACKOFF_MS = 500L
    private const val RETRY_AFTER_CAP_MS = 10_000L
    private const val MAX_RETRY_AFTER_SECONDS = 3_600L
    private const val RETRY_BUDGET_MS = 180_000L

    /** Retry knobs; the defaults are production values, tests inject small ones. */
    internal class RetryPolicy(
        val backoffMs: Long = RETRY_BACKOFF_MS,
        val retryAfterCapMs: Long = RETRY_AFTER_CAP_MS,
        val budgetMs: Long = RETRY_BUDGET_MS,
    )

    /** A fully read reply: status, body text and the capped `Retry-After` wait, when sent. */
    internal class Reply(val status: Int, val body: String?, val retryAfterMs: Long? = null)

    /** True when the thread was interrupted or [e] says the request was cancelled (not a plain timeout). */
    internal fun isInterruption(e: IOException): Boolean =
        Thread.currentThread().isInterrupted ||
            (e is InterruptedIOException && e !is SocketTimeoutException && e !is ConnectTimeoutException)

    /**
     * Rethrows a pending interrupt as [InterruptedException]; the flag stays set. The CLI uploader reports a killed
     * or cancelled `bugsee-cli` run as a plain fallback result, so a task calls this on its failure paths to keep
     * a cancelled build cancelled instead of turning it into a warning or a native-uploader fallback.
     */
    @Throws(InterruptedException::class)
    internal fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Bugsee: build cancelled")
        }
    }

    /** Statuses worth another attempt of any request: throttling and every 5xx. */
    private fun isTransient(status: Int): Boolean = status == 429 || status in 500..599

    /**
     * Statuses worth another attempt of a request that is NOT idempotent on the wire (a POST that registers
     * a build): throttling and 502/503 (usually the request never reached the application; not 504, where a stitch may still be running). A
     * plain 500 is not repeated. Repeating a registration is believed safe: the appserver replaces a build
     * registered under the same `uuid` rather than creating a second one (confirmed by code reading during #118,
     * not verified end to end).
     */
    private fun isGatewayTransient(status: Int): Boolean = status == 429 || status == 502 || status == 503

    /**
     * Run [request], retrying transient statuses up to [MAX_ATTEMPTS] times. Permanent statuses (4xx,
     * notably 401/403/404) are returned at once. The wait before a retry is the server's `Retry-After`
     * (seconds, capped) when sent, else a linear backoff. No retry starts once [RetryPolicy.budgetMs]
     * would be exceeded. The last attempt's reply is returned when the attempts run out. The request
     * entity must be repeatable ([org.apache.http.entity.StringEntity], [org.apache.http.entity.FileEntity]).
     *
     * [idempotent] `true` (the default; safe to repeat): 429 and every 5xx are retried, and so is an I/O
     * error, except a read timeout ([SocketTimeoutException]: the server already held the request for the
     * full socket timeout, so retrying would stall the build for minutes) or a cancellation. The caller's
     * client must have its own automatic retries disabled, since this loop owns all retries.
     * [idempotent] `false` (a POST that creates a record): only 429/502/503 are retried, and an I/O
     * error is thrown as is, so the client's own handler decides about it (it does not resend a request
     * that was already fully sent), except a failed connect (refused or timed out), which provably sent
     * nothing and is retried like an idempotent request.
     */
    fun execute(
        client: CloseableHttpClient,
        request: HttpUriRequest,
        step: String,
        logger: Logger,
        policy: RetryPolicy = RetryPolicy(),
        idempotent: Boolean = true,
    ): Reply {
        val started = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - started) / 1_000_000
        val retryable: (Int) -> Boolean = if (idempotent) ::isTransient else ::isGatewayTransient
        for (attempt in 1..MAX_ATTEMPTS) {
            // The blocking HttpClient never looks at the interrupt flag itself: stop before each attempt.
            throwIfInterrupted()
            val last = attempt == MAX_ATTEMPTS
            var waitMs = policy.backoffMs * attempt
            try {
                val reply = client.execute(request).use { response ->
                    val retryAfterMs = response.getFirstHeader("Retry-After")?.value?.trim()?.toLongOrNull()
                        ?.takeIf { it >= 0 }?.let { minOf(it.coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1000, policy.retryAfterCapMs) }
                    Reply(response.statusLine.statusCode, response.entity?.let { EntityUtils.toString(it, "utf-8") }, retryAfterMs)
                }
                if (!retryable(reply.status) || last) return reply
                waitMs = reply.retryAfterMs ?: waitMs
                if (elapsedMs() + waitMs >= policy.budgetMs) return reply
                logger.warn("Bugsee: $step returned status ${reply.status}, retrying (attempt $attempt of $MAX_ATTEMPTS)")
            } catch (e: IOException) {
                // A refused or timed-out connect happens before any byte is sent, so even a POST may be repeated.
                val notSent = e is java.net.ConnectException || e is ConnectTimeoutException
                if ((!idempotent && !notSent) || last || isInterruption(e) || e is SocketTimeoutException || elapsedMs() + waitMs >= policy.budgetMs) throw e
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
}
