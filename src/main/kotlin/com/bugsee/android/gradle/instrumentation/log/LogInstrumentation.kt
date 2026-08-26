package com.bugsee.android.gradle.instrumentation.log

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.instrumentation.DependencyDetector
import com.bugsee.android.gradle.instrumentation.SdkSymbolAvailability
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

    /**
     * Captured in [shouldApply] for use by [apply], which AGP's Variant API does not hand a
     * Project. The registrar calls the two back to back for each variant, shouldApply first.
     */
    private var hostProject: Project? = null

    override fun shouldApply(project: Project, coreSdkAutoLoad: Boolean, scope: Set<String>?): Boolean {
        hostProject = project
        return coreSdkAutoLoad || DependencyDetector.hasBugseeDependency(project, "bugsee-android", null, scope)
    }

    override fun apply(variant: Variant, excludes: Set<String>) {
        variant.instrumentation.transformClassesWith(
            LogClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) { params ->
            params.targetClass.set("com.bugsee.library.adapters.BugseeLogAdapter")
            hostProject?.let { p ->
                params.symbolAvailable.set(
                    SdkSymbolAvailability.of(p, "com.bugsee.library.adapters.BugseeLogAdapter", "log capture", LOGGER_)
                )
            }
            params.excludes.set(excludes)
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COPY_FRAMES
        )
    }

    private companion object {
        val LOGGER_ = org.gradle.api.logging.Logging.getLogger(LogInstrumentation::class.java)
    }
}
