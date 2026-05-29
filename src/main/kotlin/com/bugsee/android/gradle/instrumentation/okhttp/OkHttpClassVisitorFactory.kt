package com.bugsee.android.gradle.instrumentation.okhttp

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.bugsee.android.gradle.instrumentation.BugseeInstrumentationParameters
import org.objectweb.asm.ClassVisitor

/**
 * ASM class visitor factory for OkHttp instrumentation.
 *
 * This factory creates [OkHttpClassVisitor] instances for all classes except
 * those in the bugsee and okhttp3 packages (to avoid infinite recursion).
 */
abstract class OkHttpClassVisitorFactory :
    AsmClassVisitorFactory<BugseeInstrumentationParameters> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        if (classContext.loadClassData(parameters.get().targetClass.get()) == null) {
            return nextClassVisitor
        }
        return OkHttpClassVisitor(nextClassVisitor, classContext.currentClassData.className)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        val className = classData.className
        // Skip our own interceptor classes and OkHttp internals
        return !className.startsWith("com.bugsee.") && !className.startsWith("okhttp3.")
    }
}
