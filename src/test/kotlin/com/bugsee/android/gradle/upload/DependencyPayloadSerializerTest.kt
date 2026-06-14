package com.bugsee.android.gradle.upload

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class DependencyPayloadSerializerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun parseGz(bytes: ByteArray): JSONObject {
        // The wire blob is gzipped JSON; round-trip to verify the
        // produced bytes are well-formed.
        val text = GZIPInputStream(ByteArrayInputStream(bytes)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        return JSONObject(text)
    }

    private val defaultConfig = CollectionConfig(
        scope = "runtime",
        includeSelectedReason = false,
        maxCount = 5000
    )

    // Builds the gzipped entries blob the way the upload task does —
    // deriving the summary from the entries (the blob now embeds the
    // summary's `truncated` + `collection_config` so the worker can
    // read them off the blob it downloads).
    private fun gz(
        entries: List<DependencyEntry>,
        truncated: Boolean = false,
        config: CollectionConfig = defaultConfig
    ): ByteArray = DependencyPayloadSerializer.entriesGzBytes(
        entries, DependenciesSummary.from(entries, truncated, 0L, config)
    )

    @Test fun `summaryJson surfaces every scalar field`() {
        val summary = DependenciesSummary(
            total = 142,
            direct = 18,
            transitive = 124,
            byType = DependenciesSummary.ByType(library = 130, project = 8, file = 4),
            truncated = true,
            collectedAtEpochMs = 1_716_192_000_000L,  // 2024-05-20T08:00:00Z
            collectionConfig = defaultConfig
        )
        val out = DependencyPayloadSerializer.summaryJson(summary)
        assertEquals(142, out.getInt("total"))
        assertEquals(18, out.getInt("direct"))
        assertEquals(124, out.getInt("transitive"))
        assertEquals(130, out.getJSONObject("by_type").getInt("library"))
        assertEquals(8,   out.getJSONObject("by_type").getInt("project"))
        assertEquals(4,   out.getJSONObject("by_type").getInt("file"))
        assertEquals(true, out.getBoolean("truncated"))
    }

    @Test fun `writeEntries emits raw JSON with the same content as the gz blob`() {
        // The build-info bundle path hands `bugsee-cli` the RAW
        // dependencies.json; the legacy path PUTs the gz. The worker
        // re-gzips the bundle entry on store, so the two MUST carry
        // byte-identical content or a bundle-delivered build would differ
        // from a legacy-delivered one.
        val entries = listOf(
            DependencyEntry(
                group = "androidx.core", name = "core-ktx", version = "1.13.1",
                direct = true, scope = "implementation",
                type = DependencyEntry.Type.LIBRARY
            ),
            DependencyEntry(
                group = "", name = ":sub", version = null,
                direct = true, scope = "implementation",
                type = DependencyEntry.Type.PROJECT
            )
        )
        val summary = DependenciesSummary.from(entries, false, 0L, defaultConfig)
        val rawFile = tempFolder.newFile("dependencies.json")
        DependencyPayloadSerializer.writeEntries(entries, summary, rawFile)

        val rawObj = JSONObject(rawFile.readText(Charsets.UTF_8))
        val gzObj = parseGz(DependencyPayloadSerializer.entriesGzBytes(entries, summary))
        assertEquals(gzObj.toString(), rawObj.toString())
        assertEquals(DependencyPayloadSerializer.SCHEMA_VERSION, rawObj.getInt("schema_version"))
        assertEquals(2, rawObj.getJSONArray("dependencies").length())
    }

    @Test fun `summaryJson emits collection_config fingerprint`() {
        // The worker compares this object against the previous build's
        // `collection_config` to decide whether the two dep lists are
        // apples-to-apples comparable. Every field must serialise
        // exactly (no rename, no coercion) so the comparison is
        // bit-for-bit deterministic on both sides of the wire.
        val summary = DependenciesSummary(
            total = 0, direct = 0, transitive = 0,
            byType = DependenciesSummary.ByType(0, 0, 0),
            truncated = false,
            collectedAtEpochMs = 0L,
            collectionConfig = CollectionConfig(
                scope = "runtime_direct_only",
                includeSelectedReason = true,
                maxCount = 2500
            )
        )
        val cfg = DependencyPayloadSerializer.summaryJson(summary).getJSONObject("collection_config")
        assertEquals("runtime_direct_only", cfg.getString("scope"))
        assertEquals(true,  cfg.getBoolean("include_selected_reason"))
        assertEquals(2500,  cfg.getInt("max_count"))
    }

    @Test fun `collected_at serialises as ISO-8601 UTC ending in Z`() {
        // The viewer / appserver expect the `new Date(...)` JS shape;
        // matching the trailing `Z` keeps round-trips lossless.
        val summary = DependenciesSummary(
            total = 0, direct = 0, transitive = 0,
            byType = DependenciesSummary.ByType(0, 0, 0),
            truncated = false,
            collectedAtEpochMs = 1_716_192_000_000L,
            collectionConfig = defaultConfig
        )
        val collectedAt = DependencyPayloadSerializer.summaryJson(summary).getString("collected_at")
        assertTrue("collected_at must end with Z, was: $collectedAt",
                   collectedAt.endsWith("Z"))
        // Pin a known epoch → known ISO form.
        assertEquals("2024-05-20T08:00:00Z", collectedAt)
    }

    @Test fun `entries blob carries schema_version and all entries`() {
        val entries = listOf(
            DependencyEntry(
                group = "androidx.core", name = "core-ktx", version = "1.13.1",
                direct = true, scope = "implementation",
                type = DependencyEntry.Type.LIBRARY
            ),
            DependencyEntry(
                group = "com.squareup.okhttp3", name = "okhttp", version = "4.12.0",
                direct = false, scope = null,
                type = DependencyEntry.Type.LIBRARY
            ),
            DependencyEntry(
                group = "", name = ":sub", version = null,
                direct = true, scope = "implementation",
                type = DependencyEntry.Type.PROJECT
            )
        )
        val parsed = parseGz(gz(entries))
        assertEquals(DependencyPayloadSerializer.SCHEMA_VERSION, parsed.getInt("schema_version"))
        val arr = parsed.getJSONArray("dependencies")
        assertEquals(3, arr.length())
        val first = arr.getJSONObject(0)
        assertEquals("androidx.core", first.getString("group"))
        assertEquals("core-ktx", first.getString("name"))
        assertEquals("1.13.1", first.getString("version"))
        assertEquals(true, first.getBoolean("direct"))
        assertEquals("implementation", first.getString("scope"))
        assertEquals("library", first.getString("type"))
    }

    @Test fun `entries blob embeds truncated and collection_config (worker reads them off the blob)`() {
        // CONTRACT (cross-repo): the worker's deps-diff compatibility
        // check reads `truncated` + `collection_config.scope` off THIS
        // gzipped blob — not the inline POST summary, which it doesn't
        // have on disk when diffing. Omitting them collapsed every
        // comparison to "no known mismatch", so a scope change or a
        // truncated list silently produced a misleading diff. Pin both
        // fields at the blob's top level so a regression that dropped
        // them (reverting to entries-only) is caught here.
        val entries = listOf(
            DependencyEntry(
                group = "g", name = "n", version = "1",
                direct = true, scope = "implementation",
                type = DependencyEntry.Type.LIBRARY
            )
        )
        val config = CollectionConfig(
            scope = "runtime_direct_only",
            includeSelectedReason = true,
            maxCount = 2500
        )
        val parsed = parseGz(gz(entries, truncated = true, config = config))
        assertEquals(true, parsed.getBoolean("truncated"))
        val cfg = parsed.getJSONObject("collection_config")
        assertEquals("runtime_direct_only", cfg.getString("scope"))
        assertEquals(true, cfg.getBoolean("include_selected_reason"))
        assertEquals(2500, cfg.getInt("max_count"))
    }

    @Test fun `entries blob truncated defaults to false and mirrors the summary`() {
        // The dominant (non-truncated) case: the blob must carry
        // `truncated: false` explicitly, not omit it — the worker
        // reads `.get('truncated')` and an absent key would read as
        // None, which it treats the same as false today but pins a
        // fragile dependency on that coincidence. Emit it explicitly.
        val entries = listOf(
            DependencyEntry(
                group = "g", name = "n", version = "1",
                direct = true, scope = "implementation",
                type = DependencyEntry.Type.LIBRARY
            )
        )
        val parsed = parseGz(gz(entries))  // truncated defaults to false
        assertTrue("truncated key must be present", parsed.has("truncated"))
        assertFalse(parsed.getBoolean("truncated"))
    }

    @Test fun `null fields are OMITTED on the wire, not serialised as JSON null`() {
        // The worker's validator accepts absent optional fields. Emitting
        // `"version": null` would either need the worker to allowlist
        // null OR the appserver's sanitiser to special-case it.
        // Cheaper to just omit.
        val transitive = DependencyEntry(
            group = "g", name = "n", version = null,
            direct = false, scope = null,
            type = DependencyEntry.Type.LIBRARY,
            selectedReason = null
        )
        val parsed = parseGz(gz(listOf(transitive)))
        val first = parsed.getJSONArray("dependencies").getJSONObject(0)
        assertFalse("version must be absent when null", first.has("version"))
        assertFalse("scope must be absent when null", first.has("scope"))
        assertFalse("selected_reason must be absent when null", first.has("selected_reason"))
    }

    @Test fun `selected_reason is included when provided`() {
        val entry = DependencyEntry(
            group = "g", name = "n", version = "1",
            direct = true, scope = "api",
            type = DependencyEntry.Type.LIBRARY,
            selectedReason = "forced"
        )
        val parsed = parseGz(gz(listOf(entry)))
        val first = parsed.getJSONArray("dependencies").getJSONObject(0)
        assertEquals("forced", first.getString("selected_reason"))
    }

    @Test fun `writeEntriesGz round-trips bytes through a file`() {
        val target = tempFolder.newFile("deps.json.gz")
        val entries = listOf(DependencyEntry(
            group = "g", name = "n", version = "1",
            direct = true, scope = "implementation",
            type = DependencyEntry.Type.LIBRARY
        ))
        DependencyPayloadSerializer.writeEntriesGz(
            entries, DependenciesSummary.from(entries, false, 0L, defaultConfig), target
        )
        assertTrue("output must exist", target.exists())
        val parsed = parseGz(target.readBytes())
        assertEquals(1, parsed.getJSONArray("dependencies").length())
    }

    @Test fun `entry blob carries id field for every entry`() {
        // id is the canonical (type, group, name) identity string —
        // the same shape the viewer's identityOf + worker's
        // _identity produce. Always present on the wire so consumers
        // don't have to re-derive it.
        val entries = listOf(
            DependencyEntry(
                group = "androidx.core", name = "core-ktx", version = "1.13.1",
                direct = true, scope = "implementation",
                type = DependencyEntry.Type.LIBRARY
            ),
            DependencyEntry(
                group = "", name = ":sub", version = null,
                direct = true, scope = null,
                type = DependencyEntry.Type.PROJECT
            )
        )
        val arr = parseGz(gz(entries))
            .getJSONArray("dependencies")
        assertEquals("library:androidx.core:core-ktx", arr.getJSONObject(0).getString("id"))
        // project entry: empty group + name=":sub" → three colons total
        // (matches the viewer's identityOf shape verbatim).
        assertEquals("project:::sub",                  arr.getJSONObject(1).getString("id"))
    }

    @Test fun `parents is omitted when empty and rendered as JSON array otherwise`() {
        // Direct deps have no parents -> field absent (saves bytes
        // on the wire — direct deps dominate the entry count on most
        // projects). Transitives carry the parent id list, preserving
        // insertion order so the graph can be reconstructed
        // deterministically.
        val direct = DependencyEntry(
            group = "g", name = "a", version = "1",
            direct = true, scope = "implementation",
            type = DependencyEntry.Type.LIBRARY
            // parents defaulted to emptyList
        )
        val transitive = DependencyEntry(
            group = "g", name = "b", version = "1",
            direct = false, scope = null,
            type = DependencyEntry.Type.LIBRARY,
            parents = listOf("library:g:a", "library:g:c")
        )
        val arr = parseGz(gz(listOf(direct, transitive)))
            .getJSONArray("dependencies")

        val directJson = arr.getJSONObject(0)
        assertFalse("parents must be absent when empty", directJson.has("parents"))

        val transitiveJson = arr.getJSONObject(1)
        val parentsArr = transitiveJson.getJSONArray("parents")
        assertEquals(2, parentsArr.length())
        assertEquals("library:g:a", parentsArr.getString(0))
        assertEquals("library:g:c", parentsArr.getString(1))
    }

    @Test fun `entries blob uses the type wire name verbatim`() {
        val entries = listOf(
            DependencyEntry("g", "lib", "1", true, "implementation", DependencyEntry.Type.LIBRARY),
            DependencyEntry("",  ":a",  null, true, "implementation", DependencyEntry.Type.PROJECT),
            DependencyEntry("",  "f.jar", null, true, "runtimeOnly", DependencyEntry.Type.FILE)
        )
        val arr = parseGz(gz(entries))
            .getJSONArray("dependencies")
        assertEquals("library", arr.getJSONObject(0).getString("type"))
        assertEquals("project", arr.getJSONObject(1).getString("type"))
        assertEquals("file",    arr.getJSONObject(2).getString("type"))
    }

    @Test fun `library entries carry ecosystem maven while project and file entries omit it`() {
        // CROSS-REPO CONTRACT: the worker's vuln-scan maps each library
        // dep onto an OSV ecosystem via this `ecosystem` field and SKIPS
        // any entry that lacks one. Gradle library deps are
        // Maven-coordinate, so every library entry MUST emit
        // `ecosystem: "maven"` — without it the worker drops every entry
        // and vulnerability scanning silently finds nothing in
        // production. project (in-repo module) and file (local artifact)
        // entries have no package ecosystem and must omit the field
        // (the worker skips non-library types regardless).
        val entries = listOf(
            DependencyEntry("g", "lib", "1", true, "implementation", DependencyEntry.Type.LIBRARY),
            DependencyEntry("",  ":a",  null, true, "implementation", DependencyEntry.Type.PROJECT),
            DependencyEntry("",  "f.jar", null, true, "runtimeOnly", DependencyEntry.Type.FILE)
        )
        val arr = parseGz(gz(entries)).getJSONArray("dependencies")
        assertEquals("maven", arr.getJSONObject(0).getString("ecosystem"))
        assertFalse("project entries must not carry ecosystem",
                    arr.getJSONObject(1).has("ecosystem"))
        assertFalse("file entries must not carry ecosystem",
                    arr.getJSONObject(2).has("ecosystem"))
    }
}
