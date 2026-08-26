package com.bugsee.android.gradle.instrumentation.log

import com.bugsee.android.gradle.instrumentation.util.MinifiedClassSkip
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
        // R8-optimised classes are left completely untouched: rewriting them is the
        // largest single source of VerifyError / dexing failures in comparable plugins,
        // and InstrumentationScope.ALL puts third-party AARs in our path. Fails open —
        // see MinifiedClassSkip.
        if (MinifiedClassSkip.shouldSkip(nextClassVisitor)) {
            return nextClassVisitor
        }

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
        // Graceful degradation: the SDK on this classpath may predate the symbol this lane
        // injects. Emitting the call anyway would put an unlinkable INVOKESTATIC into the host
        // app's own bytecode — NoClassDefFoundError at runtime, or an R8 "Missing class" failure.
        // Resolved once from the actual artifacts (see SdkSymbolAvailability), not per class.
        if (!parameters.get().symbolAvailable.getOrElse(true)) {
            return false
        }
        if (InstrumentationExcludes.isExcluded(className, parameters.get().excludes.get())) {
            return false
        }
        return !className.startsWith("com.bugsee.") && !className.startsWith("android.")
    }
}
