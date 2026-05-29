package com.bugsee.android.gradle.instrumentation.log

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.instrumentation.DependencyDetector
import com.bugsee.android.gradle.instrumentation.Instrumentation
import org.gradle.api.Project

/**
 * Log instrumentation that redirects all `android.util.Log` static calls
 * to `BugseeLogAdapter`, which forwards log entries to Bugsee's capture
 * pipeline and then calls the original Log method.
 *
 * Gated on the presence of `com.bugsee:bugsee-android` dependency.
 */
internal class LogInstrumentation : Instrumentation {

    override val name: String = "Log"
    override val key: String = "log"

    override fun shouldApply(project: Project): Boolean {
        return DependencyDetector.hasBugseeDependency(project, "bugsee-android")
    }

    override fun apply(variant: Variant, excludes: Set<String>) {
        variant.instrumentation.transformClassesWith(
            LogClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) { params ->
            params.targetClass.set("com.bugsee.library.adapters.BugseeLogAdapter")
            params.excludes.set(excludes)
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COPY_FRAMES
        )
    }
}
