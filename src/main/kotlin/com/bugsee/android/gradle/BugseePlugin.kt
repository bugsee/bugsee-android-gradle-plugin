package com.bugsee.android.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.bugsee.android.gradle.config.PluginPropertiesApplier
import com.bugsee.android.gradle.upload.DependencyCollector
import com.bugsee.android.gradle.instrumentation.InstrumentationConfigResolver
import com.bugsee.android.gradle.instrumentation.InstrumentationRegistrar
import com.bugsee.android.gradle.instrumentation.extensions_init.ExtensionsInitInstrumentation
import com.bugsee.android.gradle.manifest.BugseeManifestTask
import com.bugsee.android.gradle.upload.AppTokenResolver
import com.bugsee.android.gradle.upload.BuildTimingService
import com.bugsee.android.gradle.upload.BundleUploadTask
import com.bugsee.android.gradle.upload.MappingUploadTask
import com.bugsee.android.gradle.upload.NativeUploadTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.build.event.BuildEventsListenerRegistry
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File
import javax.inject.Inject

abstract class BugseePlugin : Plugin<Project>, KotlinCompilerPluginSupportPlugin {

    private var pluginExtension: BugseePluginExtension? = null

    // Injected by Gradle at plugin apply time — the service registry
    // we need to hook the BuildTimingService into the task-completion
    // event stream. Gradle provides this via abstract @Inject on the
    // plugin class.
    @get:Inject
    abstract val listenerRegistry: BuildEventsListenerRegistry

    override fun apply(project: Project) {
        val extension = project.extensions.create(PLUGIN_NAME, BugseePluginExtension::class.java)
        pluginExtension = extension

        // Apply <rootProject>/bugsee.properties `plugin.*` overrides as
        // Gradle Property conventions BEFORE the user's `bugsee { … }`
        // DSL block evaluates. Convention precedence then naturally
        // yields:
        //   DSL .set(...)  >  bugsee.properties plugin.X  >  built-in default
        // because every extension property is initialised with
        // `objects.property(...).convention(default)`, the applier
        // calls `.convention(propsValue)` which supersedes the default,
        // and the user's DSL `.set(...)` later supersedes both.
        PluginPropertiesApplier.applyTo(project, extension)

        // Register the per-build timing service once per Gradle build.
        // The shared-services container deduplicates across subprojects
        // that each apply the bugsee plugin, so a multi-module build
        // still sees a single service and one rollup. Consumers (the
        // upload task) wire to this provider declaratively via
        // `@ServiceReference(BUILD_TIMING_SERVICE_NAME)`; no explicit
        // `task.usesService(...)` call is needed.
        val timingService: Provider<BuildTimingService> =
            project.gradle.sharedServices.registerIfAbsent(
                BUILD_TIMING_SERVICE_NAME,
                BuildTimingService::class.java
            ) { }

        // Single-flight guard for the task-completion listener. See
        // [registerBuildTimingListenerOnce] for the rationale
        // (multi-module de-duplication).
        registerBuildTimingListenerOnce(project, listenerRegistry, timingService)

        // Auto-install Bugsee extension modules when matching third-party
        // dependencies are detected. Uses withDependencies to inject before
        // dependency resolution locks. Skips if the Bugsee module is already
        // declared (e.g. via project dependency during development or an
        // explicit Maven dependency).
        project.configurations.configureEach { config ->
            if (config.name == "implementation") {
                config.withDependencies { deps ->
                    val isDebug = extension.debug.getOrElse(false)
                    val inst = extension.instrumentation

                    // Skip auto-add when the project IS one of the Bugsee
                    // modules itself — these are the modules other apps
                    // would depend on, not consumers of them. Without this
                    // skip, applying the bugsee plugin to e.g. :library
                    // causes the plugin to auto-add `bugsee-okhttp` as a
                    // dependency of :library, creating a circular setup
                    // and failing because the SNAPSHOT artifact for the
                    // auto-added module rarely exists in the local maven
                    // repo at the same coordinate as the plugin itself.
                    //
                    // Two-layer detection:
                    // 1. Group ID — covers any future Bugsee subproject
                    //    that publishes under `com.bugsee` even if its
                    //    name is not in the hardcoded set below.
                    // 2. Hardcoded module-name allowlist — covers
                    //    subprojects that have not yet had their `group`
                    //    set during `withDependencies` evaluation (group
                    //    can be assigned after the plugin is applied).
                    val projectGroup = project.group?.toString().orEmpty()
                    if (projectGroup.startsWith("com.bugsee")
                            || project.name in BUGSEE_INTERNAL_MODULE_NAMES) {
                        return@withDependencies
                    }

                    // Auto-add the core Bugsee SDK if the consuming app has
                    // not declared one. Dynamic version range with the
                    // plugin's MIN_SDK_VERSION as a floor — the consumer
                    // automatically picks up newer compatible SDK releases
                    // without bumping the plugin. If the user has declared
                    // their own version on any configuration (`api`,
                    // `compileOnly`, variant configs, …), defer to it
                    // rather than layering a dynamic dep on top.
                    if (!isCoreSdkPresent(project)) {
                        if (isDebug) {
                            project.logger.warn(
                                "Bugsee: Auto-adding bugsee-android runtime dependency >= $MIN_SDK_VERSION"
                            )
                        }
                        deps.add(
                            project.dependencies.create(
                                "com.bugsee:bugsee-android:[$MIN_SDK_VERSION,)"
                            )
                        )
                    }

                    // Optional SDK modules driven by user opt-in via the
                    // `bugsee { ndk { enabled.set(true) }; feedback(true) }`
                    // DSL. Same already-declared check as the
                    // auto-instrumented modules below.
                    if (extension.ndk.enabled.get()) {
                        autoAddModule(project, deps, "bugsee-android-ndk", "ndk", isDebug)
                    }
                    if (extension.feedback.get()) {
                        autoAddModule(project, deps, "bugsee-android-feedback", "feedback", isDebug)
                    }

                    if (hasComposeDependency(project) && isFeatureEnabled(inst.compose)) {
                        autoAddModule(project, deps, "bugsee-android-compose", "compose", isDebug)
                    }
                    if (hasOkHttpDependency(project) && isFeatureEnabled(inst.okhttp)) {
                        autoAddModule(project, deps, "bugsee-android-okhttp", "okhttp", isDebug)
                    }
                    if (hasKtorDependency(project, 2) && isFeatureEnabled(inst.ktor)) {
                        autoAddModule(project, deps, "bugsee-android-ktor-2", "ktor-2", isDebug)
                    }
                    if (hasKtorDependency(project, 3) && isFeatureEnabled(inst.ktor)) {
                        autoAddModule(project, deps, "bugsee-android-ktor-3", "ktor-3", isDebug)
                    }
                    if (hasCronetDependency(project) && isFeatureEnabled(inst.cronet)) {
                        autoAddModule(project, deps, "bugsee-android-cronet", "cronet", isDebug)
                    }
                }
            }
        }

        project.afterEvaluate {
            val isDebug = extension.debug.get()
            if (isDebug) project.logger.warn("Bugsee: Plugin applied")

            // Verify android plugin is applied
            if (project.extensions.findByName("android") == null) {
                throw IllegalStateException(
                    "Must apply 'com.android.application' or 'com.android.library' first!"
                )
            }
        }

        val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
        if (androidComponents == null) {
            project.logger.warn("Bugsee: AndroidComponentsExtension not found. Is the Android Gradle Plugin applied?")
            return
        }

        androidComponents.onVariants { variant ->
            val isDebug = extension.debug.get()
            val variantName = variant.name
            val capitalizedVariant = variantName.replaceFirstChar { it.uppercase() }

            if (isDebug) project.logger.warn("Bugsee: Configuring variant $variantName")

            // --- Manifest UUID injection + extension provider stripping ---
            val manifestTaskProvider = registerManifestTask(project, variant, extension, capitalizedVariant)

            // --- Application variant specific tasks (upload mapping, NDK symbols, bundle) ---
            if (variant is ApplicationVariant) {
                registerMappingUploadTask(project, variant, extension, capitalizedVariant)

                if (extension.ndk.enabled.get()) {
                    registerNativeUploadTask(project, variant, extension, capitalizedVariant)
                }

                // Build-info registration runs by default for every
                // matching variant. Size analysis is a sub-feature
                // that piggybacks on the same task — when both are
                // active, the task additionally requests a presigned
                // PUT URL and ships the artefact bytes.
                if (shouldRegisterBuildInfoFor(project, variant, extension, isDebug)) {
                    registerBundleUploadTask(project, variant, extension, capitalizedVariant)
                }
            }

            // --- Bytecode instrumentation (application modules only) ---
            if (variant is ApplicationVariant) {
                val sourceManifest = project.file("src/main/AndroidManifest.xml")
                val configResolver = InstrumentationConfigResolver(
                    extension.instrumentation,
                    project,
                    sourceManifest.takeIf { it.exists() }
                )
                if (configResolver.isGloballyEnabled()) {
                    val extras = listOf(
                        ExtensionsInitInstrumentation(extension, manifestTaskProvider),
                    )
                    val registrar = InstrumentationRegistrar(
                        project, project.logger, isDebug, configResolver, extras
                    )
                    registrar.applyAll(variant)
                } else {
                    if (isDebug) project.logger.warn("Bugsee: Bytecode instrumentation is globally disabled")
                }
            }
        }
    }

    private fun registerManifestTask(
        project: Project,
        variant: com.android.build.api.variant.Variant,
        extension: BugseePluginExtension,
        capitalizedVariant: String
    ): org.gradle.api.tasks.TaskProvider<BugseeManifestTask> {
        val taskProvider = project.tasks.register(
            "createBugsee${capitalizedVariant}ManifestConfig",
            BugseeManifestTask::class.java
        ) { task ->
            task.debug.set(extension.debug)
            task.optimizeExtensionsLoading.set(extension.optimizeExtensionsLoading)
            task.detectedExtensions.set(
                project.layout.buildDirectory.file(
                    "intermediates/bugsee/${variant.name}/detected-extensions.txt"
                )
            )
            task.group = "bugsee"
            task.description = "Injects Bugsee BUILD_UUID and consolidates extension providers for $capitalizedVariant"
        }

        // Wire into the manifest artifact transformation pipeline
        variant.artifacts.use(taskProvider)
            .wiredWithFiles(
                BugseeManifestTask::mergedManifest,
                BugseeManifestTask::updatedManifest
            )
            .toTransform(SingleArtifact.MERGED_MANIFEST)

        return taskProvider
    }

    private fun registerMappingUploadTask(
        project: Project,
        variant: ApplicationVariant,
        extension: BugseePluginExtension,
        capitalizedVariant: String
    ) {
        val mappingUploadTaskProvider = project.tasks.register(
            "uploadBugsee${capitalizedVariant}Mapping",
            MappingUploadTask::class.java
        ) { task ->
            task.debug.set(extension.debug)
            task.variantName.set(variant.name)
            task.endpoint.set(extension.endpoint)
            task.group = "bugsee"
            task.description = "Uploads ProGuard/R8 mapping file for $capitalizedVariant"

            // Wire the mapping file from AGP's artifact API
            task.mappingFile.set(
                variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
            )

            // Wire the merged manifest
            task.manifestFile.set(
                variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
            )
        }

        // Finalize after assemble and bundle tasks
        project.tasks.configureEach { t ->
            if (t.name == "assemble$capitalizedVariant" || t.name == "bundle$capitalizedVariant") {
                t.finalizedBy(mappingUploadTaskProvider)
            }
        }
    }

    private fun registerNativeUploadTask(
        project: Project,
        variant: ApplicationVariant,
        extension: BugseePluginExtension,
        capitalizedVariant: String
    ) {
        val nativeUploadTaskProvider = project.tasks.register(
            "uploadBugsee${capitalizedVariant}Native",
            NativeUploadTask::class.java
        ) { task ->
            task.debug.set(extension.debug)
            task.variantName.set(variant.name)
            task.endpoint.set(extension.endpoint)
            task.forceUpload.set(extension.ndk.forceDebugSymbolsUpload)
            task.group = "bugsee"
            task.description = "Uploads NDK native debug symbols for $capitalizedVariant"

            // Wire the merged manifest
            task.manifestFile.set(
                variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
            )

            // Ensure native debug metadata is extracted before upload.
            // Uses tasks.matching (live filter) to avoid task realization during configuration.
            task.dependsOn(
                project.tasks.matching {
                    it.name == "extract${capitalizedVariant}NativeDebugMetadata"
                }
            )
        }

        // Finalize after assemble and bundle tasks
        project.tasks.configureEach { t ->
            if (t.name == "assemble$capitalizedVariant" || t.name == "bundle$capitalizedVariant") {
                t.finalizedBy(nativeUploadTaskProvider)
            }
        }
    }

    private fun registerBundleUploadTask(
        project: Project,
        variant: ApplicationVariant,
        extension: BugseePluginExtension,
        capitalizedVariant: String
    ) {
        val buildConfig = extension.buildInfo.sizeAnalysis.buildConfiguration
            .orElse(project.provider { variant.name })
        val isDebug = extension.debug.getOrElse(false)

        // Resolve `android.compileSdk` at configuration time so the
        // task input is a plain string, not a project-scoped lookup
        // executed lazily (which would be a configuration-cache leak).
        // `ApplicationExtension` is the AGP DSL type for `android { }`
        // in app modules; reading `.compileSdk` is a plain property
        // access and CC-safe. Sub-configurations that set
        // `compileSdkPreview` instead are rare and surface as null
        // here — we emit nothing rather than guess.
        val compileSdkValue = project.extensions.findByType(
            com.android.build.api.dsl.ApplicationExtension::class.java
        )?.compileSdk?.toString()

        // Configuration-time app-token resolution. Invokes the
        // extension-level sources (closure, provider, defaultAppToken)
        // and falls through to the dedicated `bugsee.properties` file at
        // the project root. Both run here so the task can receive a
        // plain `@Input String` instead of reaching into
        // `project.extensions.getByType(...)` at execution — a
        // configuration-cache violation.
        val preResolvedToken: String? = AppTokenResolver.resolveFromExtension(
            extension, variant.name, project.logger, isDebug,
        ) ?: AppTokenResolver.resolveFromPropertiesFile(
            project.rootProject.projectDir, project.logger, isDebug,
        )

        // Pre-resolve the `res/` source files for the
        // `@string/foo`-fallback path inside
        // `AppTokenResolver.resolveFromManifest`. Passing the files
        // via a `ConfigurableFileCollection` task input lets the
        // resolver walk them at execution time without needing to
        // reach back into the Android DSL (a CC leak).
        val stringResFiles: List<File> = try {
            val android = project.extensions.findByName("android")
            if (android != null) {
                val sourceSets = android.javaClass.getMethod("getSourceSets").invoke(android)
                val mainSourceSet = sourceSets.javaClass
                    .getMethod("getByName", String::class.java)
                    .invoke(sourceSets, "main")
                val res = mainSourceSet.javaClass.getMethod("getRes").invoke(mainSourceSet)
                @Suppress("UNCHECKED_CAST")
                (res.javaClass.getMethod("getSourceFiles").invoke(res) as Iterable<File>).toList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            project.logger.warn(
                "Bugsee: could not enumerate string-resource source files — " +
                    "`@string/foo` tokens in AndroidManifest meta-data will not resolve: ${e.message}"
            )
            emptyList()
        }

        // AAB upload task — wired to bundle output
        val bundleUploadTaskProvider = project.tasks.register(
            "uploadBugsee${capitalizedVariant}Bundle",
            BundleUploadTask::class.java
        ) { task ->
            task.debug.set(extension.debug)
            task.variantName.set(variant.name)
            task.buildConfiguration.set(buildConfig)
            task.endpoint.set(extension.endpoint)
            task.format.set("aab")
            task.group = "bugsee"
            task.description = "Uploads AAB for size analysis for $capitalizedVariant"

            task.bundleFile.set(
                variant.artifacts.get(SingleArtifact.BUNDLE)
            )
            task.manifestFile.set(
                variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
            )
            task.mappingFile.set(
                variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
            )
            compileSdkValue?.let { task.buildSdkVersion.set(it) }
            preResolvedToken?.let { task.preResolvedAppToken.set(it) }
            task.stringResourceFiles.from(stringResFiles)
            task.chunkedUpload.set(extension.buildInfo.sizeAnalysis.chunkedUpload)
            task.requestArtifactUpload.set(extension.buildInfo.sizeAnalysis.enabled)
            task.projectDirectory.set(project.layout.projectDirectory)
            wireSizeCheckInputs(task, project, extension.buildInfo.sizeCheck)
            wireDependenciesCollectionInputs(task, project, variant, extension.buildInfo.dependencies)
            // The timing service is auto-wired on the task via
            // `@ServiceReference(BUILD_TIMING_SERVICE_NAME)`; no
            // explicit `set`/`usesService` call needed. The
            // user-facing on/off flag still has to be set explicitly.
            task.timingsEnabled.set(extension.buildInfo.timings.enabled)
        }

        project.tasks.configureEach { t ->
            if (t.name == "bundle$capitalizedVariant") {
                t.finalizedBy(bundleUploadTaskProvider)
            }
        }

        // APK upload task — wired to APK output
        val apkUploadTaskProvider = project.tasks.register(
            "uploadBugsee${capitalizedVariant}Apk",
            BundleUploadTask::class.java
        ) { task ->
            task.debug.set(extension.debug)
            task.variantName.set(variant.name)
            task.buildConfiguration.set(buildConfig)
            task.endpoint.set(extension.endpoint)
            task.format.set("apk")
            task.group = "bugsee"
            task.description = "Uploads APK for size analysis for $capitalizedVariant"

            task.apkDirectory.set(
                variant.artifacts.get(SingleArtifact.APK)
            )
            task.manifestFile.set(
                variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
            )
            task.mappingFile.set(
                variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
            )
            compileSdkValue?.let { task.buildSdkVersion.set(it) }
            preResolvedToken?.let { task.preResolvedAppToken.set(it) }
            task.stringResourceFiles.from(stringResFiles)
            task.chunkedUpload.set(extension.buildInfo.sizeAnalysis.chunkedUpload)
            task.requestArtifactUpload.set(extension.buildInfo.sizeAnalysis.enabled)
            task.projectDirectory.set(project.layout.projectDirectory)
            wireSizeCheckInputs(task, project, extension.buildInfo.sizeCheck)
            wireDependenciesCollectionInputs(task, project, variant, extension.buildInfo.dependencies)
            // Timing service auto-wired via @ServiceReference (see above).
            task.timingsEnabled.set(extension.buildInfo.timings.enabled)
        }

        project.tasks.configureEach { t ->
            if (t.name == "assemble$capitalizedVariant") {
                t.finalizedBy(apkUploadTaskProvider)
            }
        }
    }

    // --- KotlinCompilerPluginSupportPlugin ---

    override fun getCompilerPluginId(): String = "com.bugsee.compose.compiler"

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = "com.bugsee",
        artifactId = "bugsee-compose-compiler-plugin",
        version = PLUGIN_VERSION
    )

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
        val project = kotlinCompilation.target.project
        val extension = pluginExtension ?: return false
        if (!hasComposeDependency(project)) {
            return false
        }
        // Apply the compiler plugin if EITHER subfeature is enabled. Each
        // subfeature is gated independently inside the compiler plugin via
        // its own SubpluginOption, so the user can keep one on while
        // turning the other off.
        val tagEnabled = isFeatureEnabled(extension.instrumentation.compose)
        val secureEnabled = isFeatureEnabled(extension.instrumentation.composeSecure)
        return tagEnabled || secureEnabled
    }

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>
    ): Provider<List<SubpluginOption>> {
        return kotlinCompilation.target.project.provider {
            // Re-read pluginExtension inside the lambda. The lambda is
            // evaluated at task configuration time, after apply() has run,
            // so this is safer than capturing the field reference at
            // applyToCompilation() entry (which could in principle race
            // with apply() on plugin reconfiguration).
            val extension = pluginExtension
            val tagEnabled = extension != null
                    && isFeatureEnabled(extension.instrumentation.compose)
            val secureEnabled = extension != null
                    && isFeatureEnabled(extension.instrumentation.composeSecure)
            listOf(
                // Backward-compatible name: existing CLI option "enabled"
                // continues to control tag injection.
                SubpluginOption("enabled", tagEnabled.toString()),
                SubpluginOption("secure", secureEnabled.toString())
            )
        }
    }

    // --- Dependency detection helpers ---

    private fun hasComposeDependency(project: Project): Boolean {
        return project.configurations.any { config ->
            config.dependencies.any { dep ->
                dep.group?.startsWith("androidx.compose") == true
            }
        }
    }

    private fun hasOkHttpDependency(project: Project): Boolean {
        return project.configurations.any { config ->
            config.dependencies.any { dep ->
                dep.group == "com.squareup.okhttp3"
            }
        }
    }

    private fun hasKtorDependency(project: Project, majorVersion: Int): Boolean {
        return project.configurations.any { config ->
            config.dependencies.any { dep ->
                dep.group == "io.ktor" && dep.version?.startsWith("$majorVersion.") == true
            }
        }
    }

    private fun hasCronetDependency(project: Project): Boolean {
        return project.configurations.any { config ->
            config.dependencies.any { dep ->
                dep.group == "org.chromium.net"
            }
        }
    }

    /**
     * Returns `true` if the feature is enabled (default: enabled when not explicitly set).
     */
    private fun isFeatureEnabled(property: Property<Boolean>): Boolean {
        return !property.isPresent || property.get()
    }

    /**
     * Per-variant decision on whether to register the build-info
     * upload task. Centralises three concerns:
     *
     *   1. `buildInfo.enabled` — the master gate (default `true`).
     *   2. Validation: if `sizeAnalysis.enabled = true` is set while
     *      `buildInfo.enabled = false`, log a warning and skip both.
     *      Size analysis on its own would have nothing to attach to.
     *   3. Release-only filter: by default only non-debuggable build
     *      types register, since users typically don't want every
     *      debug rebuild flooding the dashboard. Override via
     *      `buildInfo.allBuildTypes.set(true)`.
     *
     * Returns `false` for any variant that fails any of the three.
     * Logs decisions at `info` level when debug mode is on so users
     * troubleshooting "why didn't my build register" have a
     * breadcrumb in the build log.
     */
    private fun shouldRegisterBuildInfoFor(
        project: Project,
        variant: ApplicationVariant,
        extension: BugseePluginExtension,
        isDebug: Boolean,
    ): Boolean {
        val buildInfoEnabled = extension.buildInfo.enabled.getOrElse(true)
        val sizeAnalysisEnabled = extension.buildInfo.sizeAnalysis.enabled.getOrElse(false)

        if (!buildInfoEnabled) {
            // Configuration error: sizeAnalysis is meaningless without
            // build-info. The warning is hoisted to a project-level
            // one-shot guard (see logSizeAnalysisMisconfigOnce below)
            // so an app with N variants doesn't print N copies of the
            // same diagnostic.
            if (sizeAnalysisEnabled) {
                logSizeAnalysisMisconfigOnce(project)
            }
            if (isDebug) {
                project.logger.warn(
                    "Bugsee: skipping ${variant.name} — buildInfo is disabled"
                )
            }
            return false
        }

        if (!isReleaseLikeVariant(project, variant) &&
            !extension.buildInfo.allBuildTypes.getOrElse(false)) {
            if (isDebug) {
                project.logger.warn(
                    "Bugsee: skipping non-release variant '${variant.name}' — " +
                        "set buildInfo.allBuildTypes(true) to include it"
                )
            }
            return false
        }

        return true
    }

    /**
     * Per-project flag that's flipped the first time the
     * sizeAnalysis-on / buildInfo-off misconfiguration warning fires.
     * `onVariants` invokes [shouldRegisterBuildInfoFor] once per
     * `ApplicationVariant`, so a multi-flavor app would otherwise
     * emit the same warning N times. Stored on `project.extensions`
     * via a small extra-properties marker rather than an instance
     * field on the plugin (the plugin instance is shared across
     * subprojects and using a field would coalesce warnings across
     * unrelated apps).
     */
    private fun logSizeAnalysisMisconfigOnce(project: Project) {
        val key = "bugseeSizeAnalysisMisconfigWarned"
        val extra = project.extensions.extraProperties
        if (extra.has(key) && extra.get(key) == true) {
            return
        }
        extra.set(key, true)
        project.logger.warn(
            "Bugsee: sizeAnalysis is enabled but buildInfo is disabled — " +
                "sizeAnalysis is a no-op without buildInfo. Either enable " +
                "buildInfo or disable sizeAnalysis."
        )
    }

    /**
     * Resolve the variant's build-type definition and return `true`
     * when it's NOT debuggable. Used as the release-only filter for
     * build-info registration.
     *
     * AGP doesn't expose `isDebuggable` directly on the variant in
     * recent versions — we read it from `android.buildTypes` via the
     * variant's build-type name. Falls back to a name-based heuristic
     * (`!buildType.contains("debug")`) if the lookup fails for any
     * reason; that's looser than the DSL flag but still drops the
     * default `debug` and any custom `*Debug` types.
     */
    private fun isReleaseLikeVariant(project: Project, variant: ApplicationVariant): Boolean {
        val buildTypeName = variant.buildType ?: return false
        val androidExt = project.extensions.findByType(
            com.android.build.api.dsl.ApplicationExtension::class.java
        )
        val buildType = androidExt?.buildTypes?.findByName(buildTypeName)
        return if (buildType != null) {
            !buildType.isDebuggable
        } else {
            !buildTypeName.lowercase().contains("debug")
        }
    }

    /**
     * Wires the size-check task inputs to the merged DSL + env-var
     * source. The DSL property wins when set; otherwise the matching
     * `BUGSEE_SIZE_CHECK_*` environment variable is consulted; when
     * both are absent the property stays unset so the task's
     * `@Optional` declaration takes effect.
     *
     * Reads the env vars via `project.providers.environmentVariable`
     * — that registers them as configuration-cache inputs, so a
     * changed env var on a re-run busts the cached task graph and
     * the new threshold takes effect without a manual `--rerun`.
     *
     * `0` and unparseable values normalise to `0.0` / `0L` here; the
     * task itself enforces "0 == disabled" downstream so a single
     * place owns the rule.
     */
    private fun wireSizeCheckInputs(
        task: BundleUploadTask,
        project: Project,
        sizeCheck: BugseeSizeCheckExtension,
    ) {
        val providers = project.providers
        val logger = project.logger

        fun envBool(name: String): Provider<Boolean> =
            providers.environmentVariable(name).map {
                it.equals("true", ignoreCase = true) || it == "1"
            }

        // For numeric env vars, malformed input collapses to "disabled"
        // (the task's resolveSizeCheckThresholds() drops zero/negative
        // values). A silent collapse is a real footgun — a typo like
        // `BUGSEE_SIZE_CHECK_FAIL_PCT=10..0` becomes "no fail gate"
        // with zero feedback. Surface a logger.warn so the user sees
        // the misconfiguration in the build output.
        fun envDouble(name: String): Provider<Double> =
            providers.environmentVariable(name).map { raw ->
                val parsed = raw.toDoubleOrNull()
                if (parsed == null || !parsed.isFinite()) {
                    logger.warn(
                        "Bugsee: ignoring $name=$raw — not a finite number; " +
                            "size-check threshold disabled"
                    )
                    0.0
                } else parsed
            }

        fun envLong(name: String): Provider<Long> =
            providers.environmentVariable(name).map { raw ->
                val parsed = raw.toLongOrNull()
                if (parsed == null) {
                    logger.warn(
                        "Bugsee: ignoring $name=$raw — not an integer; " +
                            "size-check threshold disabled"
                    )
                    0L
                } else parsed
            }

        task.sizeCheckEnabled.set(
            sizeCheck.enabled.orElse(envBool("BUGSEE_SIZE_CHECK_ENABLED")).orElse(false)
        )
        task.sizeCheckWarningPercent.set(
            sizeCheck.warningPercent.orElse(envDouble("BUGSEE_SIZE_CHECK_WARNING_PCT"))
        )
        task.sizeCheckFailPercent.set(
            sizeCheck.failPercent.orElse(envDouble("BUGSEE_SIZE_CHECK_FAIL_PCT"))
        )
        task.sizeCheckWarningBytes.set(
            sizeCheck.warningBytes.orElse(envLong("BUGSEE_SIZE_CHECK_WARNING_BYTES"))
        )
        task.sizeCheckFailBytes.set(
            sizeCheck.failBytes.orElse(envLong("BUGSEE_SIZE_CHECK_FAIL_BYTES"))
        )
    }

    /**
     * Wire the dependencies-collection task inputs from the
     * `bugsee.buildInfo.dependencies` DSL block.
     *
     * The resolution result is set via a Provider chain so the
     * actual dependency graph walk happens at task execution time,
     * not at configuration time. Declared-scope and file-dependency
     * extraction reads `project.configurations` lazily as well —
     * `project.provider { ... }` defers the closure to execution.
     */
    private fun wireDependenciesCollectionInputs(
        task: BundleUploadTask,
        project: Project,
        variant: ApplicationVariant,
        ext: BugseeDependenciesCollectionExtension,
    ) {
        // `requestDependenciesUpload` is the per-sub-feature gate.
        // The outer `buildInfo.enabled = false` short-circuits the
        // entire `registerBundleUploadTask` call site upstream, so
        // this method only runs at all when buildInfo is on. Within
        // that, the user can independently disable just the deps
        // sub-feature by setting `dependencies.enabled = false` —
        // build-info still ships, deps don't.
        task.requestDependenciesUpload.set(ext.enabled)
        task.dependenciesScope.set(ext.scope)
        task.dependenciesIncludeReason.set(ext.includeSelectedReason)
        task.dependenciesMaxCount.set(ext.maxCount)

        // Runtime classpath resolution result — lazy. The collector
        // walks it at execution time.
        task.runtimeRootComponent.set(
            variant.runtimeConfiguration.incoming.resolutionResult.rootComponent
        )

        // Declared-scope map + file-deps list. Both are computed at
        // configuration time (eagerly walking `configurations.findByName`),
        // which is CC-safe because we only read declared-dependency
        // names + groups (`ModuleDependency.group / name`) — no resolution
        // touched here.
        val scopeNames = listOf("api", "implementation", "runtimeOnly", "compileOnly")
        task.declaredScopes.set(
            project.provider {
                val confs = LinkedHashMap<String, org.gradle.api.artifacts.Configuration>()
                for (n in scopeNames) {
                    project.configurations.findByName(n)?.let { confs[n] = it }
                }
                DependencyCollector.collectDeclaredScopes(confs)
            }
        )
        task.fileDependencies.set(
            project.provider {
                val out = mutableListOf<String>()
                for (n in scopeNames) {
                    val conf = project.configurations.findByName(n) ?: continue
                    for (dep in conf.dependencies) {
                        if (dep is org.gradle.api.artifacts.FileCollectionDependency) {
                            // Resolve file names without triggering
                            // the configuration's full resolution —
                            // `files` is a FileCollection that
                            // enumerates raw files only.
                            for (f in dep.files) {
                                out.add("$n|${f.name}")
                            }
                        }
                    }
                }
                out
            }
        )
    }

    /**
     * Returns `true` when the consuming project already declares the Bugsee
     * core SDK on **any** configuration (`implementation`, `api`,
     * `compileOnly`, variant- or flavor-specific configs, …) — either as a
     * Maven coordinate (`com.bugsee:bugsee-android`) or as a project
     * dependency on the SDK's `:library` / `:stub` modules during local
     * development.
     *
     * Used to suppress the core-SDK auto-add when the user has pinned
     * their own version. Scanning across all configurations matters because
     * a dep declared on `api` is what feeds `implementation`'s resolved
     * graph, and adding a duplicate dynamic-version dep on top would let
     * Gradle's conflict resolver silently override the user's pin.
     */
    private fun isCoreSdkPresent(project: Project): Boolean {
        return project.configurations.any { config ->
            config.dependencies.any { dep ->
                isCoreSdkExternal(dep) || isCoreSdkProjectDep(dep)
            }
        }
    }

    private fun isCoreSdkExternal(dep: Dependency): Boolean {
        return dep.group == BUGSEE_GROUP && dep.name == "bugsee-android"
    }

    /**
     * Project-dep match for the SDK's `:library` / `:stub` modules.
     * Requires the dependency project to declare `GROUP=com.bugsee` (set
     * via the SDK repo's `gradle.properties`) so that an unrelated consumer
     * submodule named `library` does not false-match.
     */
    private fun isCoreSdkProjectDep(dep: Dependency): Boolean {
        if (dep !is ProjectDependency) return false
        val depProject = dep.dependencyProject
        if (depProject.findProperty("GROUP") != BUGSEE_GROUP) return false
        return depProject.name == "library" || depProject.name == "stub"
    }

    /**
     * Adds a Bugsee extension module dependency if not already present.
     * Checks both Maven coordinates (`com.bugsee:{artifactName}`) and
     * project dependencies (`:${projectName}`) to avoid duplicates. Scans
     * all configurations on the consuming project, not just `implementation`.
     */
    private fun autoAddModule(
        project: Project,
        deps: DependencySet,
        artifactName: String,
        projectName: String,
        isDebug: Boolean
    ) {
        val alreadyPresent = project.configurations.any { config ->
            config.dependencies.any { dep ->
                (dep.group == BUGSEE_GROUP && dep.name == artifactName) ||
                    (dep is ProjectDependency &&
                        dep.dependencyProject.findProperty("GROUP") == BUGSEE_GROUP &&
                        dep.dependencyProject.name == projectName)
            }
        }
        if (!alreadyPresent) {
            if (isDebug) project.logger.warn("Bugsee: Auto-adding $artifactName runtime dependency")
            deps.add(project.dependencies.create("com.bugsee:$artifactName:$PLUGIN_VERSION"))
        }
    }

    companion object {
        private const val PLUGIN_NAME = "bugsee"
        // Maven group of all Bugsee artifacts. Centralised so dependency
        // detection and auto-add logic agree.
        private const val BUGSEE_GROUP = "com.bugsee"
        // Shared-services key for the per-build timing collector.
        // Referenced by both the plugin's apply() registration and
        // the per-variant task wiring so both land on the same
        // service instance per build.
        internal const val BUILD_TIMING_SERVICE_NAME = "bugseeBuildTimingService"
        internal val PLUGIN_VERSION: String by lazy {
            BugseePlugin::class.java.getResourceAsStream("/bugsee-plugin-version.txt")
                ?.bufferedReader()?.readText()?.trim()
                ?: "4.99.118-SNAPSHOT"
        }

        // Minimum compatible Bugsee Android SDK version. Used when the plugin
        // auto-adds the core SDK to a consuming app that hasn't declared it.
        // Read from a resource at the same time as PLUGIN_VERSION so a single
        // plugin release pins both numbers via files in the plugin source.
        // Hard-fails on missing resource: a stale fallback would silently
        // let the plugin pull in an old SDK that lacks APIs the plugin
        // assumes after a future bump, and the resource is always packaged
        // by `processResources` — its absence indicates a broken build.
        internal val MIN_SDK_VERSION: String by lazy {
            BugseePlugin::class.java.getResourceAsStream("/bugsee-sdk-min-version.txt")
                ?.bufferedReader()?.readText()?.trim()
                ?: error(
                    "bugsee-sdk-min-version.txt missing from plugin classpath — " +
                        "broken plugin JAR or partial install."
                )
        }

        /**
         * Names of Gradle subprojects that are themselves Bugsee modules.
         * The plugin's auto-add behavior is suppressed when applied to any
         * of these so the plugin does not try to add e.g. `bugsee-okhttp`
         * as a dependency of `:library` (which is the project that
         * `bugsee-okhttp` itself depends on).
         *
         * The list mirrors the project names declared in the SDK's
         * `settings.gradle.kts`. Adding a new Bugsee subproject in the
         * future requires extending this set.
         */
        private val BUGSEE_INTERNAL_MODULE_NAMES = setOf(
            "library",
            "stub",
            "ndk",
            "ai",
            "compose",
            "feedback",
            "remoting",
            "okhttp",
            "ktor-2",
            "ktor-3",
            "cronet"
        )
    }
}
