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

        // Copy input to output location if different
        if (manifestFile.absolutePath != outputFile.absolutePath) {
            manifestFile.copyTo(outputFile, overwrite = true)
        }

        val buildUUID = UUID.randomUUID().toString()

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
