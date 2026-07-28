package com.bugsee.android.gradle.instrumentation.http_engine

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.bugsee.android.gradle.instrumentation.BugseeInstrumentationParameters
import com.bugsee.android.gradle.instrumentation.util.InstrumentationExcludes
import org.objectweb.asm.ClassVisitor

/**
 * ASM class visitor factory for HttpEngine instrumentation.
 *
 * Creates [HttpEngineClassVisitor] instances for all classes except those in the
 * bugsee package (to avoid instrumenting the adapter itself).
 */
abstract class HttpEngineClassVisitorFactory :
    AsmClassVisitorFactory<BugseeInstrumentationParameters> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        // SDK presence is verified once at configuration time in
        // HttpEngineInstrumentation.shouldApply (DependencyDetector.hasBugseeDependency).
        // Do NOT re-probe per-class via ClassContext.loadClassData: that probe is
        // unreliable across AGP's artifact-transform isolation boundaries — a class
        // from a third-party JAR is transformed on a classpath that cannot see the
        // consumer's :library dep, so the probe spuriously returns null and silently
        // skips HttpEngine (Cronet) call sites inside those JARs. (Same fix as
        // ComposeInput / AppStartupTracing.)
        return HttpEngineClassVisitor(nextClassVisitor, classContext.currentClassData.className)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        if (InstrumentationExcludes.isExcluded(classData.className, parameters.get().excludes.get())) {
            return false
        }
        // Skip our own adapter/wrapper classes
        return !classData.className.startsWith("com.bugsee.")
    }
}
