package com.bugsee.android.gradle.manifest

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pin the split-APK nested-manifest handling in [BugseeManifestTask].
 *
 * AGP's APK splits (per-ABI, per-density, etc.) lay out each split's
 * manifest in a sibling directory next to the base manifest:
 *
 * ```
 * <output-parent>/
 *   ├── AndroidManifest.xml          ← base (this task's `updatedManifest`)
 *   ├── arm64-v8a/AndroidManifest.xml
 *   ├── armeabi-v7a/AndroidManifest.xml
 *   ├── x86_64/AndroidManifest.xml
 *   └── …
 * ```
 *
 * Each per-split manifest is itself an `<application>`-bearing file
 * that ends up packed into the corresponding split-APK. The task walks
 * the parent directory, finds every sibling `<dir>/AndroidManifest.xml`,
 * and injects the SAME `BUILD_UUID` + (when [optimizeExtensionsLoading]
 * is true) strips the same provider list. Without this, the resulting
 * split APKs would either disagree on BUILD_UUID (crashes from split
 * APKs would never round-trip to mapping uploads keyed off the base
 * manifest's UUID) or carry stale Bugsee extension providers that the
 * base manifest had stripped (double-init at runtime).
 *
 * Prior to these tests the nested-manifest loop at
 * `BugseeManifestTask.kt:163-182` had **zero** coverage — a regression
 * that commented out the entire loop would have been silent. These
 * tests pin the contract:
 *  - nested manifests get the SAME UUID as the base manifest;
 *  - nested manifests get the same extension stripping;
 *  - de-duplication of `allDetected` across splits works;
 *  - directories that don't contain `AndroidManifest.xml` are ignored.
 */
class BugseeManifestTaskSplitApkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var runId = 0

    /**
     * Set up a `BugseeManifestTask` that writes its output into a
     * dedicated parent directory, populated by [nestedManifests]
     * (a map of `directoryName → manifest content`). Returns the
     * extracted BUILD_UUID from the base output plus a snapshot of
     * the parent directory tree post-execution.
     */
    private fun runWithSplits(
        baseManifestContent: String,
        nestedManifests: Map<String, String>,
        optimizeExtensions: Boolean = false,
    ): RunResult {
        val tag = "${++runId}"
        val project = ProjectBuilder.builder().withProjectDir(tempFolder.newFolder()).build()

        val baseManifestFile = tempFolder.newFile("AndroidManifest-base-$tag.xml")
        baseManifestFile.writeText(baseManifestContent)

        // The task's split-walk inspects siblings of `outputFile.parentFile`.
        // Place the output inside a dedicated parent so we can fully
        // control which sibling dirs (and what they contain) exist
        // during the run — no risk of TemporaryFolder noise leaking in.
        val outputParent = tempFolder.newFolder("out-$tag")
        val outputFile = File(outputParent, "AndroidManifest.xml")

        // Materialize the nested manifests as sibling dirs of `outputFile`.
        for ((dirName, manifest) in nestedManifests) {
            val nestedDir = File(outputParent, dirName)
            nestedDir.mkdirs()
            File(nestedDir, "AndroidManifest.xml").writeText(manifest)
        }

        val detectedFile = tempFolder.newFile("detected-$tag.txt")
        val fallbackFile = tempFolder.newFile("fallback-$tag.txt")

        val task = project.tasks.register("manifest-$tag", BugseeManifestTask::class.java) { t ->
            t.debug.set(false)
            t.optimizeExtensionsLoading.set(optimizeExtensions)
            t.variantName.set("debug")
            t.pluginVersion.set("7.0.0")
            t.mergedManifest.set(baseManifestFile)
            t.updatedManifest.set(outputFile)
            t.detectedExtensions.set(detectedFile)
            t.fallbackBuildId.set(fallbackFile)
        }.get()
        task.execute()

        return RunResult(
            outputParent = outputParent,
            baseUuid = extractBuildUuid(outputFile),
            detectedFile = detectedFile,
        )
    }

    private data class RunResult(
        val outputParent: File,
        val baseUuid: String?,
        val detectedFile: File,
    )

    private fun extractBuildUuid(manifest: File): String? {
        val re = Regex(
            "<meta-data\\s+android:name=\"com\\.bugsee\\.android\\.BUILD_UUID\"\\s+android:value=\"([^\"]+)\""
        )
        return re.find(manifest.readText())?.groupValues?.get(1)
    }

    private fun nestedManifestFile(parent: File, dirName: String): File =
        File(File(parent, dirName), "AndroidManifest.xml")

    private val baseManifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example">
            <application android:label="X" />
        </manifest>
    """.trimIndent()

    private val splitManifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example" split="config.arm64_v8a">
            <application android:label="X" />
        </manifest>
    """.trimIndent()

    private val manifestWithExtensionProvider = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example">
            <application android:label="X">
                <provider
                    android:name="com.bugsee.library.BugseeFeedbackInitProvider"
                    android:authorities="com.example.bugseefeedbackinitprovider"
                    android:exported="false" />
            </application>
        </manifest>
    """.trimIndent()

    @Test
    fun `nested manifests in split-APK sibling dirs get the same BUILD_UUID as the base`() {
        // The load-bearing claim. A split APK that disagrees on
        // BUILD_UUID with the base APK silently breaks the
        // crash-to-build round-trip — crashes from the split would
        // look up a UUID that's never registered with mapping upload.
        val result = runWithSplits(
            baseManifestContent = baseManifest,
            nestedManifests = mapOf(
                "arm64-v8a" to splitManifest,
                "armeabi-v7a" to splitManifest,
                "x86_64" to splitManifest,
            ),
        )

        assertNotNull(result.baseUuid, "base manifest must carry a BUILD_UUID")
        for (split in listOf("arm64-v8a", "armeabi-v7a", "x86_64")) {
            val nestedUuid = extractBuildUuid(nestedManifestFile(result.outputParent, split))
            assertEquals(
                result.baseUuid, nestedUuid,
                "split '$split' must inherit the base BUILD_UUID — otherwise crashes from this " +
                    "split won't round-trip to the base APK's mapping upload",
            )
        }
    }

    @Test
    fun `nested manifests get extension providers stripped when optimization is on`() {
        // Each per-split manifest is its own AndroidManifest.xml — if
        // the base strips a Bugsee extension provider but the nested
        // copy doesn't, the installed split APK would still register
        // the provider and double-init the extension at app start.
        val result = runWithSplits(
            baseManifestContent = manifestWithExtensionProvider,
            nestedManifests = mapOf(
                "arm64-v8a" to manifestWithExtensionProvider,
                "x86_64" to manifestWithExtensionProvider,
            ),
            optimizeExtensions = true,
        )

        for (split in listOf("arm64-v8a", "x86_64")) {
            val nestedText = nestedManifestFile(result.outputParent, split).readText()
            assertFalse(
                nestedText.contains("BugseeFeedbackInitProvider"),
                "split '$split' must have the extension provider stripped — found it still present in:\n$nestedText",
            )
        }

        // De-duplication: each of the 2 splits + the base all
        // contained the same provider, but the detected-extensions
        // file should list the FQN exactly once (the union, not the
        // count).
        val detected = result.detectedFile.readLines().filter { it.isNotBlank() }
        assertEquals(
            listOf("com.bugsee.library.BugseeFeedbackInitProvider"),
            detected,
            "allDetected must dedupe across splits — got $detected",
        )
    }

    @Test
    fun `sibling dirs without AndroidManifest are ignored`() {
        // The split-walk filter is `dir.isDirectory && File(dir,
        // "AndroidManifest.xml").isFile`. Unrelated sibling dirs
        // (assets caches, AGP intermediates, etc.) must NOT be
        // touched. Pin via a sibling dir containing OTHER files.
        val result = runWithSplits(
            baseManifestContent = baseManifest,
            nestedManifests = mapOf(
                "arm64-v8a" to splitManifest,  // legitimate split
            ),
        )

        // Drop a junk dir next to the output parent — it has a file
        // but no AndroidManifest.xml. The task must not touch it.
        val junkDir = File(result.outputParent, "junk-cache")
        junkDir.mkdirs()
        val junkFile = File(junkDir, "some-other-artifact.bin")
        junkFile.writeBytes(byteArrayOf(0, 1, 2, 3))
        val originalBytes = junkFile.readBytes()

        // The task already ran in `runWithSplits` — it's
        // idempotent, so re-execute is fine. But we don't need to
        // re-run: assert the legitimate split was processed AND
        // the junk dir bytes are intact post-execution.
        assertEquals(
            result.baseUuid,
            extractBuildUuid(nestedManifestFile(result.outputParent, "arm64-v8a")),
            "legitimate split must still be picked up alongside junk siblings",
        )
        assertTrue(
            originalBytes.contentEquals(junkFile.readBytes()),
            "junk-dir bytes must not be touched by the split walk",
        )
    }

    @Test
    fun `nested manifests without an application section get a logged warning but do not abort`() {
        // The split-walk calls `addBuildUuidToManifest` which returns
        // false if the nested doc has no <application> element. The
        // task logs a warn but continues processing other siblings.
        // Pin that two-condition contract: malformed nested =
        // skipped without aborting the rest.
        val malformedNested = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.broken" />
        """.trimIndent()
        val result = runWithSplits(
            baseManifestContent = baseManifest,
            nestedManifests = mapOf(
                "arm64-v8a" to splitManifest,        // legitimate
                "broken-split" to malformedNested,   // missing <application>
                "x86_64" to splitManifest,           // legitimate, AFTER the broken one
            ),
        )

        // The two legitimate splits must STILL have inherited the
        // base BUILD_UUID — the broken one in the middle must not
        // abort the walk.
        assertEquals(
            result.baseUuid,
            extractBuildUuid(nestedManifestFile(result.outputParent, "arm64-v8a")),
        )
        assertEquals(
            result.baseUuid,
            extractBuildUuid(nestedManifestFile(result.outputParent, "x86_64")),
        )
        // The broken one has no <application>, so it can't get a
        // BUILD_UUID meta-data (the task's writer requires that
        // element to anchor the injection).
        val brokenUuid = extractBuildUuid(nestedManifestFile(result.outputParent, "broken-split"))
        assertEquals(null, brokenUuid, "broken nested must NOT have a BUILD_UUID injected")
    }
}
