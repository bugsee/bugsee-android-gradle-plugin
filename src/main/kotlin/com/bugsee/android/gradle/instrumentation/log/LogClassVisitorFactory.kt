package com.bugsee.android.gradle.instrumentation.log

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.bugsee.android.gradle.instrumentation.BugseeInstrumentationParameters
import com.bugsee.android.gradle.instrumentation.util.InstrumentationExcludes
import org.objectweb.asm.ClassVisitor

/**
 * ASM class visitor factory for android.util.Log instrumentation.
 *
 * Creates [LogClassVisitor] instances for all classes except those in
 * the bugsee and android packages (to avoid infinite recursion since
 * BugseeLogAdapter itself calls android.util.Log).
 */
abstract class LogClassVisitorFactory :
    AsmClassVisitorFactory<BugseeInstrumentationParameters> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        // SDK presence is verified once at configuration time in
        // LogInstrumentation.shouldApply (DependencyDetector.hasBugseeDependency).
        // Do NOT re-probe per-class via ClassContext.loadClassData: that probe is
        // unreliable across AGP's artifact-transform isolation boundaries — a class
        // from a third-party JAR is transformed on a classpath that cannot see the
        // consumer's :library dep, so the probe spuriously returns null and silently
        // skips android.util.Log call sites inside those JARs. (Same fix as
        // ComposeInput / AppStartupTracing.)
        return LogClassVisitor(nextClassVisitor, classContext.currentClassData.className)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        val className = classData.className
        if (InstrumentationExcludes.isExcluded(className, parameters.get().excludes.get())) {
            return false
        }
        return !className.startsWith("com.bugsee.") && !className.startsWith("android.")
    }
}
