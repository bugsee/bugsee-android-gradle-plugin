package com.bugsee.android.gradle.instrumentation.operation_dispatch

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.bugsee.android.gradle.instrumentation.BugseeInstrumentationParameters
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
        if (classContext.loadClassData(parameters.get().targetClass.get()) == null) {
            return nextClassVisitor
        }
        return OperationDispatchClassVisitor(nextClassVisitor)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        val className = classData.className
        if (className.startsWith("com.bugsee.") || className.startsWith("android.")) {
            return false
        }
        return true
    }
}
