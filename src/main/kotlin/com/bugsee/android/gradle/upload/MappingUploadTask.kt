package com.bugsee.android.gradle.upload

import com.bugsee.android.gradle.BugseePluginExtension
import com.bugsee.android.gradle.manifest.ManifestModifier
import com.bugsee.android.gradle.util.HashUtils
import com.bugsee.android.gradle.util.IconResolver
import com.bugsee.android.gradle.util.ZipUtils
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.json.JSONObject
import java.io.File

/**
 * Task that uploads the ProGuard/R8 mapping file to the Bugsee backend.
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

    @TaskAction
    fun execute() {
        val isDebug = debug.get()
        val manifest = manifestFile.get().asFile
        val extension = project.extensions.getByType(BugseePluginExtension::class.java)

        if (isDebug) logger.warn("Bugsee: Upload mapping task for variant ${variantName.get()}")

        // Resolve app token
        val appToken = AppTokenResolver.resolve(
            project, extension, variantName.get(), manifest, logger, isDebug
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

        // Resolve app icon
        val iconResourceId = ManifestModifier.getApplicationIcon(manifest)
        val iconFile = if (iconResourceId != null) IconResolver.getIcon(project, iconResourceId) else null
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
