package com.bugsee.android.gradle.instrumentation.operation_dispatch

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.instrumentation.DependencyDetector
import com.bugsee.android.gradle.instrumentation.Instrumentation
import org.gradle.api.Project

/**
 * Operation dispatch instrumentation that injects start/end calls around
 * guarded I/O, network, database, and SharedPreferences operations.
 *
 * At runtime the injected calls notify [BugseeOperationDispatcher] so the SDK
 * can measure operation duration and report slow operations.
 *
 * Gated on the presence of `com.bugsee:bugsee-android` dependency.
 */
internal class OperationDispatchInstrumentation : Instrumentation {

    override val name: String = "OperationDispatch"
    override val key: String = "operationDispatch"

    override fun shouldApply(project: Project, coreSdkAutoLoad: Boolean): Boolean {
        return coreSdkAutoLoad || DependencyDetector.hasBugseeDependency(project, "bugsee-android")
    }

    override fun apply(variant: Variant, excludes: Set<String>) {
        variant.instrumentation.transformClassesWith(
            OperationDispatchClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) { params ->
            params.targetClass.set("com.bugsee.library.adapters.BugseeOperationDispatcher")
            params.excludes.set(excludes)
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
        )
    }
}
