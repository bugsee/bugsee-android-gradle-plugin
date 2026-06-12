package com.bugsee.android.gradle.upload

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the cross-producer handshake manifest the Gradle plugin
 * writes. The fastlane reader at
 * `fastlane-plugin-bugsee/lib/fastlane/plugin/bugsee/helper/bugsee_handshake.rb`
 * keys off exactly these field names and accepts only
 * `schema_version == 1`. Drift on either side silently breaks
 * per-action de-duplication, so the shape gets test pins on both
 * repos.
 *
 * The corresponding fastlane reader pins ACTION_KEYS in
 * `spec/bugsee_handshake_spec.rb`; the two lists MUST stay in
 * lockstep.
 */
class BundleUploadTaskBuildActionsManifestTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun parseManifest(file: java.io.File): JSONObject =
        JSONObject(file.readText())

    private fun writeAndRead(
        outFile: java.io.File = tempFolder.newFile("build-actions.json"),
        pluginVersion: String? = "1.2.3",
        buildId: String? = "b27501e9-5a6a-3227-9f7d-f4696b48739c",
        producedAtMs: Long = 1_700_000_000_000L,
        versionName: String? = "1.2.3",
        versionCode: String? = "42",
        dsymUpload: Boolean = false,
        mappingUpload: Boolean = true,
        depsCollection: Boolean = true,
        timings: Boolean = true,
        sizeAnalysis: Boolean = false,
    ): Pair<java.io.File, JSONObject> {
        BundleUploadTask.writeBuildActionsManifest(
            outFile = outFile,
            pluginVersion = pluginVersion,
            buildId = buildId,
            producedAtMs = producedAtMs,
            versionName = versionName,
            versionCode = versionCode,
            dsymUpload = dsymUpload,
            mappingUpload = mappingUpload,
            depsCollection = depsCollection,
            timings = timings,
            sizeAnalysis = sizeAnalysis,
        )
        return outFile to parseManifest(outFile)
    }

    // ── Schema / producer-identity pins ──────────────────────────

    @Test
    fun `schema_version is 1`() {
        // Cross-repo contract: the fastlane reader rejects any
        // other schema_version as forward-incompatible. A bump
        // here MUST be coordinated with the reader and ALL other
        // producers (the iOS SDK BugseeAgent).
        val (_, m) = writeAndRead()
        assertEquals(1, m.getInt("schema_version"))
    }

    @Test
    fun `producer is the documented literal`() {
        // The fastlane reader logs this verbatim so a customer can
        // attribute skip decisions back to a specific producer.
        val (_, m) = writeAndRead()
        assertEquals("bugsee-android-gradle-plugin", m.getString("producer"))
    }

    @Test
    fun `producer_version is emitted when given`() {
        val (_, m) = writeAndRead(pluginVersion = "9.9.9")
        assertEquals("9.9.9", m.getString("producer_version"))
    }

    @Test
    fun `producer_version is omitted when null`() {
        // Forward-compat: the reader's KDoc says missing producer_version
        // is acceptable. A literal "null" string in the JSON would
        // surface as the producer name in the lane log; a missing
        // key is the right shape.
        val (_, m) = writeAndRead(pluginVersion = null)
        assertFalse("producer_version must be ABSENT, not null, when unknown",
            m.has("producer_version"))
    }

    // ── Actions block — the load-bearing contract ────────────────

    @Test
    fun `actions block has exactly the documented keys`() {
        // Pinning the set: any new producer feature MUST update
        // ALL three repos in lockstep (this file, the iOS SDK
        // writer, the fastlane reader).
        val (_, m) = writeAndRead()
        val actions = m.getJSONObject("actions")
        val keys = actions.keySet().sorted()
        assertEquals(
            listOf("deps_collection", "dsym_upload", "mapping_upload",
                "size_analysis", "timings"),
            keys,
        )
    }

    @Test
    fun `dsym_upload faithfully reflects caller-provided value`() {
        // Android has no dSYMs in practice — production callers
        // always pass `false`. The writer faithfully echoes
        // whatever it's given so a future producer that DOES
        // emit dSYMs can re-use the key. Pin the round-trip for
        // both Boolean values from separate file targets so the
        // TemporaryFolder's "no duplicate names" rule doesn't bite.
        val (_, mTrue) = writeAndRead(
            outFile = tempFolder.newFile("a.json"),
            dsymUpload = true,
        )
        assertEquals(true,  mTrue.getJSONObject("actions").getBoolean("dsym_upload"))
        val (_, mFalse) = writeAndRead(
            outFile = tempFolder.newFile("b.json"),
            dsymUpload = false,
        )
        assertEquals(false, mFalse.getJSONObject("actions").getBoolean("dsym_upload"))
    }

    @Test
    fun `actions values are strict Booleans`() {
        // The fastlane reader's handled_by_other? uses strict ==
        // true. JSONObject preserves Boolean type through put/get;
        // a regression that wrote them as "true"/"false" strings
        // (e.g. via String.valueOf) would fail this test AND
        // silently break the reader.
        val (_, m) = writeAndRead(
            mappingUpload = true,
            depsCollection = true,
            timings = true,
            sizeAnalysis = false,
        )
        val actions = m.getJSONObject("actions")
        assertTrue(actions["mapping_upload"] is Boolean)
        assertTrue(actions["deps_collection"] is Boolean)
        assertTrue(actions["timings"] is Boolean)
        assertTrue(actions["size_analysis"] is Boolean)
    }

    @Test
    fun `each action value round-trips faithfully`() {
        val (_, m) = writeAndRead(
            mappingUpload = true,
            depsCollection = false,
            timings = true,
            sizeAnalysis = false,
        )
        val a = m.getJSONObject("actions")
        assertEquals(true,  a.getBoolean("mapping_upload"))
        assertEquals(false, a.getBoolean("deps_collection"))
        assertEquals(true,  a.getBoolean("timings"))
        assertEquals(false, a.getBoolean("size_analysis"))
    }

    // ── Build-identity fields — required for fastlane matching ──

    @Test
    fun `produced_at_ms is the documented numeric epoch`() {
        // The fastlane reader's staleness filter compares
        // `Time.now.to_f` against produced_at_ms / 1000. A wrong
        // unit (seconds vs ms) would either always-stale or
        // never-stale the manifest. Pin the unit.
        val (_, m) = writeAndRead(producedAtMs = 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, m.getLong("produced_at_ms"))
    }

    @Test
    fun `version_name and version_code emitted as strings when given`() {
        val (_, m) = writeAndRead(versionName = "1.2.3", versionCode = "42")
        assertEquals("1.2.3", m.getString("version_name"))
        assertEquals("42",   m.getString("version_code"))
    }

    @Test
    fun `version_name omitted when null`() {
        // Absence is signalled by missing key — the fastlane
        // reader's identity-match treats absence as "skip the
        // identity match for this build" rather than rejecting
        // the manifest outright.
        val (_, m) = writeAndRead(versionName = null)
        assertFalse(m.has("version_name"))
    }

    @Test
    fun `build_id is emitted when given`() {
        val (_, m) = writeAndRead(
            buildId = "b27501e9-5a6a-3227-9f7d-f4696b48739c",
        )
        assertEquals(
            "b27501e9-5a6a-3227-9f7d-f4696b48739c",
            m.getString("build_id"),
        )
    }

    @Test
    fun `build_id is omitted when null`() {
        val (_, m) = writeAndRead(buildId = null)
        assertFalse(m.has("build_id"))
    }

    // ── Filesystem behaviour ─────────────────────────────────────

    @Test
    fun `creates parent directory if absent`() {
        // The Gradle wiring puts the file under
        // intermediates/bugsee/<variant>/ — those parent dirs may
        // not exist on a fresh build. The writer MUST mkdirs
        // rather than fail.
        val nested = java.io.File(
            tempFolder.newFolder("a", "b", "c"),
            "build-actions.json",
        )
        // Delete the auto-created dir so we exercise the mkdirs
        // path.
        nested.parentFile.deleteRecursively()
        BundleUploadTask.writeBuildActionsManifest(
            outFile = nested,
            pluginVersion = "1",
            buildId = null,
            producedAtMs = 1L,
            versionName = null,
            versionCode = null,
            dsymUpload = false,
            mappingUpload = false,
            depsCollection = false,
            timings = false,
            sizeAnalysis = false,
        )
        assertTrue("manifest file must exist after write", nested.isFile)
    }

    @Test
    fun `overwrites existing manifest`() {
        // A re-run of the upload task must replace the previous
        // build's manifest. If we appended, the file would
        // contain two concatenated JSON objects and fastlane
        // would skip with `JSON::ParserError` (silently — soft
        // fail by design).
        val out = tempFolder.newFile("build-actions.json")
        BundleUploadTask.writeBuildActionsManifest(
            outFile = out,
            pluginVersion = "1.0", buildId = null,
            producedAtMs = 1L, versionName = "1", versionCode = "1",
            dsymUpload = false, mappingUpload = false,
            depsCollection = true, timings = true, sizeAnalysis = false,
        )
        BundleUploadTask.writeBuildActionsManifest(
            outFile = out,
            pluginVersion = "2.0", buildId = null,
            producedAtMs = 2L, versionName = "2", versionCode = "2",
            dsymUpload = false, mappingUpload = true,
            depsCollection = false, timings = false, sizeAnalysis = true,
        )
        val parsed = parseManifest(out)
        assertEquals("2.0", parsed.getString("producer_version"))
        assertEquals(2L,   parsed.getLong("produced_at_ms"))
        assertEquals("2",  parsed.getString("version_name"))
        assertEquals(true,
            parsed.getJSONObject("actions").getBoolean("mapping_upload"))
    }

    @Test
    fun `result parses as a JSON Hash with a top-level object`() {
        // Reader implements safe_parse — it rejects JSON arrays
        // at the top level. Pin that our output is an object.
        val (file, _) = writeAndRead()
        val raw = file.readText()
        assertTrue("manifest must start with {", raw.startsWith("{"))
        assertTrue("manifest must end with }",   raw.trim().endsWith("}"))
    }
}
