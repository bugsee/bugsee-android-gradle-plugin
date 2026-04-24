package com.bugsee.android.gradle.upload

import com.bugsee.android.gradle.BugseePlugin
import com.bugsee.android.gradle.BugseePluginExtension
import com.bugsee.android.gradle.manifest.ManifestModifier
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.util.GradleVersion
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Task that uploads the assembled AAB or APK to the Bugsee backend
 * for size analysis.
 *
 * Creates a ZIP archive containing:
 * - The bundle file (AAB or APK)
 * - The R8/ProGuard mapping file (if present), for deobfuscation
 *
 * Uses the same two-stage upload pattern as symbol/mapping uploads:
 * 1. POST JSON metadata to get a presigned S3 URL
 * 2. PUT the ZIP to the presigned URL
 */
abstract class BundleUploadTask : DefaultTask() {

    @get:Input
    abstract val debug: Property<Boolean>

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val buildConfiguration: Property<String>

    @get:Input
    abstract val endpoint: Property<String>

    @get:Input
    abstract val format: Property<String>

    @get:InputFile
    @get:Optional
    abstract val bundleFile: RegularFileProperty

    @get:InputDirectory
    @get:Optional
    abstract val apkDirectory: DirectoryProperty

    @get:InputFile
    abstract val manifestFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val mappingFile: RegularFileProperty

    // Shared BuildService that collects per-task timings across the
    // whole build. `@ServiceReference(name)` declaratively auto-wires
    // the task to the service registered under that name in
    // `BugseePlugin.apply()` — no explicit `task.set(...)` or
    // `task.usesService(...)` call required on the registration site.
    @get:ServiceReference(BugseePlugin.BUILD_TIMING_SERVICE_NAME)
    abstract val timingService: Property<BuildTimingService>

    // Android `compileSdk` rendered as a string — wired from the
    // `android { compileSdk = N }` DSL at task registration. Sent as
    // `build_sdk_version` in the upload payload so the server can
    // distinguish builds compiled against different SDK levels. The
    // property is optional because `compileSdk` can be unset in
    // exotic configurations (the plugin also allows
    // `compileSdkPreview` or `compileSdkExtension`).
    @get:Input
    @get:Optional
    abstract val buildSdkVersion: Property<String>

    @TaskAction
    fun execute() {
        val isDebug = debug.get()
        val manifest = manifestFile.get().asFile
        val extension = project.extensions.getByType(BugseePluginExtension::class.java)

        if (isDebug) logger.warn("Bugsee: Upload bundle task for variant ${variantName.get()} (${format.get()})")

        // Resolve app token
        val appToken = AppTokenResolver.resolve(
            project, extension, variantName.get(), manifest, logger, isDebug
        )
        if (appToken == null) {
            logger.warn("Bugsee: Could not resolve appToken. Skipping bundle upload.")
            return
        }

        // Get BUILD_UUID from manifest
        val buildUUID = ManifestModifier.getMetaDataValue(manifest, "com.bugsee.android.BUILD_UUID")
        if (buildUUID.isNullOrEmpty()) {
            logger.warn("Bugsee: Could not find 'com.bugsee.android.BUILD_UUID' in AndroidManifest.xml")
            return
        }

        // Get version and package info
        val versionName = ManifestModifier.getVersionName(manifest)
        val versionCode = ManifestModifier.getVersionCode(manifest)
        val packageName = ManifestModifier.getPackageName(manifest)
        if (versionCode == null) {
            logger.warn("Bugsee: Could not find 'android:versionCode' in AndroidManifest.xml")
            return
        }

        // Resolve the artifact file
        val artifactFile = resolveArtifactFile()
        if (artifactFile == null || !artifactFile.exists()) {
            if (isDebug) logger.warn("Bugsee: Artifact file not found. Skipping bundle upload.")
            return
        }

        // Resolve mapping file (optional)
        val mapping = mappingFile.orNull?.asFile?.takeIf { it.exists() && it.length() > 0 }
        if (isDebug) {
            if (mapping != null) {
                logger.warn("Bugsee: Including mapping file: ${mapping.path}")
            } else {
                logger.warn("Bugsee: No mapping file found (minification may be disabled)")
            }
        }

        // Create ZIP containing bundle + optional mapping
        val uploadZip = createUploadZip(artifactFile, mapping, buildUUID)

        // Best-effort VCS metadata — never fails the build.
        val vcs = try {
            VcsMetadataResolver.resolve(project.projectDir)
        } catch (e: Exception) {
            if (isDebug) logger.warn("Bugsee: VCS metadata resolution failed: ${e.message}")
            com.bugsee.android.gradle.upload.VcsMetadata()
        }
        if (isDebug && vcs.isNotEmpty()) {
            logger.warn(
                "Bugsee: VCS metadata — provider=${vcs.vcsProvider} repo=${vcs.vcsRepo} " +
                    "branch=${vcs.branch} baseBranch=${vcs.baseBranch} " +
                    "commit=${vcs.commitSha?.take(8)} pr=${vcs.prNumber}"
            )
        }

        // Build-process provenance — best-effort. Caught wide so a
        // misbehaving resolver or a missing BuildService never turns
        // into a failed upload: size analysis is strictly auxiliary
        // and must not kill an otherwise-green CI build.
        val buildMetadata = try {
            resolveBuildMetadataJson()
        } catch (e: Exception) {
            if (isDebug) logger.warn("Bugsee: build_metadata resolution failed: ${e.message}")
            null
        }

        try {
            // Build JSON metadata
            val json = JSONObject().apply {
                put("uuid", buildUUID)
                put("package_id", packageName)
                put("version", versionName)
                put("build", versionCode)
                put("build_configuration", buildConfiguration.get())
                put("format", format.get())
                put("has_mapping", mapping != null)
                // VCS fields — only emit keys for values we actually learned,
                // so the backend distinguishes "unknown" from "known empty".
                vcs.commitSha?.let   { put("commit_sha", it) }
                vcs.baseSha?.let     { put("base_sha", it) }
                vcs.branch?.let      { put("branch", it) }
                vcs.baseBranch?.let  { put("base_branch", it) }
                vcs.prNumber?.let    { put("pr_number", it) }
                vcs.vcsProvider?.let { put("vcs_provider", it) }
                vcs.vcsRepo?.let     { put("vcs_repo", it) }
                // Machine + plugin/Gradle versions + per-category
                // Gradle task timings (see resolveBuildMetadataJson).
                buildMetadata?.let { put("build_metadata", it) }
            }.toString()

            // Chunked upload path (Phase 6, feature-flagged). Falls back
            // to the single-PUT path on any failure so CI never breaks
            // just because the chunked endpoints aren't deployed yet.
            val chunked = extension.chunkedUpload.get()
            var chunkedSucceeded = false
            if (chunked) {
                try {
                    ChunkedBundleUploader.upload(
                        uploadZip = uploadZip,
                        metadata  = JSONObject(json),
                        appToken  = appToken,
                        endpoint  = endpoint.get(),
                        logger    = logger,
                        debug     = isDebug,
                    )
                    chunkedSucceeded = true
                } catch (e: Exception) {
                    logger.warn("Bugsee: chunked upload failed — falling back to single-PUT. Cause: ${e.message}")
                }
            }

            if (!chunkedSucceeded) {
                // Upload: POST metadata → presigned URL → PUT file.
                // BundleUploader throws on any failure so the cause is
                // observable, but size-analysis is best-effort — log at
                // error level and swallow so a flaky upload doesn't
                // kill an otherwise-green CI build.
                try {
                    BundleUploader.uploadData(
                        file = uploadZip,
                        json = json,
                        appToken = appToken,
                        endpoint = endpoint.get(),
                        logger = logger,
                        debug = isDebug
                    )
                } catch (e: Exception) {
                    logger.error("Bugsee: bundle upload failed (size analysis unavailable for this build): ${e.message}")
                }
            }
        } finally {
            uploadZip.delete()
        }
    }

    /**
     * Assembles the `build_metadata` sub-object sent alongside the
     * existing upload metadata: machine/CI-runner label, plugin and
     * Gradle versions, and the per-category rollup of Gradle task
     * timings.
     *
     * Returns `null` when nothing useful was captured — the caller
     * then omits the field entirely so the server-side sanitizer
     * sees a clean absence rather than an empty object.
     */
    private fun resolveBuildMetadataJson(): JSONObject? {
        val obj = JSONObject()

        BuildMachineResolver.resolve()?.takeIf { it.isNotBlank() }?.let {
            obj.put("machine", it)
        }

        obj.put("plugin_version", BugseePlugin.PLUGIN_VERSION)
        // Renamed from the earlier `gradle_version` as part of cross-
        // platform schema harmonisation — the same slot carries the
        // Xcode version on iOS builds.
        obj.put("build_system_version", GradleVersion.current().version)
        buildSdkVersion.orNull?.takeIf { it.isNotBlank() }?.let {
            obj.put("build_sdk_version", it)
        }

        // Timing service is wired from the plugin's `apply()` so it's
        // always present in normal operation. Guard defensively so a
        // Gradle edge case that skips the listener registration (very
        // short builds, replay-from-cache) doesn't throw here.
        val service = timingService.orNull
        if (service != null) {
            val timings = service.snapshot()
            val timingsJson = timings.toJson()
            if (timingsJson.length() > 0) {
                obj.put("timings", timingsJson)
            }
        }

        return if (obj.length() == 0) null else obj
    }

    private fun resolveArtifactFile(): File? {
        // AAB: direct file reference
        bundleFile.orNull?.asFile?.let { return it }

        // APK: find the first .apk in the output directory
        val dir = apkDirectory.orNull?.asFile ?: return null
        return dir.walkTopDown().firstOrNull { it.extension == "apk" }
    }

    private fun createUploadZip(bundle: File, mapping: File?, buildUUID: String): File {
        val zipTemp = File.createTempFile("bugsee-build-$buildUUID", ".zip")
        zipTemp.deleteOnExit()
        writeNormalizedUploadZip(bundle, mapping, zipTemp)
        return zipTemp
    }

    companion object {
        // MS-DOS date/time cannot represent anything before 1980-01-01,
        // so any normalised mtime we pick must be on or after that.
        internal val DOS_EPOCH_LOCAL: LocalDateTime =
            LocalDateTime.of(1980, 1, 1, 0, 0, 0)

        /**
         * Write the deterministic wrapper zip. Separated from the
         * Gradle-task method for testability — the task scaffolding
         * doesn't bring value to the determinism check.
         *
         * Properties we rely on:
         *   - Entries sorted lexicographically → byte output independent
         *     of filesystem enumeration order.
         *   - Fixed mtime via setTimeLocal (not setTime) → independent of
         *     build host time zone.
         *   - STORE for the bundle (already-compressed), DEFLATE for text.
         */
        internal fun writeNormalizedUploadZip(bundle: File, mapping: File?, out: File) {
            data class Member(val name: String, val source: File, val method: Int)
            val members = buildList {
                add(Member(bundle.name, bundle, ZipEntry.STORED))
                if (mapping != null) {
                    add(Member("mapping.txt", mapping, ZipEntry.DEFLATED))
                }
            }.sortedBy { it.name }

            ZipOutputStream(FileOutputStream(out)).use { zos ->
                zos.setLevel(Deflater.BEST_SPEED)
                for (m in members) {
                    val entry = ZipEntry(m.name).apply {
                        setTimeLocal(DOS_EPOCH_LOCAL)
                        method = m.method
                        if (method == ZipEntry.STORED) {
                            val bytes = m.source.length()
                            size = bytes
                            compressedSize = bytes
                            crc = computeCrc32(m.source)
                        }
                    }
                    zos.putNextEntry(entry)
                    FileInputStream(m.source).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }

        private fun computeCrc32(file: File): Long {
            val crc = java.util.zip.CRC32()
            FileInputStream(file).use { fis ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = fis.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                }
            }
            return crc.value
        }
    }
}
