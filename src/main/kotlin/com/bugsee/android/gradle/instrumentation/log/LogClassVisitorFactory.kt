package com.bugsee.android.gradle.instrumentation.log

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.bugsee.android.gradle.instrumentation.BugseeInstrumentationParameters
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
        if (classContext.loadClassData(parameters.get().targetClass.get()) == null) {
            return nextClassVisitor
        }
        return LogClassVisitor(nextClassVisitor, classContext.currentClassData.className)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        val className = classData.className
        return !className.startsWith("com.bugsee.") && !className.startsWith("android.")
    }
}
