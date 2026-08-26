package com.bugsee.android.gradle.instrumentation.thread

import com.bugsee.android.gradle.instrumentation.util.MinifiedClassSkip
import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.bugsee.android.gradle.instrumentation.BugseeInstrumentationParameters
import com.bugsee.android.gradle.instrumentation.util.InstrumentationExcludes
import org.objectweb.asm.ClassVisitor

/**
 * ASM class visitor factory for Thread.run() instrumentation.
 *
 * Only instruments classes that implement [Runnable] or extend [Thread],
 * excluding bugsee and android framework packages.
 */
abstract class ThreadClassVisitorFactory :
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
        // ThreadInstrumentation.shouldApply (DependencyDetector.hasBugseeDependency).
        // Do NOT re-probe per-class via ClassContext.loadClassData: that probe is
        // unreliable across AGP's artifact-transform isolation boundaries — a class
        // from a third-party JAR is transformed on a classpath that cannot see the
        // consumer's :library dep, so the probe spuriously returns null and silently
        // skips thread call sites (and HandlerThread subclasses) inside those JARs.
        // (Same fix as ComposeInput / AppStartupTracing.)
        val extendsHandlerThread = HANDLER_THREAD in classContext.currentClassData.superClasses
        return ThreadClassVisitor(
            nextClassVisitor,
            extendsHandlerThread,
            classContext.currentClassData.className,
        )
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
        if (className.startsWith("com.bugsee.") || className.startsWith("android.")) {
            return false
        }
        return RUNNABLE in classData.interfaces || THREAD in classData.superClasses
    }

    companion object {
        private const val RUNNABLE = "java.lang.Runnable"
        private const val THREAD = "java.lang.Thread"
        private const val HANDLER_THREAD = "android.os.HandlerThread"
    }
}
