package com.bugsee.android.gradle.instrumentation.extensions_init

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.BugseePluginExtension
import com.bugsee.android.gradle.instrumentation.DependencyDetector
import com.bugsee.android.gradle.instrumentation.Instrumentation
import com.bugsee.android.gradle.manifest.BugseeManifestTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * Bytecode instrumentation that rewrites `BugseeInitProvider.initializeExtensions()`
 * to inline `register<Name>Extension()` calls for every Bugsee extension
 * the plugin stripped from the merged manifest.
 *
 * Gated by:
 *   1. Presence of the core Bugsee SDK dependency.
 *   2. `bugsee { optimizeExtensionsLoading = true }` (default: `true`).
 *
 * The instrumentation reads the detected-extensions file produced by
 * [BugseeManifestTask] — the [TaskProvider] is passed in at construction
 * time by [com.bugsee.android.gradle.BugseePlugin] so the visitor's
 * parameter property carries the correct task dependency.
 */
internal class ExtensionsInitInstrumentation(
    private val pluginExtension: BugseePluginExtension,
    private val manifestTaskProvider: TaskProvider<BugseeManifestTask>,
) : Instrumentation {

    override val name: String = "ExtensionsInit"
    override val key: String = "extensionsInit"

    // Tier-driven so the registrar's standard boolean-key lookup
    // (which would try `instrumentation.extensionsInit`) is bypassed.
    // We own our own gate via [pluginExtension.optimizeExtensionsLoading].
    override val isTierDriven: Boolean = true

    override fun shouldApply(project: Project): Boolean {
        if (!pluginExtension.optimizeExtensionsLoading.getOrElse(true)) {
            return false
        }
        return DependencyDetector.hasBugseeDependency(project, "bugsee-android")
    }

    override fun apply(variant: Variant, excludes: Set<String>) {
        variant.instrumentation.transformClassesWith(
            ExtensionsInitClassVisitorFactory::class.java,
            InstrumentationScope.ALL,
        ) { params ->
            params.detectedExtensionsFile.set(
                manifestTaskProvider.flatMap { it.detectedExtensions }
            )
            params.excludes.set(excludes)
        }
        // The injected try/catch blocks introduce new stack frames, so
        // recompute frames for instrumented methods rather than copying
        // the originals (which were computed without the catch handler).
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
        )
    }
}
