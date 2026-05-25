package com.bugsee.android.gradle.upload

import com.bugsee.android.gradle.BugseePlugin
import com.bugsee.android.gradle.manifest.ManifestModifier
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
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

    // Pre-resolved app token from the plugin's extension (closures /
    // provider / defaultAppToken). Populated at task registration
    // via `AppTokenResolver.resolveFromExtension(...)` so the
    // `@TaskAction` can avoid `project.extensions.getByType(...)` —
    // a configuration-cache violation. Empty / unset means the
    // extension provided nothing and the task must fall back to the
    // manifest meta-data lookup.
    @get:Input
    @get:Optional
    abstract val preResolvedAppToken: Property<String>

    // `res/` source files from the app's main source set — used by
    // `AppTokenResolver.resolveFromManifest` only when the manifest
    // meta-data tag resolves to an `@string/foo` reference. Wired
    // from `android.sourceSets.main.res.getSourceFiles()` at
    // registration, so the resolver walks a plain file list at
    // execution without touching the Android DSL (which would be a
    // CC leak).
    //
    // Declared `@Internal` — this is a runtime lookup surface, not
    // a real build input. Including it as `@InputFiles` would pull
    // the entire `res/` tree into task-up-to-date snapshotting for
    // a fallback path almost nobody hits.
    @get:Internal
    abstract val stringResourceFiles: ConfigurableFileCollection

    // Feature-flag for the chunked upload path. Wired from
    // `extension.buildInfo.sizeAnalysis.chunkedUpload` at task
    // registration — reading it here (rather than at `@TaskAction`
    // time via `project.extensions`) keeps the task CC-clean.
    @get:Input
    abstract val chunkedUpload: Property<Boolean>

    // `true` when the user has enabled `bugsee.buildInfo.sizeAnalysis.enabled`.
    // The task is registered whenever `buildInfo.enabled` is on (the
    // default); this flag controls whether the metadata POST also
    // requests a presigned URL for the artefact bytes. Mirrors the
    // appserver's `request_artifact_upload` body field.
    //
    //   - `false` (default): metadata-only POST, server returns
    //     `size_analysis_status: 'unavailable'`. Build-info path.
    //   - `true`: same POST + `request_artifact_upload: true`. Server
    //     returns a presigned PUT URL; the task ships the artefact.
    @get:Input
    abstract val requestArtifactUpload: Property<Boolean>

    // Project root — handed to `VcsMetadataResolver.resolve(...)` so
    // it can shell out to `git` when no CI provider env matches.
    // Wired from `project.layout.projectDirectory` at registration;
    // the task action reads `projectDirectory.get().asFile` instead
    // of `project.projectDir` (the latter being a CC violation).
    //
    // Declared `@Internal`, NOT `@InputDirectory` — `@InputDirectory`
    // would make Gradle snapshot the entire project tree (including
    // `.gradle/`, `build/`, IDE files) on every up-to-date check,
    // which is wildly expensive for what is effectively a runtime
    // lookup surface used only to exec `git`.
    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    // ── In-build size-check inputs ─────────────────────────────────
    //
    // Resolved at task registration via DSL → env-var fallback in
    // `BugseePlugin.registerBundleUploadTask`. A `0` in any threshold
    // is treated as "disabled" (the resolver normalises to absent),
    // so users can leave a single gate active without having to spell
    // out the others.
    //
    // All five are optional. The check is a no-op when `enabled` is
    // unset/false or when no threshold is active.
    @get:Input
    @get:Optional
    abstract val sizeCheckEnabled: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val sizeCheckWarningPercent: Property<Double>

    @get:Input
    @get:Optional
    abstract val sizeCheckFailPercent: Property<Double>

    @get:Input
    @get:Optional
    abstract val sizeCheckWarningBytes: Property<Long>

    @get:Input
    @get:Optional
    abstract val sizeCheckFailBytes: Property<Long>

    // ── Dependencies-collection inputs ─────────────────────────────
    //
    // Wired from `extension.buildInfo.dependencies` at registration.
    // The task resolves + serialises the dep graph at execution time
    // so a configuration-time crash in some other plugin never
    // bleeds into Bugsee's task graph (collection is best-effort,
    // mirrors the rest of the build-info path).

    /** Master gate. When `false` / unset, the dep-collection step is
     *  skipped entirely (no resolution, no PUT, no `dependencies_summary`
     *  in the metadata POST). */
    @get:Input
    @get:Optional
    abstract val requestDependenciesUpload: Property<Boolean>

    /** One of `"runtime"`, `"runtime_direct_only"`, `"compile_runtime"`.
     *  Currently informational only — the resolution result wired in
     *  via [runtimeRootComponent] is the runtime classpath; the
     *  other scope variants are reserved for a follow-up. */
    @get:Input
    @get:Optional
    abstract val dependenciesScope: Property<String>

    /** Whether to include Gradle's selection reason on each entry. */
    @get:Input
    @get:Optional
    abstract val dependenciesIncludeReason: Property<Boolean>

    /** Cap on the per-entry list. Above this the summary's `truncated`
     *  flag is set. */
    @get:Input
    @get:Optional
    abstract val dependenciesMaxCount: Property<Int>

    /** `"group:name" → "implementation"/"api"/...` map built from the
     *  project's declared dependencies at registration. Only direct
     *  entries receive a scope; transitives stay `null`. */
    @get:Input
    @get:Optional
    abstract val declaredScopes: MapProperty<String, String>

    /** File-collection dependencies (raw .jar / .aar refs) encoded as
     *  `"scopeOrEmpty|displayName"`. Encoded as strings so the input
     *  is configuration-cache-safe regardless of the underlying file
     *  type. */
    @get:Input
    @get:Optional
    abstract val fileDependencies: ListProperty<String>

    /** Resolved root of the variant's runtime classpath — walked by
     *  [DependencyCollector]. Declared `@Internal`: it's a live
     *  Gradle computation, not a fingerprintable input, so it must
     *  not participate in up-to-date checks (Gradle re-runs the task
     *  whenever the underlying configuration changes via the
     *  build-script classpath). */
    @get:Internal
    abstract val runtimeRootComponent: Property<ResolvedComponentResult>

    // ── Timings-collection inputs ──────────────────────────────────
    //
    // Wired from `extension.buildInfo.timings` at registration.
    // The actual capture happens continuously throughout the build
    // via the always-on `BuildTimingService`; this flag gates only
    // whether the upload task USES the captured data. Disabling it
    // omits both the inline `build_metadata.timings` summary block
    // and the `request_timings_upload: true` flag (so the server
    // never signs a presigned PUT URL).

    /** Master gate. When `false` / unset, the inline timings summary
     *  is omitted from the metadata POST and the detail-blob PUT is
     *  skipped. */
    @get:Input
    @get:Optional
    abstract val timingsEnabled: Property<Boolean>

    @TaskAction
    fun execute() {
        val isDebug = debug.get()
        val manifest = manifestFile.get().asFile

        if (isDebug) logger.warn("Bugsee: Upload bundle task for variant ${variantName.get()} (${format.get()})")

        // Resolve app token — plugin-side first (pre-resolved into
        // `preResolvedAppToken` at registration), manifest fallback
        // second. Never touches `project` at execution time.
        val preResolved = preResolvedAppToken.orNull?.takeIf { it.isNotEmpty() }
        val appToken = preResolved
            ?: AppTokenResolver.resolveFromManifest(
                manifestFile = manifest,
                stringResourceFiles = stringResourceFiles,
                logger = logger,
                debug = isDebug,
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
        // `projectDirectory` is wired from `project.layout
        // .projectDirectory` at task registration, so we read it as
        // a plain File here without touching `project` at execution
        // time (CC-safe).
        val vcs = try {
            VcsMetadataResolver.resolve(projectDirectory.get().asFile)
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

        // Single snapshot of the timing service's records — used by
        // BOTH the inline `build_metadata.timings` summary AND the
        // detail-blob serializer downstream. Snapshotted ONCE so a
        // task completing between the two consumers can't make the
        // inline summary disagree with the detail blob (e.g. inline
        // shows a task the blob omits, or vice versa). `null` when
        // the timing service was never wired in (Gradle edge cases).
        val timingsSnapshot: List<TaskTiming>? = timingService.orNull?.timeline()

        // Build-process provenance — best-effort. Caught wide so a
        // misbehaving resolver or a missing BuildService never turns
        // into a failed upload: size analysis is strictly auxiliary
        // and must not kill an otherwise-green CI build.
        val buildMetadata = try {
            resolveBuildMetadataJson(timingsSnapshot)
        } catch (e: Exception) {
            if (isDebug) logger.warn("Bugsee: build_metadata resolution failed: ${e.message}")
            null
        }

        try {
            // VCS sub-object — nested to match the appserver's
            // `VcsMetadataSchema`. Each field only lands if we
            // actually resolved it, so the server distinguishes
            // "unknown" from "known empty". The whole sub-object is
            // omitted when no VCS fields resolved at all.
            val vcsJson = JSONObject().apply {
                vcs.commitSha?.let   { put("commit_sha", it) }
                vcs.baseSha?.let     { put("base_sha", it) }
                vcs.branch?.let      { put("branch", it) }
                vcs.baseBranch?.let  { put("base_branch", it) }
                vcs.prNumber?.let    { put("pr_number", it) }
                vcs.vcsProvider?.let { put("provider", it) }
                vcs.vcsRepo?.let     { put("repo", it) }
            }

            // Raw artifact byte count — captured up-front (synchronous,
            // O(1) `length()` on a File) and sent in the upload payload
            // so the next build's size-check has a baseline to query.
            // Distinct from the wrapper-zip's size which the upload
            // pipeline assembles further down: the server stores the
            // actual artifact size, not whatever the transport-layer
            // wrapper happens to be.
            val artifactSize = artifactFile.length()

            val wantsArtifactUpload = requestArtifactUpload.getOrElse(false)
            val wantsDependenciesUpload = requestDependenciesUpload.getOrElse(false)

            // Run the dependency collector when the user has opted in
            // (default-on path). All-or-nothing: a resolution failure
            // (e.g. a misbehaving custom dependency resolver in the
            // user's project) drops back to "no deps payload" without
            // failing the metadata POST. Same posture as
            // `buildMetadata` above — best-effort enrichment.
            val depsPayload: DepsPayload? = if (wantsDependenciesUpload) {
                try {
                    resolveDependenciesPayload()
                } catch (e: Exception) {
                    if (isDebug) logger.warn(
                        "Bugsee: dependency collection failed: ${e.message} — skipping deps upload"
                    )
                    null
                }
            } else null

            // Build-timings detail blob. Gated by the user-facing
            // `buildInfo.timings.enabled` DSL flag (default ON,
            // mirrored by the inline-summary guard in
            // [resolveBuildMetadataJson]). When disabled, BOTH the
            // inline `build_metadata.timings` block AND the detail
            // blob are omitted so the appserver never signs a
            // presigned timings PUT URL. The `BuildTimingService`
            // itself stays registered regardless — its per-task
            // overhead is sub-millisecond — but its data is unused.
            val timingsPayload: TimingsPayload? = if (
                timingsEnabled.getOrElse(true)
            ) {
                try {
                    resolveTimingsPayload(timingsSnapshot)
                } catch (e: Exception) {
                    if (isDebug) logger.warn(
                        "Bugsee: timings serialisation failed: ${e.message} — skipping timings upload"
                    )
                    null
                }
            } else null

            // Build JSON metadata
            val json = JSONObject().apply {
                put("uuid", buildUUID)
                put("package_id", packageName)
                put("version", versionName)
                put("build", versionCode)
                put("build_configuration", buildConfiguration.get())
                put("format", format.get())
                put("has_mapping", mapping != null)
                put("artifact_size", artifactSize)
                // Server reads this to decide whether to start the
                // record at `'unavailable'` (build-info only) or
                // `'uploading'` (sign + return a presigned PUT URL).
                put("request_artifact_upload", wantsArtifactUpload)
                // Independent of artefact upload — opts in to the deps
                // upload sub-feature and tells the server to sign a
                // second presigned PUT URL.
                if (depsPayload != null) {
                    put("request_dependencies_upload", true)
                    put("dependencies_summary", depsPayload.summaryJson)
                }
                // Independent of artefact + deps uploads — opts in to
                // the timings detail-blob feature. The inline summary
                // lives under `build_metadata.timings` already; this
                // flag tells the server to sign a third presigned PUT
                // URL that the producer uses for the detail blob.
                if (timingsPayload != null) {
                    put("request_timings_upload", true)
                }
                if (vcsJson.length() > 0) put("vcs", vcsJson)
                // Machine + plugin/Gradle versions + per-category
                // Gradle task timings (see resolveBuildMetadataJson).
                buildMetadata?.let { put("build_metadata", it) }
            }.toString()

            // In-build size-check: resolve thresholds + fetch the
            // baseline BEFORE upload so the lookup naturally excludes
            // the build we are about to create. The actual evaluation
            // (and possible throw) happens AFTER the upload so a
            // FAIL still leaves the new build in the system for
            // diagnosis. `null` means "not configured / no eligible
            // baseline / lookup failed" — all of which collapse to
            // PASS-skip.
            val sizeCheckThresholds = resolveSizeCheckThresholds()
            val baseline: BaselineClient.Baseline? = if (
                sizeCheckEnabled.getOrElse(false) && sizeCheckThresholds.anyActive
            ) {
                BaselineClient.fetchBaseline(
                    endpoint = endpoint.get(),
                    appToken = appToken,
                    packageId = packageName ?: "",
                    format = format.get(),
                    buildConfiguration = buildConfiguration.get(),
                    logger = logger,
                    debug = isDebug,
                )
            } else null

            // Chunked upload path (Phase 6, feature-flagged). Only
            // meaningful when an artefact upload was requested — the
            // build-info-only path has nothing to chunk. Falls back
            // to the single-PUT path on any failure so CI never breaks
            // just because the chunked endpoints aren't deployed yet.
            val chunked = wantsArtifactUpload && chunkedUpload.get()
            var chunkedSucceeded = false
            if (chunked) {
                try {
                    ChunkedBundleUploader.upload(
                        uploadZip = uploadZip,
                        metadata  = JSONObject(json),
                        appToken  = appToken,
                        endpoint  = endpoint.get(),
                        dependenciesGzFile = depsPayload?.gzFile,
                        timingsGzFile = timingsPayload?.gzFile,
                        logger    = logger,
                        debug     = isDebug,
                    )
                    chunkedSucceeded = true
                } catch (e: Exception) {
                    logger.warn("Bugsee: chunked upload failed — falling back to single-PUT. Cause: ${e.message}")
                }
            }

            if (!chunkedSucceeded) {
                // POST metadata; PUT file too when wantsArtifactUpload;
                // PUT deps gz too when depsPayload != null. Each PUT
                // is independently best-effort inside the uploader —
                // a failing deps PUT must not fail an otherwise-green
                // artefact upload, and vice versa.
                try {
                    BundleUploader.uploadData(
                        file = uploadZip,
                        json = json,
                        appToken = appToken,
                        endpoint = endpoint.get(),
                        requestArtifactUpload = wantsArtifactUpload,
                        dependenciesGzFile = depsPayload?.gzFile,
                        timingsGzFile = timingsPayload?.gzFile,
                        logger = logger,
                        debug = isDebug
                    )
                } catch (e: Exception) {
                    logger.error("Bugsee: build upload failed (build-info / size analysis unavailable for this build): ${e.message}")
                }
            }

            // Evaluate size-check thresholds AFTER the upload so a
            // failing build is still recorded server-side (the user
            // wants the failed artifact in the dashboard for
            // diagnosis). When the lookup yielded no baseline (first
            // build / network hiccup / lookup disabled), there's
            // nothing to compare against — log an info breadcrumb
            // and skip.
            if (baseline != null) {
                val result = SizeCheckEvaluator.evaluate(
                    localSize = artifactSize,
                    baselineSize = baseline.artifactSize,
                    thresholds = sizeCheckThresholds,
                )
                val message = SizeCheckEvaluator.formatMessage(
                    localSize = artifactSize,
                    baselineSize = baseline.artifactSize,
                    baselineVersion = baseline.version,
                    baselineBuild = baseline.build,
                    result = result,
                )
                when (result.outcome) {
                    SizeCheckEvaluator.Outcome.PASS -> {
                        if (isDebug) logger.warn(message)
                    }
                    SizeCheckEvaluator.Outcome.WARN -> logger.warn(message)
                    SizeCheckEvaluator.Outcome.FAIL -> throw GradleException(message)
                }
            } else if (sizeCheckEnabled.getOrElse(false) && sizeCheckThresholds.anyActive) {
                logger.info("Bugsee: size-check skipped — no baseline available")
            }
        } finally {
            uploadZip.delete()
        }
    }

    /**
     * Collapse the configured threshold properties into the evaluator's
     * shape, dropping any value that is unset, non-finite, or `<= 0`.
     * The "0 == disabled" rule lives here so every code path downstream
     * sees a uniform `null` for "not active".
     *
     * Non-finite (`NaN` / `±Infinity`) values are dropped explicitly:
     * `NaN > 0.0` is already `false` so it would land as `null` either
     * way, but `Double.POSITIVE_INFINITY > 0.0` is `true` and would
     * survive — producing a gate that can never trigger. Treat both as
     * "disabled" so a misconfigured DSL value behaves the same as an
     * unset one.
     */
    private fun resolveSizeCheckThresholds(): SizeCheckEvaluator.Thresholds =
        SizeCheckEvaluator.Thresholds(
            warningPercent = sizeCheckWarningPercent.orNull?.takeIf { it.isFinite() && it > 0.0 },
            failPercent    = sizeCheckFailPercent.orNull?.takeIf { it.isFinite() && it > 0.0 },
            warningBytes   = sizeCheckWarningBytes.orNull?.takeIf { it > 0L },
            failBytes      = sizeCheckFailBytes.orNull?.takeIf { it > 0L },
        )

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
    private fun resolveBuildMetadataJson(timingsSnapshot: List<TaskTiming>?): JSONObject? {
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
        // short builds, replay-from-cache) doesn't throw here. Also
        // honour `buildInfo.timings.enabled = false` — when timings
        // are opted out, the inline summary is omitted too so the
        // wire shape matches the "no timings" contract end-to-end
        // (no `build_metadata.timings`, no `request_timings_upload`).
        if (timingsEnabled.getOrElse(true) && timingsSnapshot != null) {
            val rollup = buildTimings(timingsSnapshot)
            if (hasUsefulTimings(rollup)) {
                obj.put("timings", rollup.toJson())
            }
        }

        return if (obj.length() == 0) null else obj
    }

    /**
     * Tightened inclusion gate for the inline `build_metadata.timings`
     * summary. The raw `toJson().length() > 0` check used to suffice,
     * but a cache-replay edge case can produce a rollup where ALL of
     * `total_ms`, the per-category sums, and every `top_tasks[].duration_ms`
     * are zero — yet `topTasks` is non-empty (one entry with `duration_ms = 0`).
     * `toJson()` then emits a non-empty `top_tasks` array, the viewer
     * sees `hasTimings = true`, and renders an empty Gantt panel. Demand
     * at least one signal of real work.
     */
    private fun hasUsefulTimings(rollup: BuildTimings): Boolean {
        if (rollup.totalMs > 0L) return true
        if (rollup.managedCodeMs > 0L) return true
        if (rollup.nativeMs > 0L) return true
        if (rollup.resourcesMs > 0L) return true
        if (rollup.packagingMs > 0L) return true
        if (rollup.otherMs > 0L) return true
        return rollup.topTasks.any { it.durationMs > 0L }
    }

    /**
     * Carrier for the two outputs of dependency collection: the inline
     * scalar summary (embedded in the metadata POST) and the gzipped
     * full per-entry blob (PUT to the dependencies presigned URL).
     */
    private data class DepsPayload(val summaryJson: JSONObject, val gzFile: File)

    /**
     * Carrier for the build-timings detail blob — the full per-task
     * timeline used by the viewer's Gantt-chart renderer. The inline
     * summary (totals + per-category cumulative + top-10 tasks) is
     * already written under `build_metadata.timings` and travels in
     * the metadata POST; the gz file here is PUT to the presigned URL
     * the server returns when `request_timings_upload: true`.
     */
    private data class TimingsPayload(val gzFile: File)

    /**
     * Build the timings detail blob if the build collected any task
     * timings. Returns `null` when the timing service captured zero
     * tasks (very short builds, replay-from-cache, edge cases the
     * Gradle listener didn't fire on) — the caller then omits the
     * `request_timings_upload` flag entirely.
     */
    private fun resolveTimingsPayload(timingsSnapshot: List<TaskTiming>?): TimingsPayload? {
        if (timingsSnapshot == null || timingsSnapshot.isEmpty()) return null
        val gz = File(temporaryDir, "bugsee-timings.json.gz")
        TimingsPayloadSerializer.writeGz(timingsSnapshot, gz)
        return TimingsPayload(gzFile = gz)
    }

    /**
     * Runs the [DependencyCollector] against the wired runtime
     * resolution result + declared-scope map, serialises both outputs.
     * Returns `null` when collection is enabled but no useful data
     * could be gathered (e.g. the variant has no resolution result —
     * exotic projects or unit-test scaffolding).
     */
    private fun resolveDependenciesPayload(): DepsPayload? {
        val root = runtimeRootComponent.orNull ?: return null
        val maxCount = dependenciesMaxCount.getOrElse(5_000)
        val includeReason = dependenciesIncludeReason.getOrElse(false)
        val declared = declaredScopes.getOrElse(emptyMap())
        val fileDeps = fileDependencies.getOrElse(emptyList()).map { encoded ->
            // Encoded as "scope|displayName"; an empty scope segment
            // is permitted and decodes back to `null`.
            val idx = encoded.indexOf('|')
            val scope = if (idx >= 0) encoded.substring(0, idx).ifEmpty { null } else null
            val name = if (idx >= 0) encoded.substring(idx + 1) else encoded
            DependencyCollector.FileDep(displayName = name, scope = scope)
        }

        // Scope mode. Unknown / unset values fall back to `"runtime"`
        // (default). `"compile_runtime"` is reserved — it requires a
        // second resolved configuration (compileClasspath) wired in
        // at registration. Until that lands, log a one-line warning
        // so the user knows their DSL knob is being ignored.
        val scopeMode = dependenciesScope.getOrElse("runtime").lowercase()
        val directOnly: Boolean = when (scopeMode) {
            "runtime_direct_only" -> true
            "runtime", "" -> false
            "compile_runtime" -> {
                logger.warn(
                    "Bugsee: bugsee.buildInfo.dependencies.scope = " +
                    "\"compile_runtime\" is not yet implemented — " +
                    "falling back to runtime classpath."
                )
                false
            }
            else -> {
                logger.warn(
                    "Bugsee: bugsee.buildInfo.dependencies.scope = " +
                    "\"$scopeMode\" is not a recognised value — " +
                    "falling back to runtime classpath. " +
                    "Supported: \"runtime\", \"runtime_direct_only\", \"compile_runtime\"."
                )
                false
            }
        }

        val collector = DependencyCollector(
            maxCount = maxCount,
            includeSelectedReason = includeReason,
            directOnly = directOnly
        )
        val result = collector.collect(root, declared, fileDeps)
        if (result.entries.isEmpty()) {
            // Nothing to ship — empty dep set is uncommon (the bugsee
            // SDK itself is a dep) but legitimate for sample / fixture
            // projects. Skip the second PUT rather than uploading a
            // 1-element JSON object the viewer would have to special-
            // case.
            return null
        }

        val summaryJson = DependencyPayloadSerializer.summaryJson(result.summary)
        val gz = File(temporaryDir, "bugsee-dependencies.json.gz")
        DependencyPayloadSerializer.writeEntriesGz(result.entries, gz)
        return DepsPayload(summaryJson = summaryJson, gzFile = gz)
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
