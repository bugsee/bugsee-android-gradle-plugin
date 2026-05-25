package com.bugsee.android.gradle.upload

import com.bugsee.android.gradle.manifest.ManifestModifier
import com.bugsee.android.gradle.util.HashUtils
import com.bugsee.android.gradle.util.SymbolHashCache
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
import java.io.File

/**
 * Task that uploads NDK native debug symbols to the Bugsee backend.
 *
 * Checks two locations for symbols:
 * 1. build/intermediates/native_debug_metadata/{variant}/out (intermediate symbols)
 * 2. build/outputs/native-debug-symbols/{output}/native-debug-symbols.zip (final package)
 *
 * Configuration-cache safe: every value the action needs is wired into
 * an `@Input` / `@Internal` task property at registration time. The
 * action MUST NOT reach into `project.extensions` or `project.layout`
 * at execution — `project` is unavailable on CC replay.
 */
abstract class NativeUploadTask : DefaultTask() {

    @get:Input
    abstract val debug: Property<Boolean>

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val endpoint: Property<String>

    @get:Input
    abstract val forceUpload: Property<Boolean>

    @get:InputFile
    abstract val manifestFile: RegularFileProperty

    /**
     * App token resolved via the DSL / properties-file chain at
     * registration time. Same shape + rationale as
     * `MappingUploadTask.preResolvedAppToken`. See
     * `BundleUploadTask.preResolvedAppToken` for the shared notes.
     */
    @get:Input
    @get:Optional
    abstract val preResolvedAppToken: Property<String>

    /**
     * `res/` source files for the `@string/foo`-fallback path inside
     * `AppTokenResolver.resolveFromManifest`. Wired at registration
     * from `android.sourceSets.main.res.getSourceFiles()`. Same
     * `@Internal` rationale as `MappingUploadTask.stringResourceFiles`.
     */
    @get:Internal
    abstract val stringResourceFiles: ConfigurableFileCollection

    /**
     * Root-project directory for `bugsee.properties` fallback. Same
     * shape + rationale as `MappingUploadTask.rootProjectDirectory`.
     */
    @get:Internal
    abstract val rootProjectDirectory: DirectoryProperty

    /**
     * Per-project build directory (`project.layout.buildDirectory`),
     * pre-resolved at registration. The task action walks this to
     * find native debug-metadata folders that AGP wrote during the
     * build. Reading `project.layout.buildDirectory` at execution
     * would be a CC violation; using a wired DirectoryProperty
     * keeps the resolver pure file I/O.
     *
     * Declared `@Internal` rather than `@InputDirectory` — the
     * native-debug-symbol intermediates are not real inputs to this
     * task (we don't want a content-snapshot of every `.so` file
     * gating up-to-date checks). The folder layout is the contract;
     * the actual symbol bytes are uploaded but never themselves
     * change THIS task's identity.
     */
    @get:Internal
    abstract val buildDirectory: DirectoryProperty

    @TaskAction
    fun execute() {
        val isDebug = debug.get()
        val manifest = manifestFile.get().asFile

        if (isDebug) logger.warn("Bugsee: Upload native symbols task for variant ${variantName.get()}")

        val appToken = preResolvedAppToken.orNull?.takeIf { it.isNotEmpty() }
            ?: AppTokenResolver.resolveFromPropertiesFile(
                rootProjectDirectory.get().asFile, logger, isDebug
            )
            ?: AppTokenResolver.resolveFromManifest(
                manifest, stringResourceFiles.files, logger, isDebug
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

        val basePath = buildDirectory.get().asFile.absolutePath
        val skipCache = forceUpload.get()
        val cacheFile = File(rootProjectDirectory.get().asFile, ".gradle/bugsee/native-symbol-cache.json")
        val cacheKey = "${HashUtils.sha1Hex(appToken)}:${variantName.get()}"

        // Check for intermediate symbols folder first
        val intermediateSymbolsDir = File("$basePath/intermediates/native_debug_metadata/${variantName.get()}/out")
        if (intermediateSymbolsDir.exists()) {
            if (isDebug) logger.warn("Bugsee: Intermediate symbols folder found: ${intermediateSymbolsDir.path}")

            val zipTemp = File.createTempFile(buildUUID, ".zip")
            try {
                ZipUtils.zipDirectory(intermediateSymbolsDir.absolutePath, zipTemp.absolutePath)

                val hash = HashUtils.sha1Hex(zipTemp)

                if (!skipCache && SymbolHashCache.isCached(cacheFile, cacheKey, hash)) {
                    if (isDebug) logger.warn("Bugsee: Native symbols unchanged (hash=$hash). Skipping upload.")
                    return
                }

                val json = JSONObject().apply {
                    put("uuid", buildUUID)
                    put("version", versionName)
                    put("build", versionCode)
                    put("hash", hash)
                    put("transform", "breakpad")
                }.toString()

                val success = SymbolUploader.uploadData(
                    file = zipTemp,
                    json = json,
                    appToken = appToken,
                    endpoint = endpoint.get(),
                    logger = logger,
                    debug = isDebug
                )
                if (success) {
                    SymbolHashCache.put(cacheFile, cacheKey, hash)
                }
            } finally {
                zipTemp.delete()
            }
            return
        }

        // Check for native symbols ZIP package in outputs
        val nativeSymbolsBasePath = File(basePath, "outputs/native-debug-symbols")
        if (nativeSymbolsBasePath.exists()) {
            // Look through output folders, restricted to the current variant so that
            // stale artifacts from other variants (e.g. release dir present during a
            // debug build) are not re-uploaded under this task.
            nativeSymbolsBasePath.listFiles()
                ?.filter { it.isDirectory && it.name == variantName.get() }
                ?.forEach { outputDir ->
                val symbolsZip = File(outputDir, "native-debug-symbols.zip")
                if (symbolsZip.exists()) {
                    if (isDebug) logger.warn("Bugsee: Native symbols file found: ${symbolsZip.path}")

                    val hash = HashUtils.sha1Hex(symbolsZip)

                    if (!skipCache && SymbolHashCache.isCached(cacheFile, cacheKey, hash)) {
                        if (isDebug) logger.warn("Bugsee: Native symbols unchanged (hash=$hash). Skipping upload.")
                        return@forEach
                    }

                    val json = JSONObject().apply {
                        put("uuid", buildUUID)
                        put("version", versionName)
                        put("build", versionCode)
                        put("hash", hash)
                        put("transform", "breakpad")
                    }.toString()

                    val success = SymbolUploader.uploadData(
                        file = symbolsZip,
                        json = json,
                        appToken = appToken,
                        endpoint = endpoint.get(),
                        logger = logger,
                        debug = isDebug
                    )
                    if (success) {
                        SymbolHashCache.put(cacheFile, cacheKey, hash)
                    }
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
