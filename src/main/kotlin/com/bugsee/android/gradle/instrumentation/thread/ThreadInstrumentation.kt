package com.bugsee.android.gradle.instrumentation.thread

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.instrumentation.DependencyDetector
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

    override fun shouldApply(project: Project, coreSdkAutoLoad: Boolean): Boolean {
        return coreSdkAutoLoad || DependencyDetector.hasBugseeDependency(project, "bugsee-android")
    }

    override fun apply(variant: Variant, excludes: Set<String>) {
        variant.instrumentation.transformClassesWith(
            ThreadClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) { params ->
            params.targetClass.set("com.bugsee.library.adapters.BugseeThreadAdapter")
            params.excludes.set(excludes)
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COPY_FRAMES
        )
    }
}
