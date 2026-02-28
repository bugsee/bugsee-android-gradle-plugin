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
 * Task that injects a unique BUILD_UUID into the merged AndroidManifest.xml.
 *
 * This task operates on the merged manifest via AGP's artifact transformation API,
 * meaning it receives the manifest as input, modifies it in place, and outputs the same file.
 */
abstract class BugseeManifestTask : DefaultTask() {

    @get:Input
    abstract val debug: Property<Boolean>

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val updatedManifest: RegularFileProperty

    @TaskAction
    fun execute() {
        val isDebug = debug.get()
        val manifestFile = mergedManifest.get().asFile
        val outputFile = updatedManifest.get().asFile

        if (!manifestFile.isFile) {
            logger.warn("Bugsee: Manifest file not found: ${manifestFile.absolutePath}")
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
            return
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
            }
        }

        if (isDebug) logger.warn("Bugsee: Manifest task complete")
    }
}
