package com.bugsee.android.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.bugsee.android.gradle.config.BUGSEE_PROPERTIES_FILENAME
import com.bugsee.android.gradle.config.PluginPropertiesApplier
import com.bugsee.android.gradle.config.SettingDef
import com.bugsee.android.gradle.config.SettingDefinitions
import com.bugsee.android.gradle.config.SettingsFileParser
import com.bugsee.android.gradle.config.SettingsResolver
import com.bugsee.android.gradle.config.VariantContext
import com.bugsee.android.gradle.upload.DependencyCollector
import com.bugsee.android.gradle.instrumentation.InstrumentationConfigResolver
import com.bugsee.android.gradle.instrumentation.ProjectDependencyCompat
import com.bugsee.android.gradle.instrumentation.warnIfProjectDependenciesNotInspected
import com.bugsee.android.gradle.instrumentation.InstrumentationRegistrar
import com.bugsee.android.gradle.instrumentation.Instrumentation
import com.bugsee.android.gradle.instrumentation.extensions_init.ExtensionsInitInstrumentation
import com.bugsee.android.gradle.instrumentation.BugseeSdkVersion
import com.bugsee.android.gradle.instrumentation.SdkVersionAlignment
import com.bugsee.android.gradle.instrumentation.DependencyDetector
import com.bugsee.android.gradle.instrumentation.BugseeSdkDeclarations
import com.bugsee.android.gradle.manifest.ExtensionStripGate
import com.bugsee.android.gradle.manifest.BugseeAssetInjectionTask
import com.bugsee.android.gradle.manifest.BugseeBuildIdResolveTask
import com.bugsee.android.gradle.manifest.BugseeManifestTask
import com.bugsee.android.gradle.manifest.FlutterAssetsOrdering
import com.bugsee.android.gradle.upload.AppTokenResolver
import com.bugsee.android.gradle.util.MinimumVersions
import com.bugsee.android.gradle.upload.BuildTimingService
import com.bugsee.android.gradle.upload.BundleUploadTask
import com.bugsee.android.gradle.upload.MappingUploadTask
import com.bugsee.android.gradle.upload.NativeUploadTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.build.event.BuildEventsListenerRegistry
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File
import javax.inject.Inject

abstract class BugseePlugin : Plugin<Project>, KotlinCompilerPluginSupportPlugin {

    private var pluginExtension: BugseePluginExtension? = null

    // The project this plugin instance was applied to. Needed because
    // KotlinCompilerPluginSupportPlugin.getPluginArtifact() takes no arguments, yet the artifact we
    // resolve depends on the CONSUMER's Kotlin version — see ComposeKotlinCompatibility. A plugin
    // instance is created per project, so this reference is unambiguous.
    private var appliedProject: Project? = null

    // Per-variant settings view (DSL `variant { }` blocks + `variant.*` keys in
    // bugsee.properties). Built on first use — from `afterEvaluate`, `onVariants`,
    // `withDependencies` or the Kotlin compiler-plugin gates, i.e. after the user's
    // `bugsee { }` block has been evaluated, as SettingsResolver requires.
    private var settingsResolver: SettingsResolver? = null

    // Set once the Android plugin has been seen and the variant wiring registered.
    private var androidWired = false

    // True once the build script body (so the user's `bugsee { }` block) has been evaluated,
    // i.e. from the first `afterEvaluate` on. Until then a `withDependencies` action fired by an
    // early reader of `implementation` decides on a possibly non-final DSL.
    private var dslFinal = false

    // The auto-add made while the DSL was not yet final, re-checked in afterEvaluate.
    private var autoAddRecord: AutoAddRecord? = null

    /** Test seam: receives the warning logged when an early-read auto-add cannot be corrected. */
    internal var uncorrectableAutoAddHook: ((String) -> Unit)? = null

    private class AutoAddRecord(val config: Configuration, val result: AutoAddResult)

    /** The DSL-derived gates the auto-add decision depends on, plus the dependencies it produced. */
    private class AutoAddResult(
        val inputs: AutoAddInputs,
        val dependencies: List<Dependency>,
        // Debug "Auto-adding ..." lines for [dependencies]; logged only once the decision is final.
        val notes: List<String>,
    )

    private data class AutoAddInputs(
        val sdkAutoLoad: Boolean,
        val ndk: Boolean,
        val leak: Boolean,
        val feedback: Boolean,
        val compose: Boolean,
        val okhttp: Boolean,
        val ktor: Boolean,
        val cronet: Boolean,
    )

    // Injected by Gradle at plugin apply time — the service registry
    // we need to hook the BuildTimingService into the task-completion
    // event stream. Gradle provides this via abstract @Inject on the
    // plugin class.
    @get:Inject
    abstract val listenerRegistry: BuildEventsListenerRegistry

    override fun apply(project: Project) {
        // First thing: a readable failure on an unsupported AGP/Gradle (issue #60), before any
        // other Gradle/AGP API is touched. AGP not yet applied: the AGP half is skipped.
        MinimumVersions.checkProject(project)

        val extension = project.extensions.create(PLUGIN_NAME, BugseePluginExtension::class.java)
        pluginExtension = extension
        appliedProject = project

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

                    // Decided before variants exist. If the first read of `implementation`
                    // happens before the user's `bugsee { }` block (another plugin reading it
                    // at apply time), the DSL is not final: the decision is made with what is
                    // known, NOT memoized, and re-checked in afterEvaluate (see
                    // [reconcileAutoAdd]).
                    val added = computeAutoAdds(
                        project,
                        if (dslFinal) settingsResolver() else freshSettingsResolver(),
                    )
                    deps.addAll(added.dependencies)
                    // A provisional pass is not announced; [reconcileAutoAdd] logs the final one.
                    if (dslFinal) added.notes.forEach { project.logger.warn(it) } else autoAddRecord = AutoAddRecord(config, added)
                }
            }
        }

        project.afterEvaluate {
            // Fallback: normally the DSL was already declared final by `finalizeDsl` below.
            onDslFinal(project)
            val isDebug = settingsResolver().globalValue(DEBUG)
            if (isDebug) project.logger.warn("Bugsee: Plugin applied")

            if (project.extensions.findByName("android") == null) {
                throw IllegalStateException(
                    "Bugsee requires 'com.android.application' or 'com.android.library' in project ${project.path}"
                )
            }
            // e.g. com.android.test / dynamic-feature: the Android plugin is there but nothing is wired.
            if (!androidWired && isDebug) {
                project.logger.warn(
                    "Bugsee: only application modules are configured; nothing wired for ${project.path}"
                )
            }
        }

        // Wire the variants as soon as the Android plugin is applied, whichever order the build
        // script lists the plugins in (`withPlugin` runs immediately when it is already applied).
        // Looking the extension up here instead would find nothing when Bugsee is applied first,
        // and silently skip every task.
        for (androidPluginId in ANDROID_PLUGIN_IDS) {
            project.pluginManager.withPlugin(androidPluginId) {
                if (androidWired) return@withPlugin // defensive: run once
                androidWired = true
                wireAndroidComponents(project)
            }
        }
    }

    /** Internal so unit tests can drive the wiring without applying the real Android plugin. */
    internal fun wireAndroidComponents(project: Project) {
        val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
        if (androidComponents == null) {
            project.logger.warn("Bugsee: AndroidComponentsExtension not found; skipping variant wiring.")
            return
        }

        // After AGP has created the variants, flag per-variant names that matched nothing in ANY
        // application module (bugsee.properties is shared by the whole build, so a name used by one
        // app module is not a typo). Library modules produce no application variants and are skipped.
        if (androidComponents is com.android.build.api.variant.ApplicationAndroidComponentsExtension) {
            // Declared flavors and build types count even if `beforeVariants` disables every variant;
            // recorded in afterEvaluate, which precedes every module's projectsEvaluated check.
            project.afterEvaluate { recordDeclaredNames(project) }
            project.gradle.projectsEvaluated {
                settingsResolver().warnUnmatchedNames(knownVariantNames(project), project.path)
            }
        }

        // One default-level warning per library module, emitted here rather than per variant (a library
        // has debug + release) and independent of `onVariants`, so it also fires when `beforeVariants`
        // disables every variant. The plugin skips library variants below, so without it the misapplied
        // plugin is silent unless `debug=true`.
        // Bugsee's own SDK modules are libraries on purpose and are exempt. Identified by group only, never
        // by bare module name: `:library` / `:compose` / `:okhttp` are common names in customer builds.
        // Evaluated in afterEvaluate because `group` is often assigned after the plugin is applied.
        if (androidComponents is com.android.build.api.variant.LibraryAndroidComponentsExtension) {
            project.afterEvaluate {
                if (!isBugseeSdkGroup(project.group?.toString().orEmpty())) {
                    project.logger.warn(
                        libraryModuleMessage(project.path, project.path == ":", project.name)
                    )
                }
            }
        }

        // The first AGP hook after the build script body and before ANY `onVariants` callback (which
        // reads the declared dependencies to gate instrumentation) — the earliest point at which
        // the DSL is final and late enough that the auto-add can still be corrected.
        androidComponents.finalizeDsl { onDslFinal(project) }

        androidComponents.onVariants { variant ->
            val globalDebug = settingsResolver().globalValue(DEBUG)
            val variantName = variant.name
            val capitalizedVariant = variantName.replaceFirstChar { it.uppercase() }

            if (globalDebug) project.logger.warn("Bugsee: Configuring variant $variantName")

            // Gate every plugin-side wiring on `variant is
            // ApplicationVariant`. The bugsee plugin's contract is
            // application-scoped — crash + mapping + symbol upload
            // is meaningful per APK/AAB build, not per library AAR.
            //
            // Critically, this also prevents a quiet correctness bug
            // on library variants: the manifest task strips Bugsee
            // extension `<provider>` entries (under the default
            // `optimizeExtensionsLoading=true`) and writes their FQNs
            // to a sidecar file consumed by the **bytecode** visitor
            // that inlines `register<Name>Extension()` calls into the
            // SDK's `BugseeInitProvider.initializeExtensions()`. That
            // bytecode rewrite is itself application-scoped (it
            // modifies `BugseeInitProvider`, which lives in the
            // consumer app, not in any library). So if the manifest
            // task ran on a library variant, it would strip the
            // provider AND there'd be no compensating bytecode in the
            // AAR — the resulting library, consumed by an app, would
            // ship a `<provider>` stripped from the merged manifest
            // and with no auto-registration replacement, breaking the
            // extension at runtime. The narrow fix is to scope every
            // plugin side-effect to `ApplicationVariant`, which is
            // also what the README has always documented.
            if (variant !is ApplicationVariant) {
                if (globalDebug) {
                    project.logger.warn(
                        "Bugsee: skipping plugin wiring for non-application variant " +
                            "$variantName (${variant.javaClass.simpleName}). " +
                            "The bugsee plugin only configures application modules; " +
                            "see the README, \"Library modules\"."
                    )
                }
                return@onVariants
            }

            // This variant's resolved settings. Every variant-level read below goes
            // through `effective`; it does not carry app tokens (see resolveCcSafeInputs).
            val variantContext = variantContextOf(variant)
            recordKnownVariantNames(project, variantContext)
            val effective = settingsResolver().effectiveFor(variantContext)
            val isDebug = effective.debug.get()
            if (isDebug) {
                val token = settingsResolver().appTokenFor(variantContext, debug = false)
                project.logger.warn(variantResolvedMessage(variantName, token, effective.endpoint.get()))
            }

            // Resolved EARLY (before the manifest task) because the manifest task's
            // extension-provider stripping must key on the SAME global instrumentation
            // gate as the compensating ExtensionsInit bytecode injection registered
            // further down. Stripping while instrumentation is globally disabled
            // (e.g. `-Pbugsee.instrumentation.enabled=false`) would leave every
            // Bugsee extension with NEITHER a manifest `<provider>` NOR an inlined
            // `register<Name>Extension()` call — silently dead at runtime.
            val sourceManifest = project.file("src/main/AndroidManifest.xml")
            val configResolver = InstrumentationConfigResolver(
                effective.instrumentation,
                project,
                sourceManifest.takeIf { it.exists() }
            )
            val instrumentationGloballyEnabled = configResolver.isGloballyEnabled()

            // Resolved here for the same reason as the flag above: the manifest task's
            // provider-stripping must key on every precondition the compensating
            // ExtensionsInit injection has, and that injection rewrites a method
            // (`initializeExtensions`) which only exists from a certain SDK release.
            // Same resolver as the auto-add, so gate and auto-added version cannot disagree.
            // Known limits, shared with the auto-add and tracked in #114: the app's own
            // configurations are read unscoped (a test-only newer pin can win), and a pin
            // only on a dependency project's debug* edge is not seen (null => strip).
            val declaredSdkVersionRaw = BugseeSdkDeclarations.declaredCoreSdkVersion(project)
            val declaredSdkVersion = BugseeSdkVersion.parse(declaredSdkVersionRaw)
            if (ExtensionStripGate.shouldWarnBelowFloor(
                    optimizeExtensionsLoading = effective.optimizeExtensionsLoading.get(),
                    instrumentationGloballyEnabled = instrumentationGloballyEnabled,
                    excludes = effective.instrumentation.excludes.getOrElse(emptySet()),
                    sdkVersion = declaredSdkVersion,
                ) &&
                BugseeBuildState.claim(project, "extensions-init-sdk-below-floor:${project.buildTreePath}")
            ) {
                project.logger.warn(ExtensionStripGate.belowFloorWarning(declaredSdkVersionRaw))
            }

            // Resolution-time check: fires when the runtime classpath resolves (never forces it
            // here) and warns when the resolved com.bugsee:* modules are on different versions.
            SdkVersionAlignment.watch(project.logger, "$variantName (module ${project.path})", variant.runtimeConfiguration)


            // --- Manifest UUID injection + extension provider stripping ---
            // Pre-R8 manifest transform writes a deterministic *fallback*
            // BUILD_UUID into the manifest meta-data.
            val manifestTaskProvider = registerManifestTask(
                project, variant, effective, capitalizedVariant,
                instrumentationGloballyEnabled = instrumentationGloballyEnabled,
                declaredSdkVersion = declaredSdkVersion,
            )

            // --- BUILD_UUID resolve + asset injection (post-R8) ---
            // Post-R8 task hashes mapping.txt content for the real
            // BUILD_UUID; asset-injection transform writes it into
            // `assets/bugsee_build_id.properties` for the SDK to read at
            // runtime. When R8 is off, both tasks observe the absent
            // mapping and fall back to the same UUID the manifest task
            // already wrote — asset and manifest stay in sync.
            val resolveTaskProvider = registerBuildIdResolveAndAssetTasks(
                project, variant, manifestTaskProvider, capitalizedVariant
            )

            // --- Application variant specific tasks (upload mapping, NDK symbols, bundle) ---
            registerMappingUploadTask(
                project, variant, effective, capitalizedVariant, resolveTaskProvider
            )

            if (shouldRegisterNativeUploadFor(project, variant, effective, isDebug)) {
                registerNativeUploadTask(
                    project, variant, effective, capitalizedVariant, resolveTaskProvider
                )
            }

            // Build-info registration runs by default for every
            // matching variant. Size analysis is a sub-feature
            // that piggybacks on the same task — when both are
            // active, the task additionally requests a presigned
            // PUT URL and ships the artefact bytes.
            if (shouldRegisterBuildInfoFor(project, variant, effective, isDebug)) {
                registerBundleUploadTask(
                    project, variant, effective, capitalizedVariant, resolveTaskProvider
                )
            }

            // --- Bytecode instrumentation (application modules only) ---
            // (configResolver / instrumentationGloballyEnabled resolved above,
            // before the manifest task, so provider-stripping and the
            // ExtensionsInit injection share one gate.)
            if (instrumentationGloballyEnabled) {
                val extensionsInit = ExtensionsInitInstrumentation(effective, manifestTaskProvider)
                val extras = listOf<Instrumentation>(extensionsInit)
                // Core-only instrumentations must also apply when the plugin
                // is going to auto-add the core SDK: at this point that
                // dependency is still a pending `withDependencies` addition and
                // is therefore invisible to DependencyDetector's declared-deps scan.
                val sdkAutoLoadOn = settingsResolver().globalValue(SDK_AUTO_LOAD)
                val coreSdkAutoLoad = sdkAutoLoadOn && !isCoreSdkPresent(project)
                // With auto-load off nothing else supplies the SDK: if the module's own
                // dependencies do not declare it, SDK-dependent instrumentation is skipped.
                // Same predicate as the instrumentation gates (prefix match, unscoped); the mode
                // is checked first so nothing extra runs when Isolated Projects is off.
                if (!sdkAutoLoadOn && ProjectAccess.isIsolatedProjects(project) &&
                    !DependencyDetector.hasBugseeDependency(project, "bugsee-android")
                ) {
                    warnIfProjectDependenciesNotInspected(
                        project, "the core Bugsee SDK was not found (and sdkAutoLoad is off, so it is not added)"
                    )
                }
                val registrar = InstrumentationRegistrar(
                    project, project.logger, isDebug, configResolver,
                    effective.instrumentation.excludes.getOrElse(emptySet()),
                    extras,
                    coreSdkAutoLoad,
                )
                val applied = registrar.applyAll(variant)
                // The strip must follow what was actually registered, not re-derive it:
                // the lane's own gates (SDK detection per variant, feature keys) can say
                // no while every strip precondition says yes.
                val extensionsInitRegistered = extensionsInit.key in applied
                manifestTaskProvider.configure { it.extensionsInitRegistered.set(extensionsInitRegistered) }
            } else {
                if (isDebug) project.logger.warn("Bugsee: Bytecode instrumentation is globally disabled")
            }
        }
    }

    private fun readAutoAddInputs(project: Project, resolver: SettingsResolver) = AutoAddInputs(
        sdkAutoLoad = resolver.globalValue(SDK_AUTO_LOAD),
        ndk = resolver.anyScopeValue(NDK_ENABLED) ?: false,
        leak = resolver.globalValue(LEAK_ENABLED),
        feedback = resolver.globalValue(FEEDBACK),
        // Compose AAR carries three independent capabilities (tag injection, secure-modifier
        // auto-detection, input capture). Auto-add the AAR if ANY of them is enabled — disabling
        // `compose` (the umbrella) while keeping `composeInput` or `composeSecure` should still
        // pull in the runtime that hosts those features.
        compose = isAutoAddEnabled(project, resolver, INST_COMPOSE, "compose") ||
                isAutoAddEnabled(project, resolver, INST_COMPOSE_SECURE, "composeSecure") ||
                isAutoAddEnabled(project, resolver, INST_COMPOSE_INPUT, "composeInput"),
        okhttp = isAutoAddEnabled(project, resolver, INST_OKHTTP, "okhttp"),
        ktor = isAutoAddEnabled(project, resolver, INST_KTOR, "ktor"),
        cronet = isAutoAddEnabled(project, resolver, INST_CRONET, "cronet"),
    )

    /**
     * The auto-add decision: which Bugsee modules to add to `implementation`, from [resolver]
     * and the declared dependencies. Nothing is added to the configuration here, so it can be
     * re-run by [reconcileAutoAdd].
     */
    private fun computeAutoAdds(
        project: Project,
        resolver: SettingsResolver,
        ignore: Set<Dependency> = emptySet(),
    ): AutoAddResult {
        val out = ArrayList<Dependency>()
        val notes = ArrayList<String>()
        // Decided before variants exist: global-scope values (so preferSettingsFile applies), or
        // any scope for the module auto-adds (see isAutoAddEnabled).
        val isDebug = resolver.globalValueOrNull(DEBUG) ?: false
        val inputs = readAutoAddInputs(project, resolver)

        // `sdkAutoLoad=false` opts out of ALL auto-add (core SDK and every extension module): the
        // user declares every Bugsee module themselves. Resolved with the global-scope rule, so the
        // DSL and bugsee.properties (incl. preferSettingsFile) behave identically.
        if (!inputs.sdkAutoLoad) {
            inertOptInModules(project, resolver, ignore).forEach { (setting, artifact) ->
                notes.add(sdkAutoLoadOffWarning(setting, artifact))
            }
            if (isDebug) notes.add("Bugsee: sdkAutoLoad=false - skipping all dependency auto-add")
            return AutoAddResult(inputs, out, notes)
        }

        // Version shared by EVERY auto-added Bugsee module — core AND extensions. Prefer the
        // version the consumer already declared for the core SDK; otherwise the dynamic range
        // floored at MIN_SDK_VERSION. Extensions are published under the SDK's version line, so
        // they must track the SDK version (never the plugin version — see autoAddModule).
        val inDeps = lazy { BugseeSdkDeclarations.inDependencyProjects(project) }
        val sdkVersion = declaredCoreSdkVersion(project, ignore, inDeps)
            ?: CoreSdkAutoLoad.range(MIN_SDK_VERSION)

        // Auto-add the core Bugsee SDK if the consuming app has not declared one. If the user has
        // declared their own version on any configuration (`api`, `compileOnly`, variant configs,
        // …), defer to it rather than layering a dynamic dep on top. Opt-out via
        // `bugsee { sdkAutoLoad.set(false) }`.
        if (!isCoreSdkPresent(project, ignore, inDeps)) {
            if (isDebug) notes.add("Bugsee: Auto-adding bugsee-android runtime dependency $sdkVersion")
            out.add(project.dependencies.create("com.bugsee:bugsee-android:$sdkVersion"))
        }

        // Optional SDK modules driven by user opt-in via `bugsee { ndk { enabled.set(true) };
        // feedback(true) }`. The NDK runtime is added when NDK is enabled globally or in any
        // variant scope; the per-variant symbol upload task stays exact.
        if (inputs.ndk) autoAddModule(project, out, "bugsee-android-ndk", "ndk", isDebug, sdkVersion, ignore, notes)
        if (inputs.leak) autoAddModule(project, out, "bugsee-android-leak", "leak", isDebug, sdkVersion, ignore, notes)
        if (inputs.feedback) autoAddModule(project, out, "bugsee-android-feedback", "feedback", isDebug, sdkVersion, ignore, notes)

        if (hasComposeDependency(project) && inputs.compose) {
            autoAddModule(project, out, "bugsee-android-compose", "compose", isDebug, sdkVersion, ignore, notes)
        }
        if (hasOkHttpDependency(project) && inputs.okhttp) {
            autoAddModule(project, out, "bugsee-android-okhttp", "okhttp", isDebug, sdkVersion, ignore, notes)
        }
        if (hasKtorDependency(project, 2) && inputs.ktor) {
            autoAddModule(project, out, "bugsee-android-ktor-2", "ktor-2", isDebug, sdkVersion, ignore, notes)
        }
        if (hasKtorDependency(project, 3) && inputs.ktor) {
            autoAddModule(project, out, "bugsee-android-ktor-3", "ktor-3", isDebug, sdkVersion, ignore, notes)
        }
        if (hasCronetDependency(project) && inputs.cronet) {
            autoAddModule(project, out, "bugsee-android-cronet", "cronet", isDebug, sdkVersion, ignore, notes)
        }
        return AutoAddResult(inputs, out, notes)
    }

    /**
     * Called once the DSL is final: from `androidComponents.finalizeDsl` (the first hook after the
     * build script body and before any `onVariants` callback reads the declared dependencies), or
     * from the plugin's own `afterEvaluate` as a fallback when AGP is absent.
     *
     * Values set later than that (a user `afterEvaluate { }` registered after ours, or a task) are
     * not seen; neither is a read of `implementation` made inside another plugin's `afterEvaluate`
     * that runs before this point, which is then treated as an early read and corrected here.
     */
    private fun onDslFinal(project: Project) {
        if (dslFinal) return
        dslFinal = true
        // Drop any resolver memoized before the DSL was final so every later reader sees it.
        settingsResolver = null
        reconcileAutoAdd(project)
    }

    /**
     * Re-checks an auto-add that was made before the DSL was final (an early reader fired the
     * `withDependencies` action before the `bugsee { }` block, and before the build script's own
     * `dependencies { }` block, ran). Gradle runs that action on the FIRST read of the dependency
     * set and never again, so the only way to honour the final state is to correct the
     * configuration afterwards.
     *
     * The corrected set is computed FIRST, without touching the configuration (our own earlier
     * additions are excluded from the "already declared" scans by identity). The configuration is
     * mutated only when the coordinates differ, and then only by the difference, so an unchanged
     * decision causes no remove/add churn. If Gradle refuses the change (a configuration extending
     * `implementation` was already resolved), nothing is left half-applied and a loud warning
     * is logged instead.
     *
     * Known ambiguity: a [org.gradle.api.artifacts.DependencySet] de-duplicates by content, so if
     * the user declares exactly the auto-add range for `bugsee-android` after the early read, that
     * declaration is swallowed as a duplicate of ours and removed with it when `sdkAutoLoad` is
     * off. Pin a different version or declare it before the early read.
     */
    private fun reconcileAutoAdd(project: Project) {
        val record = autoAddRecord ?: return
        autoAddRecord = null
        val config = record.config
        val early = record.result

        val corrected = computeAutoAdds(
            project, settingsResolver(), ignore = early.dependencies.toSet(),
        )
        val earlyCoords = coordinatesOf(early.dependencies)
        val correctedCoords = coordinatesOf(corrected.dependencies)
        if (earlyCoords == correctedCoords) {
            corrected.notes.forEach { project.logger.warn(it) }
            return
        }

        val toRemove = early.dependencies.filter { coordinateOf(it) !in correctedCoords }
        val toAdd = corrected.dependencies.filter { coordinateOf(it) !in earlyCoords }
        val why = differingInputs(early.inputs, corrected.inputs).ifEmpty { "declared dependencies changed" }
        var removed = false
        try {
            if (toRemove.isNotEmpty()) {
                config.dependencies.removeAll(toRemove.toSet())
                removed = true
            }
            config.dependencies.addAll(toAdd)
            corrected.notes.forEach { project.logger.warn(it) }
            project.logger.info(
                "Bugsee: '${config.name}' was read before the bugsee { } block was evaluated; " +
                    "auto-added modules re-applied for the final configuration ($why)" +
                    (if (toRemove.isNotEmpty()) "; removed ${toRemove.map { coordinateOf(it) }}" else "") + "."
            )
            return
        } catch (e: Exception) {
            if (removed) runCatching { config.dependencies.addAll(toRemove) }
            project.logger.debug("Bugsee: could not correct '${config.name}'", e)
        }
        val message = (
            "Bugsee: '${config.name}' was read before the bugsee { } block was evaluated, and a " +
                "configuration extending it has already been resolved, so the auto-added Bugsee " +
                "modules were decided on defaults and could not be corrected ($why). " +
                "Declare the Bugsee dependencies explicitly, or apply the Bugsee plugin and its " +
                "bugsee { } block before whatever reads the configuration."
        )
        project.logger.warn(message)
        uncorrectableAutoAddHook?.invoke(message)
    }

    private fun coordinateOf(dep: Dependency): String = "${dep.group}:${dep.name}:${dep.version}"

    private fun coordinatesOf(deps: List<Dependency>): Set<String> = deps.map { coordinateOf(it) }.toSet()

    private fun differingInputs(a: AutoAddInputs, b: AutoAddInputs): String = buildList {
        if (a.sdkAutoLoad != b.sdkAutoLoad) add("sdkAutoLoad ${a.sdkAutoLoad} -> ${b.sdkAutoLoad}")
        if (a.ndk != b.ndk) add("ndk.enabled ${a.ndk} -> ${b.ndk}")
        if (a.leak != b.leak) add("leak.enabled ${a.leak} -> ${b.leak}")
        if (a.feedback != b.feedback) add("feedback ${a.feedback} -> ${b.feedback}")
        if (a.compose != b.compose) add("instrumentation.compose* ${a.compose} -> ${b.compose}")
        if (a.okhttp != b.okhttp) add("instrumentation.okhttp ${a.okhttp} -> ${b.okhttp}")
        if (a.ktor != b.ktor) add("instrumentation.ktor ${a.ktor} -> ${b.ktor}")
        if (a.cronet != b.cronet) add("instrumentation.cronet ${a.cronet} -> ${b.cronet}")
    }.joinToString(", ")

    /**
     * The per-variant settings resolver, created on first call. Callers (`afterEvaluate`,
     * `onVariants`, `withDependencies`, the Compose compiler-plugin gates) run after the
     * user's `bugsee { }` block: the resolver probes which DSL properties were explicitly
     * set, so it must not be built at apply time. The file is read through `providers.fileContents` (a configuration-cache
     * input), as [PluginPropertiesApplier.applyTo] does.
     */
    private fun settingsResolver(): SettingsResolver =
        settingsResolver ?: freshSettingsResolver().also { settingsResolver = it }

    /** Builds a resolver from the DSL as it is NOW, without memoizing it. */
    private fun freshSettingsResolver(): SettingsResolver = run {
        val project = checkNotNull(appliedProject) { "Bugsee plugin not applied" }
        val extension = checkNotNull(pluginExtension) { "Bugsee plugin not applied" }
        val text = project.providers
            .fileContents(ProjectAccess.rootFile(project, BUGSEE_PROPERTIES_FILENAME))
            .asText.orNull
        // PluginPropertiesApplier has already reported a malformed file; do not repeat it.
        // The file is shared by every module, so its diagnostics are reported by the first project
        // that reads it; later projects parse silently.
        val warnedKey = "bugsee.settingsFileDiagnosticsReported"
        val parseLogger = if (BugseeBuildState.claim(project, warnedKey)) {
            project.logger
        } else {
            discardingLogger()
        }
        val parsed = SettingsFileParser.parse(text, parseLogger, reportMalformedFile = false)
        SettingsResolver(extension, parsed, project.objects, project.logger, project.rootDir)
    }

    private fun registerManifestTask(
        project: Project,
        variant: com.android.build.api.variant.Variant,
        extension: BugseePluginExtension,
        capitalizedVariant: String,
        /**
         * The consumer's declared `com.bugsee:bugsee-android` version, or null when it
         * cannot be parsed. Also an [ExtensionStripGate] precondition: the compensating
         * injection rewrites a method that only exists from a certain SDK release, so
         * stripping against an older SDK would leave extensions with neither a provider
         * nor a registration call.
         */
        declaredSdkVersion: BugseeSdkVersion?,
        /**
         * Whether bytecode instrumentation is globally enabled. Combined with
         * the `optimizeExtensionsLoading` DSL flag and the user's excludes via
         * [ExtensionStripGate]: stripping extension `<provider>`s is only
         * sound when the compensating ExtensionsInit bytecode injection will
         * actually run. See [ExtensionStripGate] for the failure modes.
         */
        instrumentationGloballyEnabled: Boolean,
    ): org.gradle.api.tasks.TaskProvider<BugseeManifestTask> {
        val taskProvider = project.tasks.register(
            "createBugsee${capitalizedVariant}ManifestConfig",
            BugseeManifestTask::class.java
        ) { task ->
            task.debug.set(extension.debug)
            task.optimizeExtensionsLoading.set(
                extension.optimizeExtensionsLoading.map { dslOptimize ->
                    ExtensionStripGate.shouldStrip(
                        optimizeExtensionsLoading = dslOptimize,
                        instrumentationGloballyEnabled = instrumentationGloballyEnabled,
                        excludes = extension.instrumentation.excludes.getOrElse(emptySet()),
                        sdkVersion = declaredSdkVersion,
                    )
                }
            )
            // Variant + plugin version feed the deterministic BUILD_UUID
            // derivation in the task action (see BugseeManifestTask
            // KDoc). Pre-resolved here at registration time so the
            // task action stays CC-safe and the @Input annotations
            // make Gradle's up-to-date check key on the right state.
            task.variantName.set(variant.name)
            task.pluginVersion.set(PLUGIN_VERSION)
            task.detectedExtensions.set(
                project.layout.buildDirectory.file(
                    "intermediates/bugsee/${variant.name}/detected-extensions.txt"
                )
            )
            // Side-file carrying the fallback UUID forward to the
            // post-R8 resolve task. See KDoc on
            // BugseeManifestTask.fallbackBuildId / BugseeBuildIdResolveTask
            // for why this isn't recomputed downstream.
            task.fallbackBuildId.set(
                project.layout.buildDirectory.file(
                    "intermediates/bugsee/${variant.name}/fallback-build-id.txt"
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

    /**
     * Registers the post-R8 BUILD_UUID resolve task and the assets-
     * stage injection task that writes the resolved value into
     * `assets/bugsee_build_id.properties`.
     *
     * The contract:
     *   - The resolve task listens to AGP's `OBFUSCATION_MAPPING_FILE`
     *     artifact. When R8 produces it, AGP sequences the resolve
     *     task to run AFTER. When R8 is off, the listener fires
     *     without a mapping file present — fallback derivation kicks in.
     *   - The asset injection task transforms AGP's `ASSETS` artifact.
     *     It additionally depends on the resolve task's output file,
     *     which transitively pulls the post-R8 ordering through the
     *     assets pipeline.
     *
     * Together: the SDK at runtime reads a UUID that's either
     * mapping-derived (when R8 ran) or manifest-derived (when R8
     * didn't), and the build pipeline produces it without ever
     * trying to mutate the manifest after R8 — which AGP doesn't
     * support and Sentry's plugin specifically routes around.
     */
    private fun registerBuildIdResolveAndAssetTasks(
        project: Project,
        variant: com.android.build.api.variant.Variant,
        manifestTaskProvider: org.gradle.api.tasks.TaskProvider<BugseeManifestTask>,
        capitalizedVariant: String,
    ): org.gradle.api.tasks.TaskProvider<BugseeBuildIdResolveTask> {
        // Resolve task — derives the final UUID from mapping.txt when
        // present, otherwise from the manifest fallback. Writes a
        // single-line text file consumed by the asset task.
        val resolveTaskProvider = project.tasks.register(
            "resolveBugsee${capitalizedVariant}BuildId",
            BugseeBuildIdResolveTask::class.java,
        ) { task ->
            // T1's side file: the fallback UUID it already derived
            // from (manifest + variant + plugin). Re-deriving here
            // wouldn't match — see [BugseeBuildIdResolveTask.fallbackBuildId]
            // KDoc for the AGP-artifact-replacement reason.
            task.fallbackBuildId.set(
                manifestTaskProvider.flatMap { it.fallbackBuildId }
            )
            task.resolvedBuildIdFile.set(
                project.layout.buildDirectory.file(
                    "intermediates/bugsee/${variant.name}/build-id.txt"
                )
            )
            task.group = "bugsee"
            task.description =
                "Resolves the Bugsee BUILD_UUID for $capitalizedVariant from mapping.txt (post-R8)"
        }

        // `toListenTo(OBFUSCATION_MAPPING_FILE)` (AGP 8.3+) places this
        // task downstream of R8 when R8 runs for the variant. AGP
        // wires the artifact into the task's `mappingFile` property
        // and orders the task graph accordingly — no manual
        // `dependsOn(:minifyXxxWithR8)`, which would be fragile across
        // AGP versions and configurations like DexGuard.
        //
        // Only registered for variants that can produce a mapping: with no
        // producer AGP prints "... will never be invoked" on every build
        // (#53), and the task's `mappingFile` is optional, so it simply stays
        // unset and the fallback derivation applies. See [mayProduceMapping].
        if (mayProduceMapping(variant, project.extensions.findByName("android"))) {
            variant.artifacts.use(resolveTaskProvider)
                .wiredWith(BugseeBuildIdResolveTask::mappingFile)
                .toListenTo(SingleArtifact.OBFUSCATION_MAPPING_FILE)
        }

        // Asset injection task — copies AGP's merged assets, drops
        // our build-id file in. The `resolvedBuildIdFile` input creates
        // the transitive ordering: assets pipeline now flows through
        // the resolve task, which itself depends on R8.
        val assetTaskProvider = project.tasks.register(
            "injectBugsee${capitalizedVariant}BuildId",
            BugseeAssetInjectionTask::class.java,
        ) { task ->
            task.resolvedBuildIdFile.set(
                resolveTaskProvider.flatMap { it.resolvedBuildIdFile }
            )
            task.group = "bugsee"
            task.description =
                "Injects Bugsee BUILD_UUID asset for $capitalizedVariant"
        }

        variant.artifacts.use(assetTaskProvider)
            .wiredWithDirectories(
                BugseeAssetInjectionTask::inputAssetsDir,
                BugseeAssetInjectionTask::outputAssetsDir,
            )
            .toTransform(SingleArtifact.ASSETS)

        // Flutter apps: Flutter's copyFlutter*Assets also writes the merged
        // assets dir without AGP's knowledge (issue #33). No-op otherwise.
        FlutterAssetsOrdering.wire(
            project,
            assetTaskProvider,
            capitalizedVariant,
            project.extensions.getByType(BugseePluginExtension::class.java).assetsDependsOn,
        )

        return resolveTaskProvider
    }

    private fun registerMappingUploadTask(
        project: Project,
        variant: ApplicationVariant,
        extension: BugseePluginExtension,
        capitalizedVariant: String,
        resolveTaskProvider: org.gradle.api.tasks.TaskProvider<BugseeBuildIdResolveTask>,
    ) {
        val ccInputs = resolveCcSafeInputs(project, extension, variant)

        val mappingUploadTaskProvider = project.tasks.register(
            "uploadBugsee${capitalizedVariant}Mapping",
            MappingUploadTask::class.java
        ) { task ->
            task.debug.set(extension.debug)
            task.failOnUploadError.set(extension.failOnUploadError)
            task.variantName.set(variant.name)
            task.endpoint.set(extension.endpoint)
            // Uploader strategy + bugsee-cli binary path/version. `cliPath` is
            // optional (lets developers point at a locally-built binary);
            // when unset, the task auto-downloads `cliVersion` from
            // download.bugsee.com into the per-user Gradle cache.
            task.cliPath.set(projectRelativeCliPath(project, extension))
            task.cliVersion.set(extension.cliVersion)
            task.cliAutoUpdate.set(extension.cliAutoUpdate)
            // Gradle user home wired CC-safely as a File (eagerly captured).
            task.gradleUserHomeDir.fileValue(project.gradle.gradleUserHomeDir)
            task.uploader.set(extension.uploader)
            task.mappingListenerRegistered.set(
                mayProduceMapping(variant, project.extensions.findByName("android"))
            )
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

            // Resolved BUILD_UUID — the same UUID the SDK reports at
            // runtime via the asset channel. Wiring the resolve task's
            // output makes the upload-side identity match the
            // runtime-side identity for both R8 and non-R8 builds.
            task.resolvedBuildIdFile.set(
                resolveTaskProvider.flatMap { it.resolvedBuildIdFile }
            )

            // CC-safe inputs — see resolveCcSafeInputs / the task's
            // KDoc for the full rationale.
            ccInputs.preResolvedToken?.let { task.preResolvedAppToken.set(it) }
            if (ccInputs.placeholderSeen) task.placeholderTokenSeen.set(true)
            task.stringResourceFiles.from(ccInputs.stringResFiles)
            task.rootProjectDirectory.fileValue(project.rootDir)
        }

        // Finalize after assemble/bundle and the packaging/signing tasks below them
        finalizeAfter(
            project,
            apkAndBundleEntryTaskNames(capitalizedVariant, isReleaseLikeVariant(project, variant)),
            mappingUploadTaskProvider,
        )
    }

    /**
     * Per-variant decision on whether to register the native-symbol upload task:
     * `ndk.enabled`, and (unless `ndk.allBuildTypes` opts in) only for release-like,
     * i.e. non-debuggable, variants.
     */
    private fun shouldRegisterNativeUploadFor(
        project: Project,
        variant: ApplicationVariant,
        extension: BugseePluginExtension,
        isDebug: Boolean,
    ): Boolean {
        if (!extension.ndk.enabled.get()) return false
        if (!isReleaseLikeVariant(project, variant) && !extension.ndk.allBuildTypes.getOrElse(false)) {
            logNativeSkippedOnce(project, variant)
            if (isDebug) {
                project.logger.warn(
                    "Bugsee: skipping native symbol upload for non-release variant '${variant.name}' — " +
                        "set ndk.allBuildTypes(true) to include it"
                )
            }
            return false
        }
        return true
    }

    /** One lifecycle line per project: a behaviour change that must not pass silently. */
    private fun logNativeSkippedOnce(project: Project, variant: ApplicationVariant) {
        val key = "bugseeNativeSkipWarned"
        val extra = project.extensions.extraProperties
        if (extra.has(key) && extra.get(key) == true) return
        extra.set(key, true)
        project.logger.lifecycle(
            "Bugsee: native symbols are uploaded only for non-debuggable build types " +
                "(e.g. skipped: ${variant.buildType ?: variant.name}; every build type with isDebuggable = true); " +
                "set ndk.allBuildTypes=true to include them"
        )
    }

    private fun registerNativeUploadTask(
        project: Project,
        variant: ApplicationVariant,
        extension: BugseePluginExtension,
        capitalizedVariant: String,
        resolveTaskProvider: org.gradle.api.tasks.TaskProvider<BugseeBuildIdResolveTask>,
    ) {
        val ccInputs = resolveCcSafeInputs(project, extension, variant)

        val nativeUploadTaskProvider = project.tasks.register(
            "uploadBugsee${capitalizedVariant}Native",
            NativeUploadTask::class.java
        ) { task ->
            task.debug.set(extension.debug)
            task.failOnUploadError.set(extension.failOnUploadError)
            task.variantName.set(variant.name)
            task.endpoint.set(extension.endpoint)
            task.forceUpload.set(extension.ndk.forceDebugSymbolsUpload)
            task.useMergedNativeLibs.set(extension.ndk.useMergedNativeLibs)
            // AGP exposes no public artifact for the unstripped merged libs, so the
            // task reads the conventional folder; an absent/empty one falls back to
            // the native_debug_metadata / native-debug-symbols.zip sources.
            task.mergedNativeLibsDir.set(
                project.layout.buildDirectory.dir("intermediates/merged_native_libs/${variant.name}")
            )
            // Uploader strategy + bugsee-cli binary path/version — mirrors the
            // wiring on MappingUploadTask. Same auto-download story: when
            // `cliPath` is unset, the task downloads `cliVersion` from
            // download.bugsee.com into the per-user Gradle cache.
            task.cliPath.set(projectRelativeCliPath(project, extension))
            task.cliVersion.set(extension.cliVersion)
            task.cliAutoUpdate.set(extension.cliAutoUpdate)
            task.gradleUserHomeDir.fileValue(project.gradle.gradleUserHomeDir)
            task.uploader.set(extension.uploader)
            task.group = "bugsee"
            task.description = "Uploads NDK native debug symbols for $capitalizedVariant"

            // Wire the merged manifest
            task.manifestFile.set(
                variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
            )

            // Resolved BUILD_UUID — see MappingUploadTask wiring.
            task.resolvedBuildIdFile.set(
                resolveTaskProvider.flatMap { it.resolvedBuildIdFile }
            )

            // CC-safe inputs — see resolveCcSafeInputs / the task's
            // KDoc for the full rationale. `buildDirectory` is
            // task-private (per-project, not shared with other tasks).
            ccInputs.preResolvedToken?.let { task.preResolvedAppToken.set(it) }
            if (ccInputs.placeholderSeen) task.placeholderTokenSeen.set(true)
            task.stringResourceFiles.from(ccInputs.stringResFiles)
            task.rootProjectDirectory.fileValue(project.rootDir)
            task.buildDirectory.set(project.layout.buildDirectory)

            // Ensure native debug metadata is extracted before upload.
            // Uses tasks.matching (live filter) to avoid task realization during configuration.
            task.dependsOn(
                project.tasks.matching {
                    it.name == "extract${capitalizedVariant}NativeDebugMetadata"
                }
            )
            // The unstripped libs must be this build's finished output (the CLI reads
            // them in place), also when the upload task is run on its own.
            task.dependsOn(
                project.tasks.matching { it.name == "merge${capitalizedVariant}NativeLibs" }
            )
        }

        // Finalize after assemble/bundle and the packaging/signing tasks below them
        finalizeAfter(
            project,
            apkAndBundleEntryTaskNames(capitalizedVariant, isReleaseLikeVariant(project, variant)),
            nativeUploadTaskProvider,
        )
    }

    /**
     * Names of the AGP tasks a build pipeline may request for [capitalizedVariant]
     * instead of `assemble`/`bundle`: `package<V>` (APK; also what `install<V>`
     * depends on), `package<V>Bundle` and `sign<V>Bundle` (AAB; what Gradle Play
     * Publisher's `publish<V>Bundle` depends on). The uploads must follow these
     * too, otherwise an artifact is produced from a mapping that is never
     * uploaded. The bundle (size-analysis) upload follows only `bundle<V>` and
     * `sign<V>Bundle`, the tasks that yield the signed AAB it reads;
     * `package<V>Bundle` finalizes the mapping/native uploads only. A task absent in a given AGP version is simply never matched.
     *
     * The package/sign tasks are included only for release-like variants
     * ([releaseLike]): `installDebug`, `connectedDebugAndroidTest` and
     * `packageDebug` must not start uploading (e.g. unstripped native libs) on
     * every developer/CI device run. `assemble`/`bundle` keep finalizing for
     * every variant as before. Consequences of the gate: a build type that is
     * debuggable (even if minified) gets no package/sign finalizers, and with
     * `testBuildType = "release"` a `connectedReleaseAndroidTest` run (which
     * packages the release APK) does upload; duplicates are treated as success.
     *
     * Known trade-off: a finalizer also runs when the finalized task fails
     * (e.g. signing fails after R8), so symbols can be stored for a build id
     * that never produced an artifact. Harmless apart from storage/noise;
     * skipping would need the finalized task's state at execution time, which
     * is not configuration-cache friendly.
     */
    private fun apkAndBundleEntryTaskNames(capitalizedVariant: String, releaseLike: Boolean): Set<String> =
        bundleEntryTaskNames(capitalizedVariant, releaseLike) +
            setOfNotNull(
                "assemble$capitalizedVariant",
                "package$capitalizedVariant".takeIf { releaseLike },
                // Writes only the unsigned intermediary bundle: fine for the mapping/native
                // uploads (they do not read the bundle), but NOT for the bundle upload, which
                // consumes the signed SingleArtifact.BUNDLE and would drag sign<V>Bundle (and
                // a signing config) into a stage that deliberately stops unsigned.
                "package${capitalizedVariant}Bundle".takeIf { releaseLike },
            )

    /** Entry tasks that finalize the bundle upload: those that produce the signed AAB. */
    private fun bundleEntryTaskNames(capitalizedVariant: String, releaseLike: Boolean): Set<String> = setOfNotNull(
        "bundle$capitalizedVariant",
        "sign${capitalizedVariant}Bundle".takeIf { releaseLike },
    )

    /**
     * Lazily make every task named in [names] be finalized by [upload]. Uses
     * `configureEach` (no realization) so a missing task is a no-op, and a
     * finalizer runs at most once per build however many of [names] are in
     * the graph.
     */
    private fun finalizeAfter(
        project: Project,
        names: Set<String>,
        upload: TaskProvider<*>,
    ) {
        project.tasks.configureEach { t ->
            if (t.name in names) t.finalizedBy(upload)
        }
    }

    /**
     * Resolve the shared CC-safe inputs (pre-resolved app token +
     * `res/` source files) once per upload-task registration. Two
     * registration paths today (mapping + native) and a third inlined
     * in `registerBundleUploadTask` all need the same plumbing; this
     * helper bottle-necks the reflection-based Android-DSL lookup in
     * one place so a future regression that flips the wrong field
     * surfaces once, not three times.
     *
     * The pre-resolved token chain mirrors `AppTokenResolver.resolve`'s
     * first two layers (DSL → properties file). The third layer
     * (manifest meta-data → `@string/foo` resource resolution) lives
     * in the task action — the merged manifest only exists at
     * execution time. The wired `stringResFiles` lets the action walk
     * the resource tree without re-entering the Android DSL.
     */
    private data class CcSafeUploadInputs(
        val preResolvedToken: String?,
        val stringResFiles: List<File>,
        // A placeholder token was ignored while resolving; the tasks then skip with an info line.
        val placeholderSeen: Boolean = false,
    )

    private fun resolveCcSafeInputs(
        project: Project,
        extension: BugseePluginExtension,
        variant: com.android.build.api.variant.Variant,
    ): CcSafeUploadInputs {
        val isDebug = extension.debug.getOrElse(false)

        // [extension] is the variant's effective view, which carries no app tokens:
        // the token chain (DSL, `variant { }` blocks, bugsee.properties) is the resolver's.
        val tokenCtx = variantContextOf(variant)
        val preResolvedToken: String? = settingsResolver().appTokenFor(tokenCtx, isDebug)
        val placeholderSeen = preResolvedToken == null && settingsResolver().sawPlaceholder(tokenCtx)

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
                    "`@string/foo` tokens in AndroidManifest meta-data + launcher-icon " +
                    "resolution will not work: ${e.message}"
            )
            emptyList()
        }

        return CcSafeUploadInputs(preResolvedToken, stringResFiles, placeholderSeen)
    }

    private fun registerBundleUploadTask(
        project: Project,
        variant: ApplicationVariant,
        extension: BugseePluginExtension,
        capitalizedVariant: String,
        resolveTaskProvider: org.gradle.api.tasks.TaskProvider<BugseeBuildIdResolveTask>,
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
        // CC-safe inputs (pre-resolved token + `res/` source files).
        // Same plumbing is needed by the mapping + native upload
        // tasks, so the lookup lives in `resolveCcSafeInputs` —
        // bottlenecking the reflection-based Android-DSL traversal
        // in one place keeps a future regression from drifting
        // across three call sites.
        val ccInputs = resolveCcSafeInputs(project, extension, variant)
        val preResolvedToken = ccInputs.preResolvedToken
        val stringResFiles = ccInputs.stringResFiles

        // AAB upload task — wired to bundle output
        val bundleUploadTaskProvider = project.tasks.register(
            "uploadBugsee${capitalizedVariant}Bundle",
            BundleUploadTask::class.java
        ) { task ->
            task.debug.set(extension.debug)
            task.failOnUploadError.set(extension.failOnUploadError)
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
            task.resolvedBuildIdFile.set(
                resolveTaskProvider.flatMap { it.resolvedBuildIdFile }
            )
            compileSdkValue?.let { task.buildSdkVersion.set(it) }
            preResolvedToken?.let { task.preResolvedAppToken.set(it) }
            if (ccInputs.placeholderSeen) task.placeholderTokenSeen.set(true)
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
            // Handshake — write the per-variant build-actions
            // manifest next to build-id.txt so the fastlane plugin
            // can find it via the standard intermediates glob.
            task.buildActionsManifestFile.set(
                project.layout.buildDirectory.file(
                    "intermediates/bugsee/${variant.name}/build-actions.json"
                )
            )
            task.pluginVersion.set(PLUGIN_VERSION)
            // Build-info bundle (Phase D) — same CLI plumbing as the
            // mapping upload task. `cliPath` optional (local binary);
            // otherwise the task auto-downloads `cliVersion` into the
            // per-user Gradle cache. `gradleUserHomeDir` is that cache
            // root, captured CC-safely as a File.
            task.cliPath.set(projectRelativeCliPath(project, extension))
            task.cliVersion.set(extension.cliVersion)
            task.cliAutoUpdate.set(extension.cliAutoUpdate)
            task.gradleUserHomeDir.fileValue(project.gradle.gradleUserHomeDir)
            // Escape hatch: `BUGSEE_LEGACY_BUILDINFO_GZIP=1` forces the
            // legacy per-blob gzip PUTs even when the server signed a
            // build-info bundle URL. Read via a provider so the
            // config-cache invalidates correctly when the env var flips.
            task.legacyBuildInfoGzip.set(
                project.providers.environmentVariable("BUGSEE_LEGACY_BUILDINFO_GZIP")
                    .map { it == "1" || it.equals("true", ignoreCase = true) }
                    .orElse(false)
            )
        }

        val releaseLike = isReleaseLikeVariant(project, variant)
        finalizeAfter(project, bundleEntryTaskNames(capitalizedVariant, releaseLike), bundleUploadTaskProvider)

        // APK upload task — wired to APK output
        val apkUploadTaskProvider = project.tasks.register(
            "uploadBugsee${capitalizedVariant}Apk",
            BundleUploadTask::class.java
        ) { task ->
            task.debug.set(extension.debug)
            task.failOnUploadError.set(extension.failOnUploadError)
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
            task.resolvedBuildIdFile.set(
                resolveTaskProvider.flatMap { it.resolvedBuildIdFile }
            )
            compileSdkValue?.let { task.buildSdkVersion.set(it) }
            preResolvedToken?.let { task.preResolvedAppToken.set(it) }
            if (ccInputs.placeholderSeen) task.placeholderTokenSeen.set(true)
            task.stringResourceFiles.from(stringResFiles)
            task.chunkedUpload.set(extension.buildInfo.sizeAnalysis.chunkedUpload)
            task.requestArtifactUpload.set(extension.buildInfo.sizeAnalysis.enabled)
            task.projectDirectory.set(project.layout.projectDirectory)
            wireSizeCheckInputs(task, project, extension.buildInfo.sizeCheck)
            wireDependenciesCollectionInputs(task, project, variant, extension.buildInfo.dependencies)
            // Timing service auto-wired via @ServiceReference (see above).
            task.timingsEnabled.set(extension.buildInfo.timings.enabled)
            // Handshake — same per-variant location as the AAB
            // upload task. The two upload tasks share a single
            // manifest output because Gradle's @OutputFile
            // declaration ties up-to-date checks to the file;
            // either task running rewrites it.
            task.buildActionsManifestFile.set(
                project.layout.buildDirectory.file(
                    "intermediates/bugsee/${variant.name}/build-actions.json"
                )
            )
            task.pluginVersion.set(PLUGIN_VERSION)
            // Build-info bundle (Phase D) — same CLI plumbing as the
            // mapping upload task. `cliPath` optional (local binary);
            // otherwise the task auto-downloads `cliVersion` into the
            // per-user Gradle cache. `gradleUserHomeDir` is that cache
            // root, captured CC-safely as a File.
            task.cliPath.set(projectRelativeCliPath(project, extension))
            task.cliVersion.set(extension.cliVersion)
            task.cliAutoUpdate.set(extension.cliAutoUpdate)
            task.gradleUserHomeDir.fileValue(project.gradle.gradleUserHomeDir)
            // Escape hatch: `BUGSEE_LEGACY_BUILDINFO_GZIP=1` forces the
            // legacy per-blob gzip PUTs even when the server signed a
            // build-info bundle URL. Read via a provider so the
            // config-cache invalidates correctly when the env var flips.
            task.legacyBuildInfoGzip.set(
                project.providers.environmentVariable("BUGSEE_LEGACY_BUILDINFO_GZIP")
                    .map { it == "1" || it.equals("true", ignoreCase = true) }
                    .orElse(false)
            )
        }

        finalizeAfter(
            project,
            setOfNotNull("assemble$capitalizedVariant", "package$capitalizedVariant".takeIf { releaseLike }),
            apkUploadTaskProvider,
        )
    }

    // --- KotlinCompilerPluginSupportPlugin ---

    override fun getCompilerPluginId(): String = "com.bugsee.compose.compiler"

    /**
     * The compiler-plugin artifact matching the CONSUMER's Kotlin version.
     *
     * A compiler plugin binds the exact descriptors of the compiler API it was built against, so we
     * publish one artifact per Kotlin line-group and choose between them here — see
     * [ComposeKotlinCompatibility] for the boundaries and the evidence behind each.
     *
     * Falls back to the base artifact when the version cannot be read. That combination is never
     * actually loaded: [isApplicable] refuses an unreadable version first, so this only keeps the
     * method total.
     */
    override fun getPluginArtifact(): SubpluginArtifact {
        val kotlinVersion = appliedProject?.let { consumerKotlinVersion(it) }
        return SubpluginArtifact(
            groupId = "com.bugsee",
            artifactId = ComposeKotlinCompatibility.artifactIdFor(kotlinVersion)
                ?: ComposeKotlinCompatibility.ARTIFACT_BASE,
            version = PLUGIN_VERSION
        )
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
        val project = kotlinCompilation.target.project
        if (pluginExtension == null) return false
        if (!hasComposeDependency(project)) {
            return false
        }
        // Test compilations keep their composables as written: tag/secure wraps add modifier and
        // semantics nodes that change parent/child matchers in Compose tests (#56). Opt back in
        // with `instrumentation.composeInTests` (any scope enabling it keeps tests on, like the
        // other project-level compiler-plugin switches).
        if (ComposeTestCompilation.isTest(kotlinCompilation) &&
            !isCompilerFeatureEnabled(settingsResolver(), INST_COMPOSE_IN_TESTS, default = false)
        ) return false

        // Kotlin compiler-plugin APIs are not stable across Kotlin minor releases, and this
        // plugin is compiled against ONE of them. Loading it into a compiler whose extension
        // API has moved does not degrade — it aborts the consumer's compilation outright. On
        // Kotlin 2.4 the registrar dies with
        //   ClassCastException: IrGenerationExtension$Companion cannot be cast to
        //   ProjectExtensionDescriptor
        // which the app author can neither diagnose nor work around.
        //
        // So when the consumer's Kotlin is outside the range this artifact was built and
        // verified against, stand down: Compose tag/secure instrumentation is lost, but the
        // build still succeeds. Losing an optional capability is recoverable; failing the
        // build is not.
        val kotlinVersion = consumerKotlinVersion(project)
        if (!ComposeKotlinCompatibility.isSupported(kotlinVersion)) {
            warnUnsupportedKotlinOnce(project, kotlinVersion)
            return false
        }
        // Apply the compiler plugin if EITHER subfeature is enabled. Each
        // subfeature is gated independently inside the compiler plugin via
        // its own SubpluginOption, so the user can keep one on while
        // turning the other off. Project-level: any scope that enables a
        // subfeature keeps it on (see isCompilerFeatureEnabled).
        val resolver = settingsResolver()
        val tagEnabled = isCompilerFeatureEnabled(resolver, INST_COMPOSE)
        val secureEnabled = isCompilerFeatureEnabled(resolver, INST_COMPOSE_SECURE)
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
            val resolver = if (pluginExtension != null) settingsResolver() else null
            val tagEnabled = resolver != null
                    && isCompilerFeatureEnabled(resolver, INST_COMPOSE)
            val secureEnabled = resolver != null
                    && isCompilerFeatureEnabled(resolver, INST_COMPOSE_SECURE)
            listOf(
                // Backward-compatible name: existing CLI option "enabled"
                // continues to control tag injection.
                SubpluginOption("enabled", tagEnabled.toString()),
                SubpluginOption("secure", secureEnabled.toString())
            )
        }
    }

    // --- Dependency detection helpers ---

    // Third-party library detectors scan production-classpath configurations only (see
    // DependencyDetector.anyProductionDependency): a library only in `testImplementation`,
    // `compileOnly`, a lint or kapt configuration never ships in the app, so it must not pull the
    // matching Bugsee extension module into the production classpath. Compose detection also gates
    // [isApplicable] for the Kotlin compiler plugin, so Compose declared only in test / compileOnly
    // configurations no longer applies it either.
    private fun anyProductionDependency(project: Project, predicate: (Dependency) -> Boolean): Boolean =
        DependencyDetector.anyProductionDependency(project, predicate)

    internal fun hasComposeDependency(project: Project): Boolean =
        anyProductionDependency(project) { dep -> dep.group?.startsWith("androidx.compose") == true }

    private fun hasOkHttpDependency(project: Project): Boolean =
        anyProductionDependency(project) { dep -> dep.group == "com.squareup.okhttp3" }

    private fun hasKtorDependency(project: Project, majorVersion: Int): Boolean =
        anyProductionDependency(project) { dep ->
            dep.group == "io.ktor" && dep.version?.startsWith("$majorVersion.") == true
        }

    private fun hasCronetDependency(project: Project): Boolean =
        anyProductionDependency(project) { dep -> dep.group == "org.chromium.net" }

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

        // Declared-scope map + file-deps list. Computed EAGERLY here at
        // configuration time into plain Map<String,String> / List<String>
        // values — deliberately NOT wrapped in `project.provider { }`.
        // A provider whose lambda captures `project` (to read
        // `project.configurations` when realized) is stored unrealized in
        // the task state and fails configuration-cache serialization with
        // "cannot serialize object of type 'org.gradle.api.Project'".
        // Reading DECLARED dependencies (names/groups, and file-dependency
        // file names) does not trigger configuration resolution, so doing
        // it eagerly here is cheap and CC-safe — the values stored on the
        // task are plain serializable types. (`runtimeRootComponent` above
        // stays lazy because `rootComponent` is Gradle's own CC-compatible
        // provider, designed to defer the actual resolution to execution.)
        val scopeNames = listOf("api", "implementation", "runtimeOnly", "compileOnly")
        val confs = LinkedHashMap<String, org.gradle.api.artifacts.Configuration>()
        for (n in scopeNames) {
            project.configurations.findByName(n)?.let { confs[n] = it }
        }
        task.declaredScopes.set(DependencyCollector.collectDeclaredScopes(confs))

        val fileDeps = mutableListOf<String>()
        for (n in scopeNames) {
            val conf = project.configurations.findByName(n) ?: continue
            for (dep in conf.dependencies) {
                if (dep is org.gradle.api.artifacts.FileCollectionDependency) {
                    // `files` enumerates the declared file collection only —
                    // no configuration resolution is triggered.
                    for (f in dep.files) {
                        fileDeps.add("$n|${f.name}")
                    }
                }
            }
        }
        task.fileDependencies.set(fileDeps)
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
    private fun isCoreSdkPresent(
        project: Project,
        ignore: Set<Dependency> = emptySet(),
        inDeps: Lazy<List<BugseeSdkDeclarations.Declaration>> = lazy { BugseeSdkDeclarations.inDependencyProjects(project) },
    ): Boolean {
        return project.configurations.any { config ->
            config.dependencies.any { dep ->
                !isIgnored(ignore, dep) && (isCoreSdkExternal(dep) || isCoreSdkProjectDep(project, dep))
            }
        } || inDeps.value.any { it.isCore && it.exposed }
        // ^ A core declared by a project this one depends on counts only when it is
        // `api`-exposed (or the SDK's own local project). An `implementation`-only pin
        // in a library is on the runtime classpath but invisible at compile time, so
        // the core is still added to the app — at the library's version (see
        // declaredCoreSdkVersion) so the two never conflict.
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
    private fun isCoreSdkProjectDep(from: Project, dep: Dependency): Boolean {
        if (dep !is ProjectDependency) return false
        val depProject = ProjectDependencyCompat.targetProject(from, dep) ?: return false
        if (depProject.findProperty("GROUP") != BUGSEE_GROUP) return false
        return depProject.name == "library" || depProject.name == "stub"
    }

    /**
     * The SDK version the build already declares, or `null` when nothing declares one
     * explicitly (the caller then falls back to the MIN_SDK_VERSION range). Delegates to
     * [BugseeSdkDeclarations.declaredCoreSdkVersion], the single resolver shared with the
     * AppStartup / ExtensionsInit SDK-version gates, so the version those gates test is
     * always the version the auto-add uses. The auto-added core AND every auto-added
     * extension module use it, so they all resolve at the SAME version rather than the
     * plugin's own version.
     */
    private fun declaredCoreSdkVersion(
        project: Project,
        ignore: Set<Dependency> = emptySet(),
        inDeps: Lazy<List<BugseeSdkDeclarations.Declaration>> = lazy { BugseeSdkDeclarations.inDependencyProjects(project) },
    ): String? = BugseeSdkDeclarations.declaredCoreSdkVersion(project, ignore, inDeps)

    /**
     * Adds a Bugsee extension module dependency if not already present.
     * Checks both Maven coordinates (`com.bugsee:{artifactName}`) and
     * project dependencies (`:${projectName}`) to avoid duplicates. Scans
     * all configurations on the consuming project, not just `implementation`.
     *
     * Not reached when `sdkAutoLoad=false` (the withDependencies action returns first).
     */
    private fun autoAddModule(
        project: Project,
        out: MutableList<Dependency>,
        artifactName: String,
        projectName: String,
        isDebug: Boolean,
        sdkVersion: String,
        ignore: Set<Dependency>,
        notes: MutableList<String>,
    ) {
        if (!isModuleDeclared(project, artifactName, projectName, ignore)) {
            // [sdkVersion] tracks the CORE SDK version, NOT the plugin version.
            // Extension AARs (bugsee-android-ndk, -okhttp, -feedback, …) are
            // published under the SDK's version line, so versioning them with
            // PLUGIN_VERSION produced a non-existent coordinate (e.g.
            // `com.bugsee:bugsee-android-ndk:4.0.0-beta14`) and failed the build.
            if (isDebug) notes.add("Bugsee: Auto-adding $artifactName runtime dependency $sdkVersion")
            out.add(project.dependencies.create("com.bugsee:$artifactName:$sdkVersion"))
        }
    }

    /**
     * The Kotlin version the CONSUMER compiles with, or null if it cannot be determined.
     *
     * Read from the applied Kotlin plugin rather than from our own build: what matters is the
     * compiler our extension gets loaded into, which is the consumer's, not ours.
     */
    /**
     * The Kotlin version the CONSUMER compiles with, or null if it cannot be determined.
     *
     * Read from the applied Kotlin plugin rather than from our own build: what matters is the
     * compiler our extension is loaded into, which is the consumer's, not ours.
     *
     * Uses `withType`, NOT `findPlugin`. [KotlinBasePlugin] is an INTERFACE, and
     * `PluginContainer.findPlugin` does not resolve interface types — it silently returns null,
     * which would make every consumer look undeterminable and disable Compose instrumentation
     * for all of them.
     */
    private fun consumerKotlinVersion(project: Project): String? = runCatching {
        project.plugins.withType(KotlinBasePlugin::class.java).firstOrNull()?.pluginVersion
    }.getOrNull()

    private fun knownVariantNames(project: Project): MutableSet<String> {
        return BugseeBuildState.knownVariantNames(project)
    }

    /** Names of every application variant, shared across modules. */
    private fun recordKnownVariantNames(project: Project, ctx: VariantContext) {
        knownVariantNames(project) += ctx.lookupNames
    }

    private fun recordDeclaredNames(project: Project) {
        val names = knownVariantNames(project)
        names += DependencyDetector.declaredVariantNames(project)
    }

    /** A [org.gradle.api.logging.Logger] that drops every message. */
    private fun discardingLogger(): org.gradle.api.logging.Logger =
        java.lang.reflect.Proxy.newProxyInstance(
            org.gradle.api.logging.Logger::class.java.classLoader,
            arrayOf(org.gradle.api.logging.Logger::class.java)
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                String::class.java -> "bugsee"
                else -> null
            }
        } as org.gradle.api.logging.Logger

    private fun warnUnsupportedKotlinOnce(project: Project, version: String?) {
        val key = "bugsee.composeCompilerKotlinWarned"
        if (!BugseeBuildState.claim(project, key)) return
        project.logger.warn(
            "Bugsee gradle plugin: Compose instrumentation is disabled because the project " +
                "uses Kotlin ${version ?: "(undetermined)"}, which this plugin's Compose " +
                "compiler plugin has not been verified against (supported up to Kotlin " +
                "${ComposeKotlinCompatibility.SUPPORTED_MAJOR}.${ComposeKotlinCompatibility.MAX_SUPPORTED_MINOR}). Everything else — " +
                "network, logs, crashes, ANRs — is unaffected. Update the Bugsee Gradle " +
                "plugin to regain Compose tagging."
        )
    }

    companion object {
        /** True when the user declared the Bugsee module (Maven coordinate or project dependency) anywhere. */
        internal fun isModuleDeclared(
            project: Project,
            artifactName: String,
            projectName: String,
            ignore: Set<Dependency> = emptySet(),
        ): Boolean =
            project.configurations.any { config ->
                config.dependencies.any { dep ->
                    !isIgnored(ignore, dep) &&
                        ((dep.group == BUGSEE_GROUP && dep.name == artifactName) ||
                            (dep is ProjectDependency &&
                                ProjectDependencyCompat.targetProject(project, dep)?.let {
                                    it.findProperty("GROUP") == BUGSEE_GROUP && it.name == projectName
                                } == true))
                }
            }

        /** Identity match: excludes dependencies the plugin itself auto-added (see reconcileAutoAdd). */
        private fun isIgnored(ignore: Set<Dependency>, dep: Dependency): Boolean = ignore.any { it === dep }

        /**
         * Explicit opt-ins (`ndk.enabled`, `leak.enabled`, `feedback`) whose runtime module the plugin
         * would normally auto-add but the user did not declare. Under `sdkAutoLoad=false` these switches
         * are inert; returns (setting key, artifact) per such opt-in.
         */
        internal fun inertOptInModules(
            project: Project,
            resolver: SettingsResolver,
            ignore: Set<Dependency> = emptySet(),
        ): List<Pair<String, String>> {
            val ndk = resolver.anyScopeValue(NDK_ENABLED) ?: false
            return listOf(
                Triple("ndk.enabled", "bugsee-android-ndk", "ndk") to ndk,
                Triple("leak.enabled", "bugsee-android-leak", "leak") to resolver.globalValue(LEAK_ENABLED),
                Triple("feedback", "bugsee-android-feedback", "feedback") to resolver.globalValue(FEEDBACK),
            )
                .filter { (t, enabled) -> enabled && !isModuleDeclared(project, t.second, t.third, ignore) }
                .map { (t, _) -> t.first to t.second }
        }

        internal fun sdkAutoLoadOffWarning(setting: String, artifact: String): String =
            "Bugsee: $setting=true but sdkAutoLoad=false and $BUGSEE_GROUP:$artifact is not declared; " +
                "declare it or the runtime is not added"

        /** Android plugins whose application wires the variants (see [apply]). */
        private val ANDROID_PLUGIN_IDS = listOf("com.android.application", "com.android.library")

        /**
         * [BugseePluginExtension.cliPath] with a relative DSL value resolved against [project]'s directory
         * (the module applying the plugin). A relative value from `bugsee.properties` was already made
         * absolute against the root project directory when it was read, so it passes through here. Without
         * this a relative path would resolve against the Gradle daemon's working directory. Absolute and
         * blank values are returned byte-for-byte (no `..` normalisation, no URI parsing). Lazy: the DSL
         * value is not read until the task input is queried.
         */
        internal fun projectRelativeCliPath(project: Project, extension: BugseePluginExtension): Provider<String> {
            val projectDir = project.layout.projectDirectory.asFile
            return extension.cliPath.map { raw -> SettingDefinitions.resolvePath(raw, projectDir) }
        }

        /** The names [variant] is matched by in per-variant settings: name, flavors (dimension order), build type. */
        internal fun variantContextOf(variant: com.android.build.api.variant.Variant): VariantContext =
            VariantContext(variant.name, variant.productFlavors.map { it.second }, variant.buildType)

        /**
         * Whether R8 may publish `OBFUSCATION_MAPPING_FILE` for [variant], i.e. whether
         * the build-id resolve task should listen for it (#53).
         *
         *  - `isMinifyEnabled` true: yes.
         *  - AGP 9 `optimization.enable` on the variant's build type or any of its product
         *    flavors (AGP merges them with OR; `buildTypes.<type>.optimization.enable`, needs
         *    `android.r8.gradual.support=true`) runs R8 and emits a mapping while the variant's
         *    `isMinifyEnabled` stays false; `CanMinifyCode` does not expose it (it is DSL-only),
         *    so it is read reflectively from [androidExtension] (the `android` DSL object; the
         *    plugin compiles against AGP 8.6). No `getOptimization` or no `getEnable` (AGP 8.x,
         *    where R8 is minify-driven): no. Present but unreadable: yes, because wrongly
         *    skipping the listener silently degrades the build id to the manifest-derived one,
         *    which does not change with the code.
         *  - Not a [com.android.build.api.variant.CanMinifyCode]: yes. The `?: true` is
         *    dead for the `ApplicationVariant`s the plugin wires today; it only preserves
         *    the old behaviour should a non-minifiable variant kind ever reach here.
         *
         * Residual gap: a third-party R8 replacement (e.g. DexGuard) with minify off may
         * still publish a mapping; `MappingUploadTask` warns when it finds one.
         */
        internal fun mayProduceMapping(
            variant: com.android.build.api.variant.Variant,
            androidExtension: Any?,
        ): Boolean {
            val minifiable = variant as? com.android.build.api.variant.CanMinifyCode ?: return true
            if (minifiable.isMinifyEnabled) return true
            val extension = androidExtension ?: return false
            return try {
                // AGP merges `optimization.enable` across build type and flavors with OR.
                optimizationEnabled(extension, "getBuildTypes", variant.buildType) ||
                    variant.productFlavors.any { optimizationEnabled(extension, "getProductFlavors", it.second) }
            } catch (_: Exception) {
                true
            }
        }

        /** `android.<containerGetter>.<name>.optimization.enable`; false when any link is absent. */
        private fun optimizationEnabled(extension: Any, containerGetter: String, name: String?): Boolean {
            if (name == null) return false
            val container = callNoArg(extension, containerGetter) as? org.gradle.api.NamedDomainObjectCollection<*>
                ?: return false
            val dsl = container.findByName(name) ?: return false
            // AGP 8.x: no `optimization` or no `enable` -> R8 is minify-driven there.
            val optimization = callNoArg(dsl, "getOptimization") ?: return false
            return callNoArg(optimization, "getEnable") as? Boolean ?: false
        }

        /**
         * Calls a no-arg getter reflectively; null when it does not exist. DSL impl classes may
         * not be public, so a method declared by a public type is preferred and otherwise access
         * is forced (a method that exists but cannot be called throws, which the caller treats as
         * "keep the listener").
         */
        private fun callNoArg(target: Any, name: String): Any? {
            val candidates = target.javaClass.methods.filter { it.name == name && it.parameterCount == 0 }
            val method = candidates.firstOrNull { java.lang.reflect.Modifier.isPublic(it.declaringClass.modifiers) }
                ?: candidates.firstOrNull()?.also { it.isAccessible = true }
                ?: return null
            return method.invoke(target)
        }

        /**
         * Default-level warning for a library module that applies the plugin. [isRoot] is true for a
         * single-module build, whose path is just ":" and so is named by [name] instead.
         */
        internal fun libraryModuleMessage(projectPath: String, isRoot: Boolean, name: String): String =
            "Bugsee: ${if (isRoot) "root project '$name'" else projectPath} is a library module. " +
                "Bugsee registers upload tasks for application modules only; apply the plugin to the " +
                "application module (in a convention plugin, apply it under " +
                "pluginManager.withPlugin(\"com.android.application\")). See the README, \"Library modules\"."

        /** The per-variant debug summary. [token] is masked; it is never printed in full. */
        internal fun variantResolvedMessage(variantName: String, token: String?, endpoint: String): String =
            "Bugsee: variant $variantName resolved: " +
                "token=${token?.let { AppTokenResolver.maskToken(it) } ?: "none"} endpoint=$endpoint"

        /**
         * `withDependencies`-time feature resolution used by the auto-add path, decided before
         * variants exist. [SettingsResolver.anyScopeValue] first: the module is added when the
         * global scope, any DSL `variant { }` block or any `variant.*` file entry enables [def]
         * (a variant that disabled it may then carry an unused module; the bytecode transform
         * stays per variant). With no scope setting it, falls back to the Gradle property
         * `bugsee.instrumentation.<key>`, then defaults to `true`.
         *
         * Without per-variant input this equals the former rule: DSL value or root
         * `plugin.instrumentation.*` file value (the property's convention), then the Gradle
         * property, then `true`.
         *
         * Manifest meta-data is not consulted here — the variant manifest is
         * not yet bound when `withDependencies` runs. Once a variant is bound,
         * [com.bugsee.android.gradle.instrumentation.InstrumentationConfigResolver]
         * provides the full DSL → Gradle property → manifest meta-data →
         * default resolution chain.
         *
         * This unifies the auto-add gates (okhttp / compose / ktor / cronet)
         * so a Gradle-property override like `-Pbugsee.instrumentation.ktor=false`
         * is honoured at AAR-pull-in time, not only at bytecode-instrumentation
         * time.
         */
        internal fun isAutoAddEnabled(
            project: Project,
            resolver: SettingsResolver,
            def: SettingDef<Boolean>,
            key: String
        ): Boolean {
            resolver.anyScopeValue(def)?.let { return it }
            val gradleValue = ProjectAccess.gradleProperty(project, "bugsee.instrumentation.$key")
            if (gradleValue != null) {
                val parsed = gradleValue.toBooleanStrictOrNull()
                if (parsed == null) {
                    project.logger.warn(
                        "Bugsee: Invalid boolean value '$gradleValue' for Gradle property " +
                                "'bugsee.instrumentation.$key', defaulting to true"
                    )
                }
                return parsed ?: true
            }
            return true
        }

        /**
         * Project-level gate for a Compose compiler-plugin subfeature: on when any scope enables
         * [def] (see [isAutoAddEnabled]), off when every scope that sets it disables it, and [default]
         * when nothing sets it (`true` for the compose features, `false` for
         * `composeInTests`). Without per-variant input this equals the former
         * `!property.isPresent || property.get()`.
         */
        internal fun isCompilerFeatureEnabled(
            resolver: SettingsResolver,
            def: SettingDef<Boolean>,
            default: Boolean = true,
        ): Boolean = resolver.anyScopeValue(def) ?: default

        // Settings read at project level (before variants exist).
        private val DEBUG = SettingDefinitions.booleanByKey("debug")
        private val SDK_AUTO_LOAD = SettingDefinitions.booleanByKey("sdkAutoLoad")
        private val FEEDBACK = SettingDefinitions.booleanByKey("feedback")
        private val LEAK_ENABLED = SettingDefinitions.booleanByKey("leak.enabled")
        private val NDK_ENABLED = SettingDefinitions.booleanByKey("ndk.enabled")
        private val INST_COMPOSE = SettingDefinitions.booleanByKey("instrumentation.compose")
        private val INST_COMPOSE_SECURE = SettingDefinitions.booleanByKey("instrumentation.composeSecure")
        private val INST_COMPOSE_INPUT = SettingDefinitions.booleanByKey("instrumentation.composeInput")
        private val INST_COMPOSE_IN_TESTS = SettingDefinitions.booleanByKey("instrumentation.composeInTests")
        private val INST_OKHTTP = SettingDefinitions.booleanByKey("instrumentation.okhttp")
        private val INST_KTOR = SettingDefinitions.booleanByKey("instrumentation.ktor")
        private val INST_CRONET = SettingDefinitions.booleanByKey("instrumentation.cronet")

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
            "leak",
            "ai",
            "compose",
            "feedback",
            "remoting",
            "okhttp",
            "ktor-2",
            "ktor-3",
            "cronet"
        )

        /** True for Bugsee's own SDK subprojects: group `com.bugsee` or a `com.bugsee.` subgroup (not `com.bugseeapp`). */
        internal fun isBugseeSdkGroup(group: String): Boolean = group == "com.bugsee" || group.startsWith("com.bugsee.")
    }
}
