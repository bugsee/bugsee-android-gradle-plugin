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
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

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
 * at execution — `project` is unavailable on CC replay. [ExecOperations]
 * is the CC-safe replacement for `project.exec` and is requested via
 * `@Inject` rather than read from the project.
 *
 * Mirrors [MappingUploadTask]'s dual-uploader strategy:
 *   - [UploaderStrategy.KOTLIN] (default) — run [SymbolUploader.uploadData]
 *     directly with `transform = "breakpad"`.
 *   - [UploaderStrategy.CLI] — exec `bugsee-cli debug-files upload --type elf`
 *     via [CliUploader.uploadElf]. On a structural CLI failure (exit 1 or 2,
 *     binary missing, exec failed), fall back to the Kotlin uploader.
 *     Substantive CLI failures (bad token, server, network) propagate.
 *
 * Both paths share the [SymbolHashCache] — once a hash is uploaded
 * successfully by either path, subsequent builds skip the upload regardless
 * of which strategy is selected.
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

    /**
     * Path to the `bugsee-cli` binary. Wired from
     * [com.bugsee.android.gradle.BugseePluginExtension.cliPath] at task
     * registration. When unset (or [uploader] is [UploaderStrategy.KOTLIN]),
     * the CLI path is never invoked.
     *
     * `@Internal` rationale: same as [MappingUploadTask.cliPath] — the
     * binary's existence is checked at execution; we don't want Gradle's
     * up-to-date checks gating on a path string that doesn't represent
     * real task input.
     */
    @get:Internal
    abstract val cliPath: Property<String>

    /**
     * `bugsee-cli` FLOOR version to auto-download when [cliPath] is unset.
     * Wired from [com.bugsee.android.gradle.BugseePluginExtension.cliVersion],
     * which defaults to [CliBinaryResolver.DEFAULT_VERSION].
     */
    @get:Input
    abstract val cliVersion: Property<String>

    /**
     * Whether to auto-update the `bugsee-cli` binary to the latest
     * non-breaking release above [cliVersion]. Wired from
     * [com.bugsee.android.gradle.BugseePluginExtension.cliAutoUpdate]
     * (default `true`). `@Optional` — defaults to `true` when unset.
     */
    @get:Input
    @get:Optional
    abstract val cliAutoUpdate: Property<Boolean>

    /**
     * Gradle user home directory. Captured at task registration so the
     * action can read `caches/bugsee-cli/...` CC-safely. `@Internal`
     * because the cache contents are derived state, not real task inputs.
     */
    @get:Internal
    abstract val gradleUserHomeDir: DirectoryProperty

    @get:Input
    abstract val uploader: Property<UploaderStrategy>

    @get:InputFile
    abstract val manifestFile: RegularFileProperty

    /**
     * The resolved BUILD_UUID for this variant, written by
     * [com.bugsee.android.gradle.manifest.BugseeBuildIdResolveTask].
     * See [MappingUploadTask.resolvedBuildIdFile] for the full
     * rationale; native debug-symbols must key off the same UUID the
     * SDK reports at runtime — otherwise NDK crashes never resolve
     * to symbolicated frames.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val resolvedBuildIdFile: RegularFileProperty

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

    /**
     * Injected by Gradle. CC-safe replacement for `project.exec` —
     * required when the action shells out to `bugsee-cli`.
     */
    @get:Inject
    abstract val execOps: ExecOperations

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

        // Source of truth: the resolve task's output. See
        // resolvedBuildIdFile KDoc — uploading under the manifest
        // meta-data would silently mismatch the runtime UUID in
        // every R8-minified build, breaking NDK symbolication.
        val buildUUID = resolvedBuildIdFile.get().asFile.readText().trim()
        if (buildUUID.isEmpty()) {
            logger.warn("Bugsee: Resolved BUILD_UUID file is empty. Skipping native upload.")
            return
        }

        val basePath = buildDirectory.get().asFile.absolutePath
        val skipCache = forceUpload.get()
        val cacheFile = File(rootProjectDirectory.get().asFile, ".gradle/bugsee/native-symbol-cache.json")
        val cacheKey = "${HashUtils.sha1Hex(appToken)}:${variantName.get()}"

        val uploaderChoice = uploader.get()

        // Resolve the CLI binary ONCE per task action, not per upload site.
        // The resolver is cache-aware so re-calling would be cheap, but
        // resolving in one place keeps the strategy state easy to follow.
        // `null` means: no CLI available (or uploader=KOTLIN); fall back.
        val cliBinary: File? = if (uploaderChoice == UploaderStrategy.CLI) {
            CliBinaryResolver.resolve(
                cliVersion = cliVersion.orNull,
                cliPath = cliPath.orNull,
                execOps = execOps,
                gradleUserHome = gradleUserHomeDir.get().asFile,
                logger = logger,
                debug = isDebug,
                autoUpdate = cliAutoUpdate.orNull ?: true,
            )
        } else {
            null
        }

        // Check for intermediate symbols folder first
        val intermediateSymbolsDir = File("$basePath/intermediates/native_debug_metadata/${variantName.get()}/out")
        if (intermediateSymbolsDir.exists()) {
            if (isDebug) logger.warn("Bugsee: Intermediate symbols folder found: ${intermediateSymbolsDir.path}")

            val zipTemp = File.createTempFile(buildUUID, ".zip")
            try {
                ZipUtils.zipDirectory(intermediateSymbolsDir.absolutePath, zipTemp.absolutePath)
                uploadOneZip(
                    zip = zipTemp,
                    buildUUID = buildUUID,
                    versionName = versionName,
                    versionCode = versionCode,
                    appToken = appToken,
                    endpointUrl = endpoint.get(),
                    cacheFile = cacheFile,
                    cacheKey = cacheKey,
                    skipCache = skipCache,
                    isDebug = isDebug,
                    uploaderChoice = uploaderChoice,
                    cliBinary = cliBinary,
                )
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
                    uploadOneZip(
                        zip = symbolsZip,
                        buildUUID = buildUUID,
                        versionName = versionName,
                        versionCode = versionCode,
                        appToken = appToken,
                        endpointUrl = endpoint.get(),
                        cacheFile = cacheFile,
                        cacheKey = cacheKey,
                        skipCache = skipCache,
                        isDebug = isDebug,
                        uploaderChoice = uploaderChoice,
                        cliBinary = cliBinary,
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

    /**
     * Hash → cache-check → strategy-pick → upload (CLI first if configured,
     * Kotlin otherwise) → cache-write on success. Extracted so both upload
     * sites (intermediate folder + final zip) share the same dual-path
     * behavior.
     */
    @Suppress("LongParameterList")
    private fun uploadOneZip(
        zip: File,
        buildUUID: String,
        versionName: String?,
        versionCode: String,
        appToken: String,
        endpointUrl: String,
        cacheFile: File,
        cacheKey: String,
        skipCache: Boolean,
        isDebug: Boolean,
        uploaderChoice: UploaderStrategy,
        cliBinary: File?,
    ) {
        val hash = HashUtils.sha1Hex(zip)

        if (!skipCache && SymbolHashCache.isCached(cacheFile, cacheKey, hash)) {
            if (isDebug) logger.warn("Bugsee: Native symbols unchanged (hash=$hash). Skipping upload.")
            return
        }

        // Strategy pick — same shape as MappingUploadTask. The Kotlin tag
        // flows into the SymbolUploader's `X-Bugsee-Uploader` header so
        // the backend can bucket fallback rates.
        val kotlinFallbackTag: String = when {
            uploaderChoice == UploaderStrategy.KOTLIN -> "kotlin"
            cliBinary == null -> {
                // Either auto-download was attempted and failed (warning
                // already logged inside CliBinaryResolver), or the user
                // explicitly set a cliPath that wasn't usable.
                "kotlin-fallback-cli-not-resolved"
            }
            else -> {
                val cliResult = CliUploader.uploadElf(
                    execOps = execOps,
                    cliBinary = cliBinary,
                    symbolsZip = zip,
                    appToken = appToken,
                    endpoint = endpointUrl,
                    version = versionName ?: "",
                    build = versionCode,
                    uuid = buildUUID,
                    logger = logger,
                    debug = isDebug,
                )
                when {
                    cliResult.success -> {
                        SymbolHashCache.put(cacheFile, cacheKey, hash)
                        return
                    }
                    !cliResult.shouldFallback -> return // already logged
                    else -> "kotlin-fallback-cli-${cliResult.fallbackReason ?: "unknown"}"
                }
            }
        }

        // Kotlin path: build JSON + run the in-process uploader.
        val json = JSONObject().apply {
            put("uuid", buildUUID)
            put("version", versionName)
            put("build", versionCode)
            put("hash", hash)
            put("transform", "breakpad")
        }.toString()

        val success = SymbolUploader.uploadData(
            file = zip,
            json = json,
            appToken = appToken,
            endpoint = endpointUrl,
            logger = logger,
            debug = isDebug,
            uploaderTag = kotlinFallbackTag,
        )
        if (success) {
            SymbolHashCache.put(cacheFile, cacheKey, hash)
        }
    }
}
