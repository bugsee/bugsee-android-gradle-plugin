package com.bugsee.android.gradle.upload

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ApiEndpointTest {

    // ── buildsUrl ─────────────────────────────────────────────────

    @Test fun `buildsUrl appends v2 prefix to bare host`() {
        assertEquals(
            "https://api.bugsee.com/v2/apps/tok/builds",
            ApiEndpoint.buildsUrl("https://api.bugsee.com", "tok"),
        )
    }

    @Test fun `buildsUrl appends suffix after builds segment`() {
        assertEquals(
            "https://api.bugsee.com/v2/apps/tok/builds/chunk-options",
            ApiEndpoint.buildsUrl("https://api.bugsee.com", "tok", "/chunk-options"),
        )
    }

    @Test fun `buildsUrl trims trailing slash from host`() {
        assertEquals(
            "https://api.bugsee.com/v2/apps/tok/builds",
            ApiEndpoint.buildsUrl("https://api.bugsee.com/", "tok"),
        )
    }

    @Test fun `buildsUrl strips explicit v2 suffix so it isn't doubled`() {
        assertEquals(
            "https://api.bugsee.com/v2/apps/tok/builds",
            ApiEndpoint.buildsUrl("https://api.bugsee.com/v2", "tok"),
        )
    }

    @Test fun `buildsUrl strips explicit v2 suffix with trailing slash`() {
        assertEquals(
            "https://api.bugsee.com/v2/apps/tok/builds/chunks/check",
            ApiEndpoint.buildsUrl("https://api.bugsee.com/v2/", "tok", "/chunks/check"),
        )
    }

    @Test fun `buildsUrl strips explicit v1 suffix and replaces with v2`() {
        assertEquals(
            "https://api.bugsee.com/v2/apps/tok/builds",
            ApiEndpoint.buildsUrl("https://api.bugsee.com/v1", "tok"),
        )
    }

    @Test fun `buildsUrl preserves other path segments`() {
        // Users running a reverse proxy (e.g. staging) may point at
        // `https://proxy.example/bugsee` — only the version suffix is
        // stripped, not arbitrary middle segments.
        assertEquals(
            "https://proxy.example/bugsee/v2/apps/tok/builds",
            ApiEndpoint.buildsUrl("https://proxy.example/bugsee", "tok"),
        )
    }

    // ── unwrapResult ──────────────────────────────────────────────

    @Test fun `unwrapResult returns result sub-object for v2 envelope`() {
        val payload = JSONObject().put("build_id", "abc")
        val envelope = JSONObject()
            .put("ok", true)
            .put("result", payload)

        val unwrapped = ApiEndpoint.unwrapResult(envelope)
        assertEquals("abc", unwrapped.optString("build_id"))
    }

    @Test fun `unwrapResult returns body itself when no result key present`() {
        // v1 responses are flat — the function must pass them through.
        val flat = JSONObject()
            .put("ok", true)
            .put("endpoint", "https://s3.example/upload")
        val unwrapped = ApiEndpoint.unwrapResult(flat)
        // Same reference — no extra allocation on the hot path.
        assertSame(flat, unwrapped)
        assertEquals("https://s3.example/upload", unwrapped.optString("endpoint"))
    }

    @Test fun `unwrapResult tolerates empty result object`() {
        val envelope = JSONObject()
            .put("ok", true)
            .put("result", JSONObject())
        val unwrapped = ApiEndpoint.unwrapResult(envelope)
        assertEquals("", unwrapped.optString("build_id"))
    }
}
