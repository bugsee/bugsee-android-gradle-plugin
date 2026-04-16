package com.bugsee.android.gradle.upload

import org.json.JSONObject

/**
 * Helpers for composing Bugsee v2 REST URLs and parsing the v2
 * response envelope.
 *
 * The builds / size-analysis endpoints are mounted under `/v2/...`
 * on the server (see `v2.routes.js` in the appserver). Callers
 * configure a bare `endpoint` like `https://api.bugsee.com` and we
 * append the version segment here so a stray `/v2` in user config
 * doesn't end up doubled.
 *
 * v2 responses are wrapped as `{ ok: true, result: {...} }`.
 * [unwrapResult] pulls the payload out while tolerating legacy v1
 * replies that don't wrap at all — the caller just reads the payload
 * object uniformly.
 */
internal object ApiEndpoint {

    /**
     * Build a fully-qualified builds endpoint URL.
     *
     * The caller's `endpoint` is treated as a host base: any trailing
     * slash is trimmed, and a `/v1` or `/v2` suffix (with or without
     * trailing slash) is stripped before the canonical `/v2/apps/...`
     * path is appended. This lets users keep the legacy
     * `https://api.bugsee.com` default without ending up at
     * `https://api.bugsee.com/v2/v2/apps/...`.
     *
     * @param base    Host base, e.g. `https://api.bugsee.com` or
     *                `https://api.bugsee.com/v2`.
     * @param appToken Bugsee app token.
     * @param suffix  Path suffix starting with `/` (may be empty for
     *                the root `/builds` endpoint).
     */
    fun buildsUrl(base: String, appToken: String, suffix: String = ""): String {
        val cleanBase = base
            .trimEnd('/')
            .removeSuffix("/v1")
            .removeSuffix("/v2")
            .trimEnd('/')
        return "$cleanBase/v2/apps/$appToken/builds$suffix"
    }

    /**
     * Return the `result` payload of a v2 response, or the body
     * itself if no envelope is present. Used after the status-code
     * check so callers don't have to special-case v1 vs v2.
     */
    fun unwrapResult(body: JSONObject): JSONObject =
        body.optJSONObject("result") ?: body
}
