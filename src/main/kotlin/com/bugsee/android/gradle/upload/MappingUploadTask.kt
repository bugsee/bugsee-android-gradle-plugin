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
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/**
 * Task that uploads the ProGuard/R8 mapping file to the Bugsee backend.
 *
 * Configuration-cache safe: every value the action needs is wired into
 * an `@Input` / `@Internal` task property at registration time. The
 * action MUST NOT reach into `project.extensions` or `project.layout`
 * at execution — `project` is unavailable on CC replay. [ExecOperations]
 * is the CC-safe replacement for `project.exec` and is requested via
 * `@Inject` rather than read from the project.
 *
 * Phase 1 of the bugsee-cli rollout: the task picks one of two uploader
 * strategies based on [uploader] + [cliPath] (both seeded from the
 * matching [com.bugsee.android.gradle.BugseePluginExtension] fields at
 * registration).
 *
 *   - [UploaderStrategy.KOTLIN] (default) — run [SymbolUploader.uploadData]
 *     directly, exactly as the task always has.
 *   - [UploaderStrategy.CLI] — exec `bugsee-cli debug-files upload` via
 *     [CliUploader]. On a structural CLI failure (exit 1 or 2 per the
 *     CLI contract, binary missing, exec failed), fall back to the
 *     Kotlin uploader so the build still ships symbols. Substantive CLI
 *     failures (bad token, server, network) propagate without retry.
 *
 * In either fallback case the Kotlin uploader stamps the `X-Bugsee-Uploader`
 * header with a `kotlin-fallback-cli-<reason>` value so the backend can
 * bucket fallback rates.
 */
abstract class MappingUploadTask : DefaultTask() {

    @get:Input
    abstract val debug: Property<Boolean>

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val endpoint: Property<String>

    /**
     * Path to the `bugsee-cli` binary. Wired from
     * [com.bugsee.android.gradle.BugseePluginExtension.cliPath] at task
     * registration. When unset (or [uploader] is [UploaderStrategy.KOTLIN]),
     * the CLI path is never invoked.
     *
     * `@Internal` because the existence of the file is checked at execution
     * time (see [CliUploader.uploadMapping]); we don't want Gradle to fail
     * up-to-date checks just because a developer changed the path between
     * builds — the source of truth is what's on disk when the task runs.
     */
    @get:Internal
    abstract val cliPath: Property<String>

    /**
     * `bugsee-cli` version to auto-download when [cliPath] is unset. Wired
     * from [com.bugsee.android.gradle.BugseePluginExtension.cliVersion],
     * which defaults to [CliBinaryResolver.DEFAULT_VERSION].
     */
    @get:Input
    abstract val cliVersion: Property<String>

    /**
     * Gradle user home directory (`~/.gradle` by default). Captured at task
     * registration so the action can read `caches/bugsee-cli/...` without a
     * CC-violating `project.gradle.gradleUserHomeDir` read at execution.
     * `@Internal` because the contents under `caches/` are not real task
     * inputs — they're a derived cache.
     */
    @get:Internal
    abstract val gradleUserHomeDir: DirectoryProperty

    @get:Input
    abstract val uploader: Property<UploaderStrategy>

    @get:InputFile
    abstract val manifestFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val mappingFile: RegularFileProperty

    /**
     * The resolved BUILD_UUID for this variant, written by
     * [com.bugsee.android.gradle.manifest.BugseeBuildIdResolveTask]:
     *   - R8 enabled: a hash of the mapping.txt content. Bytecode-
     *     identical builds get the same UUID; any bytecode change
     *     flips it. This is the UUID the SDK reports at runtime via
     *     the asset channel, so the server MUST key the mapping
     *     under it — otherwise crash symbolication never resolves.
     *   - R8 disabled: the same fallback UUID the manifest meta-data
     *     carries (merged-manifest + variant + plugin-version hash).
     *     Stays in sync with the SDK's manifest-fallback reader.
     *
     * Reading the manifest meta-data here directly would defeat the
     * whole asset-channel design — in any minified release build the
     * upload-side UUID and the runtime-side UUID would diverge.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val resolvedBuildIdFile: RegularFileProperty

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

        // Source of truth: the resolve task's output. In R8 builds
        // this is the mapping-derived UUID the SDK reports at runtime;
        // in non-R8 builds it is byte-equal to the manifest fallback.
        // Reading the manifest meta-data here would silently mismatch
        // the runtime UUID in every minified release build.
        val buildUUID = resolvedBuildIdFile.get().asFile.readText().trim()
        if (buildUUID.isEmpty()) {
            logger.warn("Bugsee: Resolved BUILD_UUID file is empty. Skipping mapping upload.")
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

        // Pick uploader strategy. The fallback tag flows into the Kotlin
        // path's `X-Bugsee-Uploader` header so backend can count CLI vs.
        // fallback usage without touching customer code.
        val uploaderChoice = uploader.get()

        val kotlinFallbackTag: String = when (uploaderChoice) {
            UploaderStrategy.KOTLIN -> "kotlin"
            UploaderStrategy.CLI -> {
                // Resolve a binary: explicit cliPath wins; else auto-download
                // from download.bugsee.com into the per-user Gradle cache.
                val cliBinary = CliBinaryResolver.resolve(
                    cliVersion = cliVersion.orNull,
                    cliPath = cliPath.orNull,
                    execOps = execOps,
                    gradleUserHome = gradleUserHomeDir.get().asFile,
                    logger = logger,
                    debug = isDebug,
                )
                if (cliBinary == null) {
                    "kotlin-fallback-cli-not-resolved"
                } else {
                    val cliResult = CliUploader.uploadMapping(
                        execOps = execOps,
                        cliBinary = cliBinary,
                        mappingFile = mapping,
                        iconFile = iconFile,
                        appToken = appToken,
                        endpoint = endpoint.get(),
                        version = versionName ?: "",
                        build = versionCode.toString(),
                        uuid = buildUUID,
                        logger = logger,
                        debug = isDebug,
                    )
                    when {
                        cliResult.success -> return
                        !cliResult.shouldFallback -> return // already logged
                        else -> "kotlin-fallback-cli-${cliResult.fallbackReason ?: "unknown"}"
                    }
                }
            }
        }

        // Kotlin path: build the ZIP + metadata and run the in-process uploader.
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
                debug = isDebug,
                uploaderTag = kotlinFallbackTag,
            )
        } finally {
            zipTemp.delete()
        }
    }
}
