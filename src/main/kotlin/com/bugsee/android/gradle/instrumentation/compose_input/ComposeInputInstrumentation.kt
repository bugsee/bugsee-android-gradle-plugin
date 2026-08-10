package com.bugsee.android.gradle.instrumentation.compose_input

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.instrumentation.DependencyDetector
import com.bugsee.android.gradle.instrumentation.SdkSymbolAvailability
import com.bugsee.android.gradle.instrumentation.Instrumentation
import org.gradle.api.Project

/**
 * Instruments `AndroidComposeView.dispatchTouchEvent(MotionEvent)` to call
 * `BugseeComposeInputAdapter.onComposeTouch(view, event)` at the start of
 * the method.
 *
 * This allows the Bugsee SDK to capture touch events inside Compose UI,
 * which cannot be observed via `OnTouchListener` or overlay view injection
 * because `AndroidComposeView` overrides `dispatchTouchEvent` without
 * calling `super`.
 *
 * Gated once at configuration time (in [shouldApply]) on the presence of both
 * `com.bugsee:bugsee-android` (which contains the adapter class) and
 * `androidx.compose.ui:ui` (which contains the target class). The class visitor
 * factory does NOT re-probe the adapter per class (see
 * [ComposeInputClassVisitorFactory] for why that probe is unreliable across
 * AGP's transform-boundary isolation); SDK/version skew instead surfaces at
 * runtime as a `NoClassDefFoundError` on the adapter FQN.
 */
internal class ComposeInputInstrumentation : Instrumentation {

    override val name: String = "ComposeInput"
    override val key: String = "composeInput"

    /**
     * Captured in [shouldApply] for use by [apply], which AGP's Variant API does not hand a
     * Project. The registrar calls the two back to back for each variant, shouldApply first.
     */
    private var hostProject: Project? = null

    override fun shouldApply(project: Project, coreSdkAutoLoad: Boolean): Boolean {
        hostProject = project
        // Needs the core SDK (adapter class) AND a Compose UI dependency.
        // The Compose requirement still stands even when the core is auto-loaded.
        return (coreSdkAutoLoad || DependencyDetector.hasBugseeDependency(project, "bugsee-android")) &&
                hasComposeDependency(project)
    }

    override fun apply(variant: Variant, excludes: Set<String>) {
        variant.instrumentation.transformClassesWith(
            ComposeInputClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) { params ->
            params.targetClass.set("com.bugsee.library.adapters.BugseeComposeInputAdapter")
            hostProject?.let { p ->
                params.symbolAvailable.set(
                    SdkSymbolAvailability.of(p, "com.bugsee.library.adapters.BugseeComposeInputAdapter", "Compose input capture", LOGGER_)
                )
            }
            params.excludes.set(excludes)
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
        )
    }

    /**
     * Checks whether any Compose UI dependency is declared in the project.
     * Apps typically depend on material/material3/foundation which pull in
     * `androidx.compose.ui:ui` transitively, so we check the group prefix
     * rather than a single exact artifact.
     */
    private fun hasComposeDependency(project: Project): Boolean {
        return project.configurations.any { config ->
            config.dependencies.any { dep ->
                dep.group?.startsWith("androidx.compose") == true
            }
        }
    }

    private companion object {
        val LOGGER_ = org.gradle.api.logging.Logging.getLogger(ComposeInputInstrumentation::class.java)
    }
}
