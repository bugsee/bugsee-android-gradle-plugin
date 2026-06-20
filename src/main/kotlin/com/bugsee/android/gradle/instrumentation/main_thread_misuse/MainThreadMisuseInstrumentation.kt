package com.bugsee.android.gradle.instrumentation.main_thread_misuse

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.instrumentation.DependencyDetector
import com.bugsee.android.gradle.instrumentation.Instrumentation
import org.gradle.api.Project

/**
 * Main thread misuse instrumentation that injects lightweight check calls
 * before guarded I/O, network, database, and SharedPreferences operations.
 *
 * At runtime the injected checks determine whether the current thread is the
 * main thread and, if so, report the violation to the Bugsee SDK.
 *
 * Gated on the presence of `com.bugsee:bugsee-android` dependency.
 */
internal class MainThreadMisuseInstrumentation : Instrumentation {

    override val name: String = "MainThreadMisuse"
    override val key: String = "mainThreadMisuse"

    override fun shouldApply(project: Project, coreSdkAutoLoad: Boolean): Boolean {
        return coreSdkAutoLoad || DependencyDetector.hasBugseeDependency(project, "bugsee-android")
    }

    override fun apply(variant: Variant, excludes: Set<String>) {
        variant.instrumentation.transformClassesWith(
            MainThreadMisuseClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) { params ->
            params.targetClass.set("com.bugsee.library.adapters.BugseeMainThreadGuardAdapter")
            params.excludes.set(excludes)
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COPY_FRAMES
        )
    }
}
