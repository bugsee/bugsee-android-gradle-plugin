package com.bugsee.android.gradle.instrumentation.okhttp

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.instrumentation.DependencyDetector
import com.bugsee.android.gradle.instrumentation.Instrumentation
import org.gradle.api.Project

/**
 * OkHttp instrumentation that injects BugseeOkHttpInterceptor into every
 * OkHttpClient.Builder.build() call site.
 *
 * Gated on the presence of `com.bugsee:bugsee-android-okhttp` dependency.
 */
internal class OkHttpInstrumentation : Instrumentation {

    override val name: String = "OkHttp"
    override val key: String = "okhttp"

    override fun shouldApply(project: Project, coreSdkAutoLoad: Boolean): Boolean {
        // Extension-gated: requires the okhttp extension AAR specifically, so
        // core-SDK auto-load alone does not enable it.
        return DependencyDetector.hasBugseeDependency(project, "bugsee-android-okhttp", "okhttp")
    }

    override fun apply(variant: Variant, excludes: Set<String>) {
        variant.instrumentation.transformClassesWith(
            OkHttpClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) { params ->
            params.targetClass.set("com.bugsee.library.okhttp.BugseeOkHttpInterceptor")
            params.excludes.set(excludes)
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COPY_FRAMES
        )
    }
}
