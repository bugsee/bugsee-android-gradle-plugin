package com.bugsee.android.gradle.instrumentation.compose_input

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.instrumentation.DependencyDetector
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
 * Gated on the presence of both `com.bugsee:bugsee-android` (which contains
 * the adapter class) and `androidx.compose.ui:ui` (which contains the target
 * class). If the adapter class is not on the classpath (e.g. older/incompatible
 * SDK version), the class visitor factory skips transformation.
 */
internal class ComposeInputInstrumentation : Instrumentation {

    override val name: String = "ComposeInput"
    override val key: String = "composeInput"

    override fun shouldApply(project: Project): Boolean {
        return DependencyDetector.hasBugseeDependency(project, "bugsee-android") &&
                hasComposeDependency(project)
    }

    override fun apply(variant: Variant) {
        variant.instrumentation.transformClassesWith(
            ComposeInputClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) { params ->
            params.targetClass.set("com.bugsee.library.adapters.BugseeComposeInputAdapter")
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
}
