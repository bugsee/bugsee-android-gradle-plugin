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
    ): String {
        val tag = "${variant}-${pluginVer}-${++runId}"
        val project = ProjectBuilder.builder().withProjectDir(tempFolder.newFolder()).build()
        val manifestFile = tempFolder.newFile("AndroidManifest-$tag.xml")
        manifestFile.writeText(manifestContent)
        val outputFile = tempFolder.newFile("AndroidManifest-$tag.out.xml")
        val detectedFile = tempFolder.newFile("detected-$tag.txt")

        val task = project.tasks.register("manifest-$tag", BugseeManifestTask::class.java) { t ->
            t.debug.set(false)
            t.optimizeExtensionsLoading.set(false)
            t.variantName.set(variant)
            t.pluginVersion.set(pluginVer)
            t.mergedManifest.set(manifestFile)
            t.updatedManifest.set(outputFile)
            t.detectedExtensions.set(detectedFile)
        }.get()
        task.execute()

        return extractBuildUuid(outputFile)
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
    fun `derived UUID is a valid RFC 4122 UUID`() {
        // Defensive: the value is consumed by viewer / appserver code
        // that parses it as a UUID. Make sure our derivation produces
        // a string that round-trips through `UUID.fromString`.
        val u = runOnce(sampleManifest, variant = "debug", pluginVer = "7.0.0")
        UUID.fromString(u)  // throws IllegalArgumentException on malformed
    }
}
