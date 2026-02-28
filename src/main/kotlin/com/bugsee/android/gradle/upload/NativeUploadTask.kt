package com.bugsee.android.gradle.upload

import com.bugsee.android.gradle.BugseePluginExtension
import com.bugsee.android.gradle.manifest.ManifestModifier
import com.bugsee.android.gradle.util.HashUtils
import com.bugsee.android.gradle.util.ZipUtils
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.json.JSONObject
import java.io.File

/**
 * Task that uploads NDK native debug symbols to the Bugsee backend.
 *
 * Checks two locations for symbols:
 * 1. build/intermediates/native_debug_metadata/{variant}/out (intermediate symbols)
 * 2. build/outputs/native-debug-symbols/{output}/native-debug-symbols.zip (final package)
 */
abstract class NativeUploadTask : DefaultTask() {

    @get:Input
    abstract val debug: Property<Boolean>

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val endpoint: Property<String>

    @get:InputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun execute() {
        val isDebug = debug.get()
        val manifest = manifestFile.get().asFile
        val extension = project.extensions.getByType(BugseePluginExtension::class.java)

        if (isDebug) logger.warn("Bugsee: Upload native symbols task for variant ${variantName.get()}")

        // Resolve app token
        val appToken = AppTokenResolver.resolve(
            project, extension, variantName.get(), manifest, logger, isDebug
        )
        if (appToken == null) {
            logger.warn("Bugsee: Could not resolve appToken. Skipping native upload.")
            return
        }

        // Get version info
        val versionName = ManifestModifier.getVersionName(manifest)
        val versionCode = ManifestModifier.getVersionCode(manifest)
        if (versionCode == null) {
            logger.warn("Bugsee: Could not find 'android:versionCode' in AndroidManifest.xml")
            return
        }

        // Get BUILD_UUID from manifest
        val buildUUID = ManifestModifier.getMetaDataValue(manifest, "com.bugsee.android.BUILD_UUID")
        if (buildUUID.isNullOrEmpty()) {
            logger.warn("Bugsee: Could not find 'com.bugsee.android.BUILD_UUID' in AndroidManifest.xml. Skipping native upload.")
            return
        }

        val basePath = project.layout.buildDirectory.get().asFile.absolutePath

        // Check for intermediate symbols folder first
        val intermediateSymbolsDir = File("$basePath/intermediates/native_debug_metadata/${variantName.get()}/out")
        if (intermediateSymbolsDir.exists()) {
            if (isDebug) logger.warn("Bugsee: Intermediate symbols folder found: ${intermediateSymbolsDir.path}")

            val zipTemp = File.createTempFile(buildUUID, ".zip")
            try {
                ZipUtils.zipDirectory(intermediateSymbolsDir.absolutePath, zipTemp.absolutePath)

                val hash = HashUtils.sha1Hex(zipTemp)
                val json = JSONObject().apply {
                    put("uuid", buildUUID)
                    put("version", versionName)
                    put("build", versionCode)
                    put("hash", hash)
                    put("transform", "breakpad")
                }.toString()

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
            return
        }

        // Check for native symbols ZIP package in outputs
        val nativeSymbolsBasePath = File(basePath, "outputs/native-debug-symbols")
        if (nativeSymbolsBasePath.exists()) {
            // Look through output folders
            nativeSymbolsBasePath.listFiles()?.filter { it.isDirectory }?.forEach { outputDir ->
                val symbolsZip = File(outputDir, "native-debug-symbols.zip")
                if (symbolsZip.exists()) {
                    if (isDebug) logger.warn("Bugsee: Native symbols file found: ${symbolsZip.path}")

                    val hash = HashUtils.sha1Hex(symbolsZip)
                    val json = JSONObject().apply {
                        put("uuid", buildUUID)
                        put("version", versionName)
                        put("build", versionCode)
                        put("hash", hash)
                        put("transform", "breakpad")
                    }.toString()

                    SymbolUploader.uploadData(
                        file = symbolsZip,
                        json = json,
                        appToken = appToken,
                        endpoint = endpoint.get(),
                        logger = logger,
                        debug = isDebug
                    )
                } else {
                    if (isDebug) logger.warn("Bugsee: Native symbols file not found at: ${symbolsZip.absolutePath}")
                }
            }
        } else {
            logger.warn("Bugsee: NDK upload is enabled but no native symbols found for variant ${variantName.get()}. " +
                "Ensure your project includes native code and that debugSymbolLevel is configured.")
        }
    }
}
