package com.bugsee.android.gradle.upload

import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.utils.URIBuilder
import org.apache.http.conn.ssl.DefaultHostnameVerifier
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.client.StandardHttpRequestRetryHandler
import org.apache.http.util.EntityUtils
import org.gradle.api.logging.Logger
import org.json.JSONObject

/**
 * Read-only client for the appserver's baseline lookup endpoint
 * (`GET /v2/apps/{token}/builds/baseline`). Used by the in-build
 * size-check feature to fetch the most recent prior build's recorded
 * artifact size for delta computation.
 *
 * Returns `null` on every non-success path (no baseline exists,
 * network error, malformed response, server error). The caller treats
 * `null` as "no baseline available — skip the check" rather than
 * failing the build on infrastructure problems.
 */
internal object BaselineClient {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val SOCKET_TIMEOUT_MS = 30_000

    /**
     * Subset of fields the size-check evaluator needs from the server
     * payload. The server may add fields over time; this struct
     * captures only what we currently consume so a forward-compatible
     * payload doesn't require a client-side schema update.
     */
    internal data class Baseline(
        val artifactSize: Long,
        val version: String?,
        val build: String?,
    )

    /**
     * Fetch the baseline build's recorded artifact size for a
     * (package_id, format, build_configuration) tuple.
     *
     * @return The baseline, or `null` when none exists / a transient
     *         failure prevented retrieval. Either way the caller
     *         should treat the absence as PASS-skip.
     */
    fun fetchBaseline(
        endpoint: String,
        appToken: String,
        packageId: String,
        format: String,
        buildConfiguration: String?,
        logger: Logger,
        debug: Boolean,
    ): Baseline? {
        val url = try {
            val base = ApiEndpoint.buildsUrl(endpoint, appToken, "/baseline")
            // URIBuilder properly URL-encodes query components — defends
            // against a stray `&` / `?` / unicode in a build_configuration.
            URIBuilder(base)
                .addParameter("package_id", packageId)
                .addParameter("format", format)
                .also { if (!buildConfiguration.isNullOrEmpty()) it.addParameter("build_configuration", buildConfiguration) }
                .build()
                .toASCIIString()
        } catch (e: Exception) {
            logger.warn("Bugsee: size-check baseline URL build failed (${e.message}); skipping")
            return null
        }

        if (debug) logger.warn("Bugsee: size-check fetching baseline from $url")

        val httpGet = HttpGet(url)
        val requestConfig = RequestConfig.custom()
            .setConnectTimeout(CONNECT_TIMEOUT_MS)
            .setSocketTimeout(SOCKET_TIMEOUT_MS)
            .setConnectionRequestTimeout(CONNECT_TIMEOUT_MS)
            .build()

        return try {
            HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setSSLHostnameVerifier(DefaultHostnameVerifier(null))
                .setRetryHandler(StandardHttpRequestRetryHandler())
                .build()
                .use { client ->
                    val response = client.execute(httpGet)
                    val statusCode = response.statusLine.statusCode
                    val body = response.entity?.let { EntityUtils.toString(it, "utf-8") } ?: ""

                    if (statusCode !in 200..299) {
                        logger.warn(
                            "Bugsee: size-check baseline lookup returned $statusCode " +
                                "(${body.take(200)}); skipping check"
                        )
                        return@use null
                    }

                    val payload = try {
                        ApiEndpoint.unwrapResult(JSONObject(body))
                    } catch (e: Exception) {
                        logger.warn("Bugsee: size-check baseline returned non-JSON; skipping (${body.take(200)})")
                        return@use null
                    }

                    // The server returns `{ build: null }` for "no baseline yet".
                    // Treat both `JSONObject.NULL` and a missing key as no-baseline.
                    val build = payload.optJSONObject("build") ?: return@use null
                    val artifactSize = build.optLong("artifact_size", -1L)
                    if (artifactSize <= 0) {
                        // Legacy build with no captured artifact_size — same
                        // outcome as "no baseline exists at all" from the
                        // check's perspective. Don't fall back to a different
                        // build — we want a stable comparison target.
                        if (debug) {
                            logger.warn("Bugsee: size-check baseline carries no artifact_size; skipping")
                        }
                        return@use null
                    }

                    // org.json's `optString(key, fallback)` returns the
                    // literal string `"null"` when the JSON value is
                    // `JSONObject.NULL` — the `fallback` argument only
                    // covers a *missing* key, not an explicit-null one.
                    // Without the `isNull` guard the log line would
                    // read `vs version null (null)` whenever the
                    // baseline carries a JSON null for either field.
                    Baseline(
                        artifactSize = artifactSize,
                        version = if (build.isNull("version")) null else build.optString("version").takeIf { it.isNotEmpty() },
                        build = if (build.isNull("build")) null else build.optString("build").takeIf { it.isNotEmpty() },
                    )
                }
        } catch (e: Exception) {
            logger.warn("Bugsee: size-check baseline lookup failed (${e.message}); skipping check")
            null
        }
    }
}
