package com.bugsee.android.gradle.instrumentation.http_engine

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.instrumentation.DependencyDetector
import com.bugsee.android.gradle.instrumentation.SdkSymbolAvailability
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

    override fun shouldApply(project: Project, coreSdkAutoLoad: Boolean): Boolean {
        return coreSdkAutoLoad ||
            DependencyDetector.hasBugseeDependency(project, "bugsee-android", "library")
    }

    override fun apply(variant: Variant, excludes: Set<String>) {
        variant.instrumentation.transformClassesWith(
            HttpEngineClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) { params ->
            params.targetClass.set("com.bugsee.library.adapters.BugseeHttpEngineAdapter")
            params.symbolAvailable.set(
                SdkSymbolAvailability.of(variant, "com.bugsee.library.adapters.BugseeHttpEngineAdapter", "HttpEngine network capture", LOGGER_)
            )
            params.excludes.set(excludes)
        }
        // The injected call sites grow the operand stack (DUP / DUP2 / DUP_X2 /
        // DUP2_X1 in HttpEngineClassVisitor), so the original method's max_stack
        // is no longer valid. COPY_FRAMES copies it verbatim → a too-small
        // max_stack → ART/JVM VerifyError at class load. Recompute frames+maxs
        // for instrumented methods (matching operation_dispatch / compose_input /
        // extensions_init, which inject similarly).
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
        )
    }

    private companion object {
        val LOGGER_ = org.gradle.api.logging.Logging.getLogger(HttpEngineInstrumentation::class.java)
    }
}
