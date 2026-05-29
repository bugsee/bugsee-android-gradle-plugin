package com.bugsee.android.gradle.upload

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Serialises [DependencyCollector.Result] into the two wire shapes the
 * upload task needs:
 *
 *   1. [summaryJson] — inline-in-POST scalar summary (small).
 *      Matches `BuildDependenciesSummarySchema` on the appserver.
 *
 *   2. [entriesGzBytes] / [writeEntriesGz] — gzipped JSON blob with
 *      `schema_version: 1` + the full per-entry list. PUT to the
 *      presigned URL the server returns alongside the inline summary.
 *
 * Output is compact (no whitespace) — keeps the gzipped blob small
 * and the worker's validation step (which decompresses + parses) fast.
 */
internal object DependencyPayloadSerializer {

    /** Wire-format schema version. Bump in lockstep with the worker's
     *  `_SUPPORTED_SCHEMA_VERSIONS` set. */
    const val SCHEMA_VERSION = 1

    /**
     * Render the scalar summary as a JSON object. Caller embeds the
     * result under `"dependencies_summary"` in the metadata POST body.
     *
     * `collected_at` ships as an ISO-8601 UTC string the appserver's
     * sanitiser parses via `new Date(...)`.
     */
    fun summaryJson(summary: DependenciesSummary): JSONObject =
        JSONObject().apply {
            put("total", summary.total)
            put("direct", summary.direct)
            put("transitive", summary.transitive)
            put("by_type", JSONObject().apply {
                put("library", summary.byType.library)
                put("project", summary.byType.project)
                put("file", summary.byType.file)
            })
            put("truncated", summary.truncated)
            // ISO-8601 UTC with millisecond precision and a trailing `Z`.
            // Mirrors `new Date(epochMs).toISOString()` so the
            // appserver's `new Date(raw.collected_at)` round-trips
            // identically across the JS / JVM boundary.
            put("collected_at", isoUtc(summary.collectedAtEpochMs))
            // Fingerprint of how this list was collected. The worker
            // compares this against the previous build's
            // `collection_config` to decide whether the two lists
            // are apples-to-apples comparable.
            put("collection_config", collectionConfigJson(summary.collectionConfig))
        }

    /**
     * Render the per-entry list as raw gzipped bytes. The shape:
     *   `{"schema_version": 1, "truncated": false,
     *     "collection_config": {...}, "dependencies": [...]}`
     * Each entry serialises only the non-null fields — the worker
     * tolerates absent `version` / `scope` / `selected_reason` as
     * "not applicable".
     */
    fun entriesGzBytes(entries: List<DependencyEntry>, summary: DependenciesSummary): ByteArray {
        val obj = entriesJsonObject(entries, summary)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            // org.json has no streaming writer, so `toString()` builds
            // the full JSON payload as a single String, which we then
            // UTF-8 encode and gzip in place. On multi-MB graphs this
            // String is the peak intermediate allocation.
            gz.write(obj.toString().toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    /**
     * Write the gzipped blob to a file (caller-supplied — typically a
     * temp file under `task.temporaryDir`). Returns the same File for
     * call-site chaining.
     */
    fun writeEntriesGz(entries: List<DependencyEntry>, summary: DependenciesSummary, target: File): File {
        target.parentFile?.mkdirs()
        target.writeBytes(entriesGzBytes(entries, summary))
        return target
    }

    // ── internals ──────────────────────────────────────────────────

    private fun entriesJsonObject(entries: List<DependencyEntry>, summary: DependenciesSummary): JSONObject =
        JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            // Blob-level comparability signals. The worker's deps-diff
            // reads `truncated` + `collection_config` off THIS gzipped
            // blob (NOT the inline POST summary — it only has the
            // blob on disk when diffing) to decide whether two builds'
            // dependency lists are apples-to-apples. Omitting them
            // collapsed every comparison to the "no known mismatch"
            // default, so a scope change or a truncated list silently
            // produced a misleading diff. Mirror the inline summary's
            // values here so the worker sees the real fingerprint.
            put("truncated", summary.truncated)
            put("collection_config", collectionConfigJson(summary.collectionConfig))
            put("dependencies", JSONArray().also { arr ->
                for (e in entries) {
                    arr.put(entryJson(e))
                }
            })
        }

    private fun collectionConfigJson(config: CollectionConfig): JSONObject =
        JSONObject().apply {
            put("scope", config.scope)
            put("include_selected_reason", config.includeSelectedReason)
            put("max_count", config.maxCount)
        }

    private fun entryJson(e: DependencyEntry): JSONObject =
        JSONObject().apply {
            // Stable identity ID — always emitted. Same shape the
            // viewer + worker use as the diff identity (`<type>:<group>:<name>`),
            // surfaced here so consumers don't have to re-derive it.
            put("id", e.id)
            // `group` is empty for project / file deps — keep it on
            // the wire so the schema is uniform (the appserver's
            // sanitiser drops empties downstream if it cares).
            put("group", e.group)
            put("name", e.name)
            e.version?.let { put("version", it) }
            put("direct", e.direct)
            e.scope?.let { put("scope", it) }
            put("type", e.type.wire)
            // Package ecosystem — REQUIRED by the worker's vuln-scan to
            // map a dependency onto its OSV ecosystem. Gradle resolves
            // library deps from Maven-coordinate repositories, so every
            // LIBRARY entry is OSV's `Maven` ecosystem. Emitted only for
            // library type: `project` (in-repo module) and `file` (local
            // artifact) entries have no package ecosystem, and the worker
            // skips non-library types before it ever reads this field.
            // Without it the worker drops EVERY entry (no ecosystem -> no
            // OSV query) and vuln scanning silently finds nothing.
            if (e.type == DependencyEntry.Type.LIBRARY) {
                put("ecosystem", "maven")
            }
            e.selectedReason?.let { put("selected_reason", it) }
            // `parents` is omitted when empty (the common case for
            // direct deps, which dominate the entry count on most
            // projects). Saves ~12 bytes/entry × thousands on big
            // monorepos. Consumers treat missing and `[]` as
            // equivalent.
            if (e.parents.isNotEmpty()) {
                put("parents", JSONArray().also { arr ->
                    for (p in e.parents) arr.put(p)
                })
            }
        }

    private fun isoUtc(epochMs: Long): String {
        // Build the canonical ISO-8601 form by hand — sidesteps the
        // platform-default zone problem `DateTimeFormatter.ISO_INSTANT`
        // sometimes hits and keeps the output stable in tests.
        val instant = java.time.Instant.ofEpochMilli(epochMs)
        val zdt = instant.atOffset(java.time.ZoneOffset.UTC)
        return zdt.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            // ISO_OFFSET_DATE_TIME emits `+00:00`; switch to the
            // shorter `Z` form so the wire shape matches the JS
            // convention the appserver uses elsewhere.
            .replace("+00:00", "Z")
    }
}
