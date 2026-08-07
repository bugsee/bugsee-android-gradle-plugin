package com.bugsee.android.gradle.instrumentation.okhttp

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.bugsee.android.gradle.instrumentation.util.InstrumentationExcludes
import org.objectweb.asm.ClassVisitor

/**
 * ASM class visitor factory for OkHttp instrumentation.
 *
 * This factory creates [OkHttpClassVisitor] instances for all classes except
 * those in the bugsee and okhttp3 packages (to avoid infinite recursion).
 */
abstract class OkHttpClassVisitorFactory :
    AsmClassVisitorFactory<OkHttpInstrumentationParameters> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        // Extension presence is verified once at configuration time in
        // OkHttpInstrumentation.shouldApply (DependencyDetector.hasBugseeDependency
        // for the okhttp extension). Do NOT re-probe per-class via
        // ClassContext.loadClassData: that probe is unreliable across AGP's
        // artifact-transform isolation boundaries — a class from a third-party JAR
        // is transformed on a classpath that cannot see the consumer's :library /
        // extension dep, so the probe spuriously returns null and silently skips
        // OkHttp call sites inside those JARs. (Same fix as ComposeInput /
        // AppStartupTracing.)
        return OkHttpClassVisitor(
            nextClassVisitor,
            classContext.currentClassData.className,
            // Resolved once at configuration time; see OkHttpInstrumentationParameters.
            parameters.get().webSocketCapture.getOrElse(false),
        )
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        val className = classData.className
        if (InstrumentationExcludes.isExcluded(className, parameters.get().excludes.get())) {
            return false
        }
        // Skip our own interceptor classes and OkHttp internals
        return !className.startsWith("com.bugsee.") && !className.startsWith("okhttp3.")
    }
}
