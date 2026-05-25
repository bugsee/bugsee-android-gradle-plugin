package com.bugsee.android.gradle.manifest

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.UUID

/**
 * Task that injects a unique BUILD_UUID into the merged AndroidManifest.xml
 * and (when [optimizeExtensionsLoading] is `true`) strips every Bugsee
 * extension `<provider>` from the merged manifest.
 *
 * This task operates on the merged manifest via AGP's artifact transformation API,
 * meaning it receives the manifest as input, modifies it in place, and outputs the same file.
 *
 * The list of stripped extension init-provider FQNs is written to
 * [detectedExtensions] so the downstream bytecode visitor knows which
 * `register<Name>Extension()` calls to inline into the SDK's
 * `BugseeInitProvider.initializeExtensions()`.
 */
abstract class BugseeManifestTask : DefaultTask() {

    @get:Input
    abstract val debug: Property<Boolean>

    /**
     * When `true` (default), every `<provider>` matching
     * `*.Bugsee<Name>InitProvider` is stripped from the merged manifest
     * and its FQN recorded in [detectedExtensions]. When `false`, no
     * stripping occurs and the detection file is written empty.
     */
    @get:Input
    abstract val optimizeExtensionsLoading: Property<Boolean>

    /**
     * AGP variant name (`debug`, `freeRelease`, etc.) for this task's
     * variant. Mixed into the deterministic BUILD_UUID derivation so
     * `assembleDebug` and `assembleRelease` of the same workspace
     * produce DIFFERENT UUIDs — they're different artefacts and must
     * round-trip to different crash/mapping uploads.
     */
    @get:Input
    abstract val variantName: Property<String>

    /**
     * Plugin version (`PLUGIN_VERSION` constant). Mixed into the UUID
     * derivation so a plugin upgrade applied to the same workspace
     * produces a fresh UUID — the upload pipeline's wire shape and
     * symbol-extraction rules can change across plugin releases, and
     * the safest contract is "new plugin = new build identity."
     */
    @get:Input
    abstract val pluginVersion: Property<String>

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val updatedManifest: RegularFileProperty

    /**
     * Output file listing one extension init-provider FQN per line, in
     * document order, for every provider stripped from the merged
     * manifest. Empty when [optimizeExtensionsLoading] is `false` or no
     * extension providers were merged in.
     */
    @get:OutputFile
    abstract val detectedExtensions: RegularFileProperty

    @TaskAction
    fun execute() {
        val isDebug = debug.get()
        val manifestFile = mergedManifest.get().asFile
        val outputFile = updatedManifest.get().asFile
        val detectedFile = detectedExtensions.get().asFile

        if (!manifestFile.isFile) {
            logger.warn("Bugsee: Manifest file not found: ${manifestFile.absolutePath}")
            writeDetectedExtensions(detectedFile, emptyList())
            return
        }

        // Derive the BUILD_UUID DETERMINISTICALLY from the inputs
        // (merged manifest bytes + variant name + plugin version)
        // BEFORE copying / mutating the output. Two reasons:
        //
        // 1. Cross-invocation stability. `assembleDebug` and
        //    `bundleDebug` from the same workspace land in this task
        //    via the same AGP MERGED_MANIFEST transform; within a
        //    single invocation the task runs once and both downstreams
        //    consume the same output. But across SEPARATE invocations
        //    (clean + assemble, later clean + bundle, common in
        //    Fastlane two-lane CI flows), the task re-runs. Under the
        //    previous `UUID.randomUUID()` the APK got UUID A, the AAB
        //    later got UUID B, and mapping/symbol uploads keyed off
        //    one UUID could not be looked up by crashes keyed off the
        //    other.
        //
        // 2. Up-to-date check correctness. With a deterministic UUID,
        //    re-running the task with unchanged inputs produces a
        //    byte-identical updated manifest — so Gradle's incremental
        //    build correctly marks both this task and downstream
        //    bytecode-instrumentation steps UP-TO-DATE.
        //
        // `UUID.nameUUIDFromBytes` produces a v3 (MD5-based, name-
        // derived) UUID per RFC 4122; the version bits don't matter
        // for our use, only that the value is UUID-shaped, stable
        // across runs of the same inputs, and distinct across inputs.
        val manifestBytes = manifestFile.readBytes()
        val uuidInput = ByteArray(
            manifestBytes.size + variantName.get().length + pluginVersion.get().length + 2
        )
        System.arraycopy(manifestBytes, 0, uuidInput, 0, manifestBytes.size)
        var offset = manifestBytes.size
        uuidInput[offset++] = '|'.code.toByte()
        val variantBytes = variantName.get().toByteArray(Charsets.UTF_8)
        System.arraycopy(variantBytes, 0, uuidInput, offset, variantBytes.size)
        offset += variantBytes.size
        uuidInput[offset++] = '|'.code.toByte()
        val pluginVersionBytes = pluginVersion.get().toByteArray(Charsets.UTF_8)
        System.arraycopy(pluginVersionBytes, 0, uuidInput, offset, pluginVersionBytes.size)
        val buildUUID = UUID.nameUUIDFromBytes(uuidInput).toString()

        // Copy input to output location if different
        if (manifestFile.absolutePath != outputFile.absolutePath) {
            manifestFile.copyTo(outputFile, overwrite = true)
        }

        if (isDebug) logger.warn("Bugsee: Adding buildUUID $buildUUID to ${outputFile.path}")

        if (!ManifestModifier.addBuildUuidToManifest(outputFile, buildUUID)) {
            logger.warn("Bugsee: <application> section not found in manifest: ${outputFile.path}")
            writeDetectedExtensions(detectedFile, emptyList())
            return
        }

        val allDetected = mutableListOf<String>()
        val optimize = optimizeExtensionsLoading.getOrElse(true)
        if (optimize) {
            val detected = ManifestModifier.removeExtensionInitProviders(outputFile)
            if (isDebug && detected.isNotEmpty()) {
                logger.warn(
                    "Bugsee: Stripped ${detected.size} extension provider(s) from " +
                        "${outputFile.path}: ${detected.joinToString(", ")}"
                )
            }
            allDetected.addAll(detected)
        }

        // Handle split APK scenarios: check for nested directories with AndroidManifest.xml
        val parentDir = outputFile.parentFile
        if (parentDir != null && parentDir.isDirectory) {
            parentDir.listFiles()?.filter { dir ->
                dir.isDirectory && File(dir, "AndroidManifest.xml").isFile
            }?.forEach { dir ->
                val nestedManifest = File(dir, "AndroidManifest.xml")
                if (!ManifestModifier.addBuildUuidToManifest(nestedManifest, buildUUID)) {
                    logger.warn("Bugsee: <application> section not found in nested manifest: ${nestedManifest.path}")
                }
                if (optimize) {
                    val nestedDetected = ManifestModifier.removeExtensionInitProviders(nestedManifest)
                    // De-duplicate across split copies: each split typically
                    // mirrors the same provider set, so we want the union of
                    // FQNs (not a list with N copies of each name).
                    for (fqn in nestedDetected) {
                        if (fqn !in allDetected) allDetected.add(fqn)
                    }
                }
            }
        }

        writeDetectedExtensions(detectedFile, allDetected)

        if (isDebug) logger.warn("Bugsee: Manifest task complete")
    }

    /**
     * Writes [fqns] one per line into [target], creating parent directories
     * as needed. Always overwrites — Gradle's `@OutputFile` semantics
     * require a deterministic output on every task run.
     */
    private fun writeDetectedExtensions(target: File, fqns: List<String>) {
        target.parentFile?.mkdirs()
        target.writeText(fqns.joinToString(separator = "\n", postfix = if (fqns.isEmpty()) "" else "\n"))
    }
}
