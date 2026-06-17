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
import org.gradle.process.ExecOperations
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
        // Build-info bundle (Phase D). When the registration response
        // carries `build_info_upload_endpoint` AND a CLI binary is
        // resolvable AND the escape hatch is off, deps + timings ship as
        // ONE zstd bundle via `bugsee-cli upload build-info` instead of
        // the two legacy gzip PUTs. All default to the no-bundle state so
        // existing callers / tests get the unchanged legacy behaviour.
        execOps: ExecOperations? = null,
        resolveCli: () -> File? = { null },
        depsJsonFile: File? = null,
        timingsJsonFile: File? = null,
        legacyBuildInfoGzip: Boolean = false,
    ) {
        if (debug) logger.warn("Bugsee: Starting bundle upload. Body: $json")

        val httpPost = HttpPost(ApiEndpoint.buildsUrl(endpoint, appToken))
        // Encode the JSON body as UTF-8 explicitly: the single-arg StringEntity
        // defaults to ISO-8859-1 (Apache HttpClient's default), which mangles
        // non-ASCII metadata (e.g. accented/CJK/emoji git branch names in
        // `branch`/`base_branch`) into mojibake or breaks the server's UTF-8
        // JSON parse. The chunked transport already does this correctly.
        val body = StringEntity(json, "UTF-8")
        body.contentType = BasicHeader(HTTP.CONTENT_TYPE, "application/json; charset=utf-8")
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
                // The server returns HTTP 2xx with an `{ ok:false,
                // error:{...} }` envelope on rejection (e.g. an invalid
                // app token → `ApplicationNotFoundError`). Without
                // inspecting it the task would report green while the
                // backend never created a build. Mirror the artefact
                // path's error handling so an invalid token / server
                // rejection fails the step instead of silently
                // succeeding.
                val error = responseBody.optJSONObject("error") ?: payload.optJSONObject("error")
                if (error != null) {
                    val errorType = error.optString("type", "")
                    if (errorType == "ApplicationNotFoundError") {
                        throw RuntimeException(
                            "Bugsee: App token is invalid: ${maskAppToken(appToken)}"
                        )
                    }
                    throw RuntimeException("Bugsee build-info upload failed: $error")
                }
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

            // Auxiliary blobs — deps + timings — independent of each
            // other AND of the artefact PUT above. Each is a
            // best-effort PUT to a server-supplied presigned URL,
            // wrapped in its own try/catch so a transient failure
            // here cannot break a (successful) artefact upload, and
            // vice versa. The inline summaries live in the build doc
            // (deps_summary on the POST body; build_metadata.timings
            // inside the same body); these are the detail blobs the
            // viewer lazy-fetches.
            uploadBuildInfoComponents(
                client = client,
                buildInfoUploadEndpoint = payload.optString("build_info_upload_endpoint", ""),
                dependenciesUploadEndpoint = depsPresignedEndpoint,
                timingsUploadEndpoint = timingsPresignedEndpoint,
                execOps = execOps,
                resolveCli = resolveCli,
                depsJsonFile = depsJsonFile,
                timingsJsonFile = timingsJsonFile,
                dependenciesGzFile = dependenciesGzFile,
                timingsGzFile = timingsGzFile,
                legacyBuildInfoGzip = legacyBuildInfoGzip,
                logger = logger,
                debug = debug,
            )
        }
    }

    /**
     * Ship the build-info components (deps + timings) by ONE of two routes:
     *
     *   - **Bundle (preferred)** — when the server signed a
     *     [buildInfoUploadEndpoint], the escape hatch is off, there's at
     *     least one raw JSON file, and a CLI binary resolves: shell to
     *     `bugsee-cli upload build-info --upload-url <endpoint>` (pre-signed
     *     mode), which packs the raw `dependencies.json` / `timings.json`
     *     into one zstd ZIP and PUTs it. The converged Phase-D path.
     *   - **Legacy per-blob (fallback)** — otherwise (no bundle endpoint /
     *     escape hatch / no CLI / ANY bundle failure): the two independent
     *     best-effort gzip PUTs to the separate presigned URLs, exactly as
     *     before this path existed.
     *
     * Fallback policy (best-effort enrichment): the legacy per-blob PUTs go
     * to DIFFERENT presigned URLs than the bundle, so they're an INDEPENDENT
     * upload mechanism — not "the same backend, same error" the way the
     * mapping CLI/Kotlin paths are. So we fall back to them on ANY bundle
     * failure (structural OR substantive), maximising the chance the
     * enrichment data still lands. A failed bundle (exit != 0) didn't store
     * anything, so there's no double-store risk.
     *
     * CLI resolution is LAZY: [resolveCli] is invoked only after the cheap
     * gate passes, so an org that isn't flagged on (no bundle endpoint in
     * the response) never pays the CLI auto-download. Shared between the
     * single-PUT and chunked paths so both produce the same wire behaviour.
     */
    @Suppress("LongParameterList")
    internal fun uploadBuildInfoComponents(
        client: org.apache.http.impl.client.CloseableHttpClient,
        buildInfoUploadEndpoint: String,
        dependenciesUploadEndpoint: String,
        timingsUploadEndpoint: String,
        execOps: ExecOperations?,
        resolveCli: () -> File?,
        depsJsonFile: File?,
        timingsJsonFile: File?,
        dependenciesGzFile: File?,
        timingsGzFile: File?,
        legacyBuildInfoGzip: Boolean,
        logger: Logger,
        debug: Boolean,
    ) {
        if (shouldAttemptBundle(
                buildInfoUploadEndpoint = buildInfoUploadEndpoint,
                legacyBuildInfoGzip = legacyBuildInfoGzip,
                hasExec = execOps != null,
                hasAnyJson = depsJsonFile != null || timingsJsonFile != null,
            )
        ) {
            // Lazy: only download/resolve the CLI now that a bundle is
            // actually on offer (W3 — don't pay it on every soak build).
            val cliBinary = resolveCli()
            if (cliBinary != null && execOps != null) {
                val result = CliUploader.uploadBuildInfo(
                    execOps = execOps,
                    cliBinary = cliBinary,
                    uploadUrl = buildInfoUploadEndpoint,
                    depsJsonFile = depsJsonFile,
                    timingsJsonFile = timingsJsonFile,
                    logger = logger,
                    debug = debug,
                )
                if (result.success) {
                    if (debug) logger.warn("Bugsee: build-info bundle uploaded via bugsee-cli.")
                    return
                }
                // Any failure → fall back to the independent legacy PUTs.
                logger.warn(
                    "Bugsee: build-info bundle upload failed " +
                        "(${result.fallbackReason ?: "exit ${result.exitCode}"}); " +
                        "falling back to legacy per-blob upload.",
                )
            } else {
                logger.warn(
                    "Bugsee: build-info bundle offered but bugsee-cli could not be " +
                        "resolved; falling back to legacy per-blob upload.",
                )
            }
            // fall through to the legacy per-blob PUTs
        }

        uploadAuxiliaryBlob(client, "dependencies", dependenciesUploadEndpoint, dependenciesGzFile, logger, debug)
        uploadAuxiliaryBlob(client, "timings", timingsUploadEndpoint, timingsGzFile, logger, debug)
    }

    /**
     * Cheap gate for the build-info bundle path, evaluated BEFORE the CLI is
     * resolved (so a non-flagged org never pays the auto-download): the
     * server signed a bundle URL ([buildInfoUploadEndpoint] non-empty), the
     * escape hatch is off, an [ExecOperations] is available, and there's at
     * least one component to pack. Any miss → the legacy per-blob gzip PUTs.
     * Whether the CLI then actually resolves is handled by the caller (a
     * null resolve also falls back). Extracted (and `internal`) so the
     * gating contract is unit-testable without an exec / network round-trip.
     */
    internal fun shouldAttemptBundle(
        buildInfoUploadEndpoint: String,
        legacyBuildInfoGzip: Boolean,
        hasExec: Boolean,
        hasAnyJson: Boolean,
    ): Boolean =
        buildInfoUploadEndpoint.isNotEmpty() && !legacyBuildInfoGzip && hasExec && hasAnyJson

    /**
     * Best-effort PUT of an auxiliary blob (deps or timings gz) to a
     * server-supplied presigned URL. Shared between the single-PUT
     * and chunked-upload paths so both ingress flows produce the
     * same wire contract (and so a future blob type slots in with
     * one more call here, not a second copy of the block).
     *
     * Contract: independent of every other PUT in the upload flow.
     * A failure or absence here MUST NOT affect the caller; the
     * function logs and returns regardless of outcome. The caller is
     * responsible for keeping `client` alive across all calls.
     *
     * @param client an already-built http client to reuse (saves
     *   re-establishing the TLS connection pool per-blob).
     * @param label "dependencies" / "timings" — used verbatim in
     *   log messages so warning lines are greppable.
     * @param presignedUrl the server-returned PUT URL. Empty string
     *   means the server didn't ask us to upload — log + skip.
     * @param gzFile the gzipped blob to PUT. `null` means the
     *   collection step on the client side produced nothing — silent
     *   skip (a deps-collection-disabled run shouldn't WARN about a
     *   missing URL it never asked for).
     */
    internal fun uploadAuxiliaryBlob(
        client: org.apache.http.impl.client.CloseableHttpClient,
        label: String,
        presignedUrl: String,
        gzFile: File?,
        logger: Logger,
        debug: Boolean,
    ) {
        if (gzFile == null) return
        if (presignedUrl.isEmpty()) {
            logger.warn(
                "Bugsee: $label blob ready but server did not return a presigned URL — skipping."
            )
            return
        }
        try {
            if (debug) logger.warn(
                "Bugsee: Uploading $label blob to endpoint: " +
                redactPresignedUrl(presignedUrl)
            )
            // Content-Type matches the server-side sign
            // (`application/octet-stream`) — anything else and SigV2
            // mismatches.
            val put = HttpPut(presignedUrl)
            val entity = FileEntity(gzFile)
            entity.contentType = BasicHeader(HTTP.CONTENT_TYPE, "application/octet-stream")
            put.entity = entity
            val resp = client.execute(put)
            if (resp.statusLine.statusCode !in 200..299) {
                val body = EntityUtils.toString(resp.entity, "utf-8")
                logger.warn(
                    "Bugsee: $label upload failed " +
                    "(status=${resp.statusLine.statusCode}): $body"
                )
            } else if (debug) {
                logger.warn("Bugsee: $label upload complete.")
            }
        } catch (e: Exception) {
            logger.warn("Bugsee: $label upload failed: ${e.message}")
        }
    }
}
