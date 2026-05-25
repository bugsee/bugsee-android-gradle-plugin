package com.bugsee.android.gradle.upload

import com.bugsee.android.gradle.manifest.ManifestModifier
import com.bugsee.android.gradle.util.HashUtils
import com.bugsee.android.gradle.util.IconResolver
import com.bugsee.android.gradle.util.ZipUtils
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.json.JSONObject

/**
 * Task that uploads the ProGuard/R8 mapping file to the Bugsee backend.
 *
 * Configuration-cache safe: every value the action needs is wired into
 * an `@Input` / `@Internal` task property at registration time. The
 * action MUST NOT reach into `project.extensions` or `project.layout`
 * at execution — `project` is unavailable on CC replay.
 */
abstract class MappingUploadTask : DefaultTask() {

    @get:Input
    abstract val debug: Property<Boolean>

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val endpoint: Property<String>

    @get:InputFile
    abstract val manifestFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val mappingFile: RegularFileProperty

    /**
     * App token resolved via the DSL / properties-file chain
     * (`AppTokenResolver.resolveFromExtension` + `resolveFromPropertiesFile`)
     * at registration time. Empty / unset means neither source
     * provided a token; the task falls back to the manifest meta-data
     * lookup at execution. See `BundleUploadTask.preResolvedAppToken`
     * for the shared rationale.
     */
    @get:Input
    @get:Optional
    abstract val preResolvedAppToken: Property<String>

    /**
     * `res/` source files from the app's main source set — used by
     * `AppTokenResolver.resolveFromManifest` when the manifest
     * `<meta-data>` value is an `@string/foo` reference, AND by
     * `IconResolver.getIcon` for the launcher-icon attached to the
     * upload zip. Wired from `android.sourceSets.main.res.getSourceFiles()`
     * at registration so neither resolver needs `project` access at
     * execution.
     *
     * Declared `@Internal` — same rationale as
     * `BundleUploadTask.stringResourceFiles`: runtime lookup surface,
     * not a real build input; treating the entire `res/` tree as an
     * `@InputFiles` snapshot would be wildly expensive for a fallback
     * path almost nobody hits.
     */
    @get:Internal
    abstract val stringResourceFiles: ConfigurableFileCollection

    /**
     * Root-project directory — handed to
     * `AppTokenResolver.resolveFromPropertiesFile` so it can read
     * `bugsee.properties` at the project root. Wired from
     * `project.rootProject.layout.projectDirectory` at registration;
     * the action reads `.get().asFile` instead of `project.rootDir`
     * (a CC violation). Pre-resolution at registration already
     * consults this file once, but the action keeps the read as a
     * last-resort fallback in case the registration-time resolution
     * raced with a properties-file write.
     */
    @get:Internal
    abstract val rootProjectDirectory: DirectoryProperty

    @TaskAction
    fun execute() {
        val isDebug = debug.get()
        val manifest = manifestFile.get().asFile

        if (isDebug) logger.warn("Bugsee: Upload mapping task for variant ${variantName.get()}")

        // App-token resolution chain — mirrors BundleUploadTask. The
        // registration-time work (extension + properties-file) is
        // already baked into `preResolvedAppToken`; the manifest
        // fallback runs here because the merged manifest only exists
        // at execution.
        val appToken = preResolvedAppToken.orNull?.takeIf { it.isNotEmpty() }
            ?: AppTokenResolver.resolveFromPropertiesFile(
                rootProjectDirectory.get().asFile, logger, isDebug
            )
            ?: AppTokenResolver.resolveFromManifest(
                manifest, stringResourceFiles.files, logger, isDebug
            )

        if (appToken == null) {
            logger.warn("Bugsee: Could not resolve appToken. Skipping mapping upload.")
            return
        }

        // Get BUILD_UUID from manifest
        val buildUUID = ManifestModifier.getMetaDataValue(manifest, "com.bugsee.android.BUILD_UUID")
        if (buildUUID.isNullOrEmpty()) {
            logger.warn("Bugsee: Could not find 'com.bugsee.android.BUILD_UUID' in AndroidManifest.xml")
            return
        }

        // Get version info
        val versionName = ManifestModifier.getVersionName(manifest)
        val versionCode = ManifestModifier.getVersionCode(manifest)
        if (versionCode == null) {
            logger.warn("Bugsee: Could not find 'android:versionCode' in AndroidManifest.xml")
            return
        }

        // Get the mapping file
        val mapping = mappingFile.orNull?.asFile
        if (mapping == null || !mapping.exists()) {
            if (isDebug) logger.warn("Bugsee: Mapping file does not exist. Skipping upload.")
            return
        }

        // Resolve app icon from the pre-wired source-file list.
        val iconResourceId = ManifestModifier.getApplicationIcon(manifest)
        val iconFile = if (iconResourceId != null)
            IconResolver.getIcon(stringResourceFiles.files, iconResourceId)
        else null
        if (isDebug) logger.warn("Bugsee: Icon file: ${iconFile?.path}")

        // Create ZIP with mapping + icon
        val zipTemp = ZipUtils.createMappingZip(mapping, iconFile, buildUUID)
        try {
            // Compute mapping hash
            val mappingHash = HashUtils.sha1Hex(mapping.readText(Charsets.UTF_8))

            // Build JSON metadata
            val json = JSONObject().apply {
                put("uuid", buildUUID)
                put("version", versionName)
                put("build", versionCode)
                put("hash", mappingHash)
            }.toString()

            // Upload
            SymbolUploader.uploadData(
                file = zipTemp,
                json = json,
                appToken = appToken,
                endpoint = endpoint.get(),
                logger = logger,
                debug = isDebug
            )
        } finally {
            zipTemp.delete()
        }
    }
}
