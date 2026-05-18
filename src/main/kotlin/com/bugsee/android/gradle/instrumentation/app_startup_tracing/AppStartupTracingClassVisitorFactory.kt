package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import org.objectweb.asm.ClassVisitor

/**
 * ASM class visitor factory for app-startup tracing instrumentation.
 *
 * **Phase 3 skeleton — does not yet rewrite any bytecode.** Reports every
 * candidate class as not instrumentable, so applying this factory to a
 * build is a no-op beyond the AGP plumbing.
 *
 * Phase 5 will narrow the candidate set to Application / ContentProvider /
 * AndroidX `Initializer` / Firebase `ComponentRegistrar` / WorkManager
 * `Configuration.Provider` subclasses (plus, at `FULL` tier,
 * `@BugseeTrace`-annotated methods anywhere) and start emitting wraps.
 */
abstract class AppStartupTracingClassVisitorFactory :
    AsmClassVisitorFactory<AppStartupTracingParameters> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        // Phase 3 skeleton: isInstrumentable always returns false so this
        // method is never reached by AGP for real classes. The real
        // dispatcher-class probe (loadClassData on targetClass) lands in
        // Phase 5 once isInstrumentable starts saying yes; until then the
        // pass-through here is enough.
        return nextClassVisitor
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        // Phase 3 skeleton: no class is instrumentable. The class filter
        // (Application / ContentProvider / Initializer / ComponentRegistrar
        // / Configuration.Provider) lands in Phase 5.
        return false
    }
}
