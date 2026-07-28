package com.bugsee.android.gradle.instrumentation.operation_dispatch

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.bugsee.android.gradle.instrumentation.BugseeInstrumentationParameters
import com.bugsee.android.gradle.instrumentation.util.InstrumentationExcludes
import org.objectweb.asm.ClassVisitor

/**
 * ASM class visitor factory for operation dispatch instrumentation.
 *
 * Instruments all non-excluded classes — any class may perform file I/O,
 * network, database, or SharedPreferences operations that should be tracked.
 */
abstract class OperationDispatchClassVisitorFactory :
    AsmClassVisitorFactory<BugseeInstrumentationParameters> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        // SDK presence is verified once at configuration time in
        // OperationDispatchInstrumentation.shouldApply
        // (DependencyDetector.hasBugseeDependency). Do NOT re-probe per-class via
        // ClassContext.loadClassData: that probe is unreliable across AGP's
        // artifact-transform isolation boundaries — a class from a third-party JAR
        // is transformed on a classpath that cannot see the consumer's :library dep,
        // so the probe spuriously returns null and silently skips guarded I/O call
        // sites inside those JARs. (Same fix as ComposeInput / AppStartupTracing.)
        return OperationDispatchClassVisitor(
            nextClassVisitor,
            classContext.currentClassData.className,
        )
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        val className = classData.className
        if (InstrumentationExcludes.isExcluded(className, parameters.get().excludes.get())) {
            return false
        }
        if (className.startsWith("com.bugsee.") || className.startsWith("android.")) {
            return false
        }
        return true
    }
}
