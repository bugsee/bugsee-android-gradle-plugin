package com.bugsee.android.gradle.manifest

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pin the BUILD_UUID determinism contract on [BugseeManifestTask].
 *
 * The earlier implementation used `UUID.randomUUID()` inside the
 * `@TaskAction`. That made every fresh task-action run emit a
 * different UUID — fine within a single Gradle invocation (AGP's
 * artifact-transform graph runs the task once and caches the
 * output), but broken across separate invocations:
 *
 *     ./gradlew clean assembleDebug   # APK ships with UUID A
 *     ./gradlew bundleDebug           # AAB ships with UUID B
 *
 * Mapping / NDK symbol uploads keyed off one UUID couldn't be
 * looked up by crashes keyed off the other — the round-trip
 * silently broke for a common Fastlane two-lane CI pattern.
 *
 * The fix derives the UUID from the merged-manifest bytes +
 * variant name + plugin version via [UUID.nameUUIDFromBytes].
 * These tests pin:
 *  - Same inputs → same UUID across runs (the load-bearing
 *    contract that resolves the assemble/bundle divergence).
 *  - Different manifest bytes → different UUID.
 *  - Different variant → different UUID (assembleDebug vs
 *    assembleRelease must produce different IDs even on the
 *    same workspace).
 *  - Different plugin version → different UUID (a plugin
 *    upgrade is treated as a fresh build identity, since wire
 *    contracts and symbol-extraction rules can change).
 */
class BugseeManifestTaskUuidDeterminismTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Per-call counter so repeated `runOnce` invocations with the same
    // variant + version don't collide on temp-file names — JUnit's
    // `tempFolder.newFile(name)` throws if the file already exists,
    // and the determinism test deliberately runs the same inputs twice.
    private var runId = 0

    /**
     * Set up a `BugseeManifestTask` instance with a real merged
     * manifest on disk and a separate output location. Returns the
     * extracted BUILD_UUID after running the task action.
     */
    private fun runOnce(
        manifestContent: String,
        variant: String,
        pluginVer: String,
    ): String = runOnceCapturing(manifestContent, variant, pluginVer).first

    /**
     * Variant of [runOnce] that also returns the raw output-manifest
     * bytes — for tests that pin both the UUID and the byte-identity
     * of the rewritten manifest (the latter is what makes Gradle's
     * up-to-date check propagate correctly to downstream tasks).
     */
    private fun runOnceCapturing(
        manifestContent: String,
        variant: String,
        pluginVer: String,
    ): Pair<String, ByteArray> {
        // Use a tag based on the index alone (not variant/version) so
        // unicode variant/version values cannot produce invalid file
        // names on stricter filesystems.
        val tag = "${++runId}"
        val project = ProjectBuilder.builder().withProjectDir(tempFolder.newFolder()).build()
        val manifestFile = tempFolder.newFile("AndroidManifest-$tag.xml")
        manifestFile.writeText(manifestContent)
        val outputFile = tempFolder.newFile("AndroidManifest-$tag.out.xml")
        val detectedFile = tempFolder.newFile("detected-$tag.txt")

        val fallbackFile = tempFolder.newFile("fallback-$tag.txt")
        val task = project.tasks.register("manifest-$tag", BugseeManifestTask::class.java) { t ->
            t.debug.set(false)
            t.optimizeExtensionsLoading.set(false)
            t.variantName.set(variant)
            t.pluginVersion.set(pluginVer)
            t.mergedManifest.set(manifestFile)
            t.updatedManifest.set(outputFile)
            t.detectedExtensions.set(detectedFile)
            t.fallbackBuildId.set(fallbackFile)
        }.get()
        task.execute()

        return extractBuildUuid(outputFile) to outputFile.readBytes()
    }

    /**
     * Parse the BUILD_UUID `<meta-data>` value out of the updated
     * manifest. The task always writes one — if it's absent, that's
     * itself a failure of the task action.
     */
    private fun extractBuildUuid(manifest: File): String {
        val text = manifest.readText()
        val re = Regex(
            "<meta-data\\s+android:name=\"com\\.bugsee\\.android\\.BUILD_UUID\"\\s+android:value=\"([^\"]+)\""
        )
        val match = re.find(text)
        assertNotNull(match, "BUILD_UUID meta-data not found in updated manifest:\n$text")
        return match.groupValues[1]
    }

    private val sampleManifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example">
            <application android:label="X" />
        </manifest>
    """.trimIndent()

    @Test
    fun `same inputs produce the same UUID across separate task runs`() {
        // The load-bearing claim: running the task twice with
        // byte-identical inputs (manifest bytes + variant +
        // plugin-version) must produce the same BUILD_UUID. Earlier
        // (random) implementation would have made these two runs
        // emit two different UUIDs, breaking assemble→bundle
        // mapping-upload round-trip.
        val u1 = runOnce(sampleManifest, variant = "debug", pluginVer = "7.0.0")
        val u2 = runOnce(sampleManifest, variant = "debug", pluginVer = "7.0.0")
        assertEquals(u1, u2, "same-input runs must produce the same UUID")
    }

    @Test
    fun `different manifest bytes produce different UUIDs`() {
        // A meaningful change to the merged manifest (e.g. the user
        // added a permission, AGP merged in a new dependency) IS a
        // new build — and should get a new UUID. Pins the
        // bytes-component of the derivation.
        val u1 = runOnce(sampleManifest, variant = "debug", pluginVer = "7.0.0")
        val u2 = runOnce(
            sampleManifest.replace("com.example", "com.example.other"),
            variant = "debug",
            pluginVer = "7.0.0",
        )
        assertNotEquals(u1, u2, "different manifest bytes must produce different UUIDs")
    }

    @Test
    fun `different variants produce different UUIDs on the same workspace`() {
        // `assembleDebug` and `assembleRelease` of the same project
        // are different artefacts that ship side-by-side. They MUST
        // get different UUIDs so the appserver's crash-to-build
        // lookup can route to the right one.
        val uDebug = runOnce(sampleManifest, variant = "debug", pluginVer = "7.0.0")
        val uRelease = runOnce(sampleManifest, variant = "release", pluginVer = "7.0.0")
        assertNotEquals(uDebug, uRelease, "different variants must produce different UUIDs")
    }

    @Test
    fun `different plugin versions produce different UUIDs`() {
        // A plugin upgrade can change wire-format / symbol-extraction
        // contracts. Treat upgrade-then-rebuild as a fresh build
        // identity rather than (potentially) re-using a UUID that
        // points at the previous shape.
        val u1 = runOnce(sampleManifest, variant = "debug", pluginVer = "7.0.0-beta7")
        val u2 = runOnce(sampleManifest, variant = "debug", pluginVer = "7.0.0-beta8")
        assertNotEquals(u1, u2, "different plugin versions must produce different UUIDs")
    }

    @Test
    fun `derived UUID is a valid RFC 4122 v3 (MD5-named) UUID`() {
        // Defensive: the value is consumed by viewer / appserver code
        // that parses it as a UUID. Make sure our derivation produces
        // a string that round-trips through `UUID.fromString` AND that
        // it has the version=3 bits set — i.e. it was actually
        // produced by `UUID.nameUUIDFromBytes` rather than (say) a
        // hardcoded literal or a `UUID.randomUUID()` slipped back in.
        //
        // Pinning the version bits closes a loophole the previous
        // version of this test left open: a mutation that replaced
        // the entire `@TaskAction` body with
        // `outputFile.writeText("<meta-data ... value='00000000-0000-...'/>")`
        // (or `UUID.randomUUID().toString()`) would have passed the
        // old "doesn't throw" check, even though it broke the
        // determinism contract the rest of this test class pins.
        val u = runOnce(sampleManifest, variant = "debug", pluginVer = "7.0.0")
        val parsed = UUID.fromString(u)  // throws on malformed
        assertEquals(
            3,
            parsed.version(),
            "BUILD_UUID must be a name-derived (v3) UUID — got version ${parsed.version()} ($u)",
        )
    }

    @Test
    fun `non-ASCII variant and plugin-version names do not overflow the UUID buffer`() {
        // The UUID-input buffer is sized from UTF-8 byte counts (NOT
        // String.length). For any non-ASCII character — BMP non-ASCII
        // (`é`, CJK) or surrogate-pair characters — the UTF-8 encoding
        // is wider than the char count. A previous revision of this
        // task sized the buffer from `.length` and would throw
        // ArrayIndexOutOfBoundsException at execution for any
        // non-ASCII variant or plugin-version. AGP variant names are
        // Java identifiers in practice (so this is defence-in-depth),
        // but we still want a regression guard.
        //
        // Run two representative samples:
        //  - BMP non-ASCII chars (CJK in variant, accented in version)
        //  - Surrogate pair (emoji)
        // and just assert that the task doesn't throw and that the
        // result round-trips as a valid UUID.
        val cjk = runOnce(sampleManifest, variant = "debug中文", pluginVer = "7.0.0-béta")
        UUID.fromString(cjk)

        val emoji = runOnce(sampleManifest, variant = "debug🚀", pluginVer = "7.0.0")
        UUID.fromString(emoji)

        // The two should still be distinct from each other AND from
        // the ASCII-only baseline — i.e. the variant/version
        // contributions actually made it into the hash.
        val ascii = runOnce(sampleManifest, variant = "debug", pluginVer = "7.0.0")
        assertNotEquals(ascii, cjk, "CJK variant must produce a distinct UUID from ASCII baseline")
        assertNotEquals(ascii, emoji, "emoji variant must produce a distinct UUID from ASCII baseline")
        assertNotEquals(cjk, emoji, "CJK and emoji variants must produce distinct UUIDs")
    }

    @Test
    fun `same inputs produce byte-identical output manifests`() {
        // Stronger version of `same inputs produce the same UUID`.
        // The UUID matching is necessary but not sufficient for the
        // incremental-build behavior the deterministic-UUID fix
        // restored. Downstream tasks (bytecode instrumentation,
        // upload) consume `updatedManifest` as an `@InputFile`. For
        // those tasks to be marked UP-TO-DATE on a second run, the
        // entire updated manifest file must hash to the same value —
        // not just the BUILD_UUID `<meta-data>` value inside it. A
        // hypothetical future regression that introduced HashMap-
        // iteration-order in XML attribute serialization (or any
        // other non-determinism in `ManifestModifier.writeDocument`)
        // would leave the BUILD_UUID stable but the surrounding bytes
        // unstable; this test would catch that.
        val (uuid1, bytes1) = runOnceCapturing(sampleManifest, variant = "debug", pluginVer = "7.0.0")
        val (uuid2, bytes2) = runOnceCapturing(sampleManifest, variant = "debug", pluginVer = "7.0.0")
        assertEquals(uuid1, uuid2)
        assertTrue(
            bytes1.contentEquals(bytes2),
            "same-input runs must produce byte-identical output manifests; " +
                "got ${bytes1.size} vs ${bytes2.size} bytes",
        )
    }
}
