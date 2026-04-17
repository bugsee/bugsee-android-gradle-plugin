package com.bugsee.android.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.bugsee.android.gradle.instrumentation.InstrumentationConfigResolver
import com.bugsee.android.gradle.instrumentation.InstrumentationRegistrar
import com.bugsee.android.gradle.manifest.BugseeManifestTask
import com.bugsee.android.gradle.upload.BundleUploadTask
import com.bugsee.android.gradle.upload.MappingUploadTask
import com.bugsee.android.gradle.upload.NativeUploadTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

class BugseePlugin : Plugin<Project>, KotlinCompilerPluginSupportPlugin {

    private var pluginExtension: BugseePluginExtension? = null

    override fun apply(project: Project) {
        val extension = project.extensions.create(PLUGIN_NAME, BugseePluginExtension::class.java)
        pluginExtension = extension

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

            // --- Manifest UUID injection ---
            registerManifestTask(project, variant, extension, capitalizedVariant)

            // --- Application variant specific tasks (upload mapping, NDK symbols, bundle) ---
            if (variant is ApplicationVariant) {
                registerMappingUploadTask(project, variant, extension, capitalizedVariant)

                if (extension.ndk.get()) {
                    registerNativeUploadTask(project, variant, extension, capitalizedVariant)
                }

                if (extension.sizeAnalysis.enabled.getOrElse(false)) {
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
                    val registrar = InstrumentationRegistrar(project, project.logger, isDebug, configResolver)
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
    ) {
        val taskProvider = project.tasks.register(
            "createBugsee${capitalizedVariant}ManifestConfig",
            BugseeManifestTask::class.java
        ) { task ->
            task.debug.set(extension.debug)
            task.group = "bugsee"
            task.description = "Injects Bugsee BUILD_UUID into the merged manifest for $capitalizedVariant"
        }

        // Wire into the manifest artifact transformation pipeline
        variant.artifacts.use(taskProvider)
            .wiredWithFiles(
                BugseeManifestTask::mergedManifest,
                BugseeManifestTask::updatedManifest
            )
            .toTransform(SingleArtifact.MERGED_MANIFEST)
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
            task.forceUpload.set(extension.ndkForceUpload)
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
        val buildConfig = extension.sizeAnalysis.buildConfiguration
            .orElse(project.provider { variant.name })

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
     * Adds a Bugsee extension module dependency if not already present.
     * Checks both Maven coordinates (`com.bugsee:{artifactName}`) and
     * project dependencies (`:${projectName}`) to avoid duplicates.
     */
    private fun autoAddModule(
        project: Project,
        deps: org.gradle.api.artifacts.DependencySet,
        artifactName: String,
        projectName: String,
        isDebug: Boolean
    ) {
        val alreadyPresent = deps.any { dep ->
            (dep.group == "com.bugsee" && dep.name == artifactName) ||
                (dep is org.gradle.api.artifacts.ProjectDependency &&
                    dep.dependencyProject.name == projectName)
        }
        if (!alreadyPresent) {
            if (isDebug) project.logger.warn("Bugsee: Auto-adding $artifactName runtime dependency")
            deps.add(project.dependencies.create("com.bugsee:$artifactName:$PLUGIN_VERSION"))
        }
    }

    companion object {
        private const val PLUGIN_NAME = "bugsee"
        private val PLUGIN_VERSION: String by lazy {
            BugseePlugin::class.java.getResourceAsStream("/bugsee-plugin-version.txt")
                ?.bufferedReader()?.readText()?.trim()
                ?: "4.99.118-SNAPSHOT"
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
            "compose",
            "feedback",
            "remoting",
            "okhttp",
            "ktor-2",
            "ktor-3",
            "cronet",
            "interoperation"
        )
    }
}
