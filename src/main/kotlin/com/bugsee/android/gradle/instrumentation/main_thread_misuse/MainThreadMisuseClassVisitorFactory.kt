package com.bugsee.android.gradle.instrumentation.main_thread_misuse

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.bugsee.android.gradle.instrumentation.BugseeInstrumentationParameters
import com.bugsee.android.gradle.instrumentation.util.InstrumentationExcludes
import org.objectweb.asm.ClassVisitor

/**
 * ASM class visitor factory for main thread misuse detection.
 *
 * Instruments all non-excluded classes — any class may perform file I/O,
 * network, database, or SharedPreferences operations on the main thread.
 */
abstract class MainThreadMisuseClassVisitorFactory :
    AsmClassVisitorFactory<BugseeInstrumentationParameters> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        // SDK presence is verified once at configuration time in
        // MainThreadMisuseInstrumentation.shouldApply
        // (DependencyDetector.hasBugseeDependency). Do NOT re-probe per-class via
        // ClassContext.loadClassData: that probe is unreliable across AGP's
        // artifact-transform isolation boundaries — a class from a third-party JAR
        // is transformed on a classpath that cannot see the consumer's :library dep,
        // so the probe spuriously returns null and silently skips guarded call sites
        // inside those JARs. (Same fix as ComposeInput / AppStartupTracing.)
        return MainThreadMisuseClassVisitor(nextClassVisitor, classContext.currentClassData.className)
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
