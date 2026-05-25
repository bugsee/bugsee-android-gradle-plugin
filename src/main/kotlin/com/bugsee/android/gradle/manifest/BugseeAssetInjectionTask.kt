package com.bugsee.android.gradle.manifest

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Drops a `bugsee_build_id.properties` file into the variant's merged
 * `assets/` directory, carrying the BUILD_UUID resolved post-R8 by
 * [BugseeBuildIdResolveTask].
 *
 * Wired as the assets-stage transform:
 *
 * ```kotlin
 * variant.artifacts.use(taskProvider)
 *     .wiredWithDirectories(
 *         BugseeAssetInjectionTask::inputAssetsDir,
 *         BugseeAssetInjectionTask::outputAssetsDir,
 *     )
 *     .toTransform(SingleArtifact.ASSETS)
 * ```
 *
 * AGP routes the merged assets directory into [inputAssetsDir] and
 * uses [outputAssetsDir] as the input to downstream APK packaging.
 * Because this task additionally consumes
 * [BugseeBuildIdResolveTask.resolvedBuildIdFile], AGP's task graph
 * sequences it AFTER R8 (via the resolve task's `toListenTo`-wired
 * dependency on `OBFUSCATION_MAPPING_FILE`), even though the assets
 * pipeline normally runs in parallel with R8.
 *
 * **File format.** Standard `java.util.Properties` syntax with a
 * single key:
 *
 * ```
 * # Bugsee build identifier — auto-generated, do not edit.
 * bugsee.build_id=<uuid>
 * ```
 *
 * The Bugsee Android SDK reads this file at first launch via
 * `Context.getAssets().open("bugsee_build_id.properties")` and uses
 * the value as the build identifier sent with every crash report.
 * Falls back to the legacy manifest meta-data
 * (`com.bugsee.android.BUILD_UUID`) when the asset is absent — older
 * builds, or any consumer that builds the SDK without our plugin.
 */
abstract class BugseeAssetInjectionTask : DefaultTask() {

    /**
     * AGP's merged-assets output (input to APK packaging). We copy
     * everything from here verbatim into [outputAssetsDir], then
     * append our one extra file.
     */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputAssetsDir: DirectoryProperty

    /** Output directory replacing AGP's merged assets in the pipeline. */
    @get:OutputDirectory
    abstract val outputAssetsDir: DirectoryProperty

    /**
     * Resolved BUILD_UUID, computed by [BugseeBuildIdResolveTask].
     * A single-line text file containing the canonical 36-char UUID.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val resolvedBuildIdFile: RegularFileProperty

    @TaskAction
    fun execute() {
        val srcDir = inputAssetsDir.get().asFile
        val dstDir = outputAssetsDir.get().asFile

        // AGP < 8.3 quirk noted in the Sentry plugin: the output dir
        // can occasionally be the same physical path as the input dir.
        // The copyRecursively below would then try to delete files it
        // is also reading. Detecting and short-circuiting the copy
        // makes the task self-consistent across AGP versions. On 8.6
        // (our floor for `toListenTo`) the paths are reliably
        // distinct; the guard is defence-in-depth.
        if (!areSamePath(srcDir, dstDir)) {
            if (dstDir.exists()) {
                dstDir.deleteRecursively()
            }
            dstDir.mkdirs()
            if (srcDir.exists()) {
                srcDir.copyRecursively(dstDir, overwrite = true)
            }
        } else {
            dstDir.mkdirs()
        }

        val buildId = resolvedBuildIdFile.get().asFile.readText().trim()
        val outFile = File(dstDir, BUILD_ID_ASSET_NAME)
        outFile.writeText(
            // Standard java.util.Properties syntax + a comment header
            // so anyone who pops open the APK sees provenance instead
            // of an opaque UUID. Trailing newline because the SDK
            // reader uses Properties.load which is newline-tolerant
            // either way, but POSIX text-file convention is to end on
            // one.
            "# Bugsee build identifier — auto-generated, do not edit.\n" +
                "bugsee.build_id=$buildId\n"
        )
    }

    private fun areSamePath(a: File, b: File): Boolean {
        return try {
            a.canonicalPath == b.canonicalPath
        } catch (e: java.io.IOException) {
            // Fall back to absolute paths if canonicalisation fails
            // (e.g. broken symlink). False negatives only cost an
            // extra copy; safe default.
            a.absolutePath == b.absolutePath
        }
    }

    companion object {
        /**
         * Asset file name. The Bugsee SDK uses the same constant on
         * the read side — keep these synchronised. Lives under the
         * APK's `assets/` directory (i.e. accessible via
         * `Context.getAssets().open(BUILD_ID_ASSET_NAME)`).
         */
        internal const val BUILD_ID_ASSET_NAME = "bugsee_build_id.properties"
    }
}
