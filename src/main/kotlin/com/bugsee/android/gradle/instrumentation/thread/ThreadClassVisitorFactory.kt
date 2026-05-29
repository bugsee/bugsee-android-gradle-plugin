package com.bugsee.android.gradle.instrumentation.thread

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
        if (classContext.loadClassData(parameters.get().targetClass.get()) == null) {
            return nextClassVisitor
        }
        val extendsHandlerThread = HANDLER_THREAD in classContext.currentClassData.superClasses
        return ThreadClassVisitor(
            nextClassVisitor,
            extendsHandlerThread,
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
        return RUNNABLE in classData.interfaces || THREAD in classData.superClasses
    }

    companion object {
        private const val RUNNABLE = "java.lang.Runnable"
        private const val THREAD = "java.lang.Thread"
        private const val HANDLER_THREAD = "android.os.HandlerThread"
    }
}
