package com.bugsee.android.gradle.instrumentation.thread

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.instrumentation.DependencyDetector
import com.bugsee.android.gradle.instrumentation.SdkSymbolAvailability
import com.bugsee.android.gradle.instrumentation.Instrumentation
import org.gradle.api.Project

/**
 * Thread instrumentation that injects `BugseeThreadAdapter.registerThread()`
 * as the first instruction of every `run()` method in classes implementing
 * `Runnable` or extending `Thread`.
 *
 * This builds a Java-to-native thread ID mapping used for native crash
 * reporting and thread tracing.
 *
 * Gated on the presence of `com.bugsee:bugsee-android` dependency.
 */
internal class ThreadInstrumentation : Instrumentation {

    override val name: String = "Thread"
    override val key: String = "thread"

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
            ThreadClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) { params ->
            params.targetClass.set("com.bugsee.library.adapters.BugseeThreadAdapter")
            hostProject?.let { p ->
                params.symbolAvailable.set(
                    SdkSymbolAvailability.of(p, "com.bugsee.library.adapters.BugseeThreadAdapter", "thread tracking", LOGGER_)
                )
            }
            params.excludes.set(excludes)
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COPY_FRAMES
        )
    }

    private companion object {
        val LOGGER_ = org.gradle.api.logging.Logging.getLogger(ThreadInstrumentation::class.java)
    }
}
