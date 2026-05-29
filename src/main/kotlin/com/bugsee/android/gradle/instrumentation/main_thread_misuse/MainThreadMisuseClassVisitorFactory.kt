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
        if (classContext.loadClassData(parameters.get().targetClass.get()) == null) {
            return nextClassVisitor
        }
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
