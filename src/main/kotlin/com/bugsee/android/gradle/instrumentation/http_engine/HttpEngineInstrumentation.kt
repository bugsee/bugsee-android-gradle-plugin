package com.bugsee.android.gradle.instrumentation.http_engine

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.instrumentation.DependencyDetector
import com.bugsee.android.gradle.instrumentation.Instrumentation
import org.gradle.api.Project

/**
 * HttpEngine instrumentation that injects BugseeHttpEngineAdapter.wrapHttpEngine()
 * after every HttpEngine.Builder.build() call site.
 *
 * Gated on the presence of `com.bugsee:bugsee-android` dependency.
 */
internal class HttpEngineInstrumentation : Instrumentation {

    override val name: String = "HttpEngine"
    override val key: String = "http_engine"

    override fun shouldApply(project: Project): Boolean {
        return DependencyDetector.hasBugseeDependency(project, "bugsee-android", "library")
    }

    override fun apply(variant: Variant) {
        variant.instrumentation.transformClassesWith(
            HttpEngineClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) { params ->
            params.targetClass.set("com.bugsee.library.adapters.BugseeHttpEngineAdapter")
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COPY_FRAMES
        )
    }
}
