package com.bugsee.android.gradle.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

/**
 * Pins the contract of [BugseeBuildIdDeriver]: same inputs → same
 * UUID; different inputs → different UUIDs; output is RFC 4122
 * UUID-shaped. The shared deriver is load-bearing because both
 * [BugseeManifestTask] (manifest meta-data channel) and
 * [BugseeBuildIdResolveTask] (asset channel) call into it for the
 * fallback path — they must agree byte-for-byte or the SDK's
 * asset-first / manifest-fallback reader would observe a mismatch
 * that looks like a regression.
 */
class BugseeBuildIdDeriverTest {

    @Test
    fun fallback_sameInputs_sameUuid() {
        val manifest = "<manifest package='com.example' />".toByteArray()
        val u1 = BugseeBuildIdDeriver.deriveFromFallbackInputs(manifest, "debug", "4.0.0")
        val u2 = BugseeBuildIdDeriver.deriveFromFallbackInputs(manifest, "debug", "4.0.0")
        assertEquals(u1, u2)
    }

    @Test
    fun fallback_manifestChange_changesUuid() {
        val u1 = BugseeBuildIdDeriver.deriveFromFallbackInputs(
            "<manifest package='com.example' />".toByteArray(),
            "debug", "4.0.0",
        )
        val u2 = BugseeBuildIdDeriver.deriveFromFallbackInputs(
            "<manifest package='com.example2' />".toByteArray(),
            "debug", "4.0.0",
        )
        assertNotEquals(u1, u2)
    }

    @Test
    fun fallback_variantChange_changesUuid() {
        val manifest = "<m/>".toByteArray()
        val u1 = BugseeBuildIdDeriver.deriveFromFallbackInputs(manifest, "debug", "4.0.0")
        val u2 = BugseeBuildIdDeriver.deriveFromFallbackInputs(manifest, "release", "4.0.0")
        assertNotEquals(
            "assembleDebug and assembleRelease must produce different UUIDs " +
                "— they're different artefacts that round-trip to different mappings",
            u1, u2,
        )
    }

    @Test
    fun fallback_pluginVersionChange_changesUuid() {
        val manifest = "<m/>".toByteArray()
        val u1 = BugseeBuildIdDeriver.deriveFromFallbackInputs(manifest, "debug", "4.0.0")
        val u2 = BugseeBuildIdDeriver.deriveFromFallbackInputs(manifest, "debug", "4.0.1")
        assertNotEquals(
            "a plugin upgrade applied to the same workspace must produce a " +
                "fresh UUID — wire format or symbol-extraction rules can change " +
                "and 'new plugin = new build identity' is the safer contract",
            u1, u2,
        )
    }

    @Test
    fun fallback_nonAscii_variantName_doesNotOverflow() {
        // The arraycopy in the deriver sized its buffer from
        // String.length (char count) until commit 8f6b9faa3's follow-up
        // — for any non-ASCII char in variantName or pluginVersion the
        // UTF-8 byte count is larger and the buffer would overflow.
        // This test pins the UTF-8-aware sizing.
        val nonAsciiVariant = "rüsée"      // ü + é → 2 multi-byte chars
        val u = BugseeBuildIdDeriver.deriveFromFallbackInputs(
            "<m/>".toByteArray(),
            nonAsciiVariant,
            "4.0.0",
        )
        // No exception thrown = the buffer was sized correctly.
        // Sanity-check that we got a real UUID back.
        assertEquals(36, u.toString().length)
    }

    @Test
    fun mappingFile_sameContent_sameUuid() {
        val mapping = "com.example.Foo -> a:\nvoid bar() -> a\n".toByteArray()
        val u1 = BugseeBuildIdDeriver.deriveFromMappingFile(mapping)
        val u2 = BugseeBuildIdDeriver.deriveFromMappingFile(mapping)
        assertEquals(u1, u2)
    }

    @Test
    fun mappingFile_differentContent_differentUuid() {
        val u1 = BugseeBuildIdDeriver.deriveFromMappingFile(
            "com.example.Foo -> a:\nvoid bar() -> a\n".toByteArray()
        )
        val u2 = BugseeBuildIdDeriver.deriveFromMappingFile(
            "com.example.Foo -> a:\nvoid baz() -> a\n".toByteArray()
        )
        assertNotEquals(
            "Bytecode change → R8 produces a different mapping → UUID must " +
                "differ. This is the property a code-only edit needs to satisfy " +
                "for symbolication round-trip correctness.",
            u1, u2,
        )
    }

    @Test
    fun mappingFile_versus_fallback_produceDifferentUuids() {
        // A consumer who has R8 enabled in CI and then disables it
        // locally would see different UUIDs from the two paths. That
        // is *intended* — the UUIDs serve different roles. Pin so a
        // future refactor doesn't accidentally collapse them.
        val mapping = "anything".toByteArray()
        val viaMapping = BugseeBuildIdDeriver.deriveFromMappingFile(mapping)
        val viaFallback = BugseeBuildIdDeriver.deriveFromFallbackInputs(
            mapping, "debug", "4.0.0",
        )
        assertNotEquals(viaMapping, viaFallback)
    }

    @Test
    fun output_isCanonicalUuidString() {
        val u = BugseeBuildIdDeriver.deriveFromFallbackInputs(
            "abc".toByteArray(), "debug", "1.0",
        )
        // Round-trip through UUID.fromString to verify the canonical
        // 8-4-4-4-12 form (the only form java.util.UUID.fromString
        // accepts).
        val parsed = UUID.fromString(u.toString())
        assertEquals(u, parsed)
    }
}
