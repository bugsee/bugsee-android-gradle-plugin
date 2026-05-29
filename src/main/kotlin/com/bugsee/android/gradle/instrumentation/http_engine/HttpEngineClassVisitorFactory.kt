package com.bugsee.android.gradle.instrumentation.http_engine

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.bugsee.android.gradle.instrumentation.BugseeInstrumentationParameters
import com.bugsee.android.gradle.instrumentation.util.InstrumentationExcludes
import org.objectweb.asm.ClassVisitor

/**
 * ASM class visitor factory for HttpEngine instrumentation.
 *
 * Creates [HttpEngineClassVisitor] instances for all classes except those in the
 * bugsee package (to avoid instrumenting the adapter itself).
 */
abstract class HttpEngineClassVisitorFactory :
    AsmClassVisitorFactory<BugseeInstrumentationParameters> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        if (classContext.loadClassData(parameters.get().targetClass.get()) == null) {
            return nextClassVisitor
        }
        return HttpEngineClassVisitor(nextClassVisitor, classContext.currentClassData.className)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        if (InstrumentationExcludes.isExcluded(classData.className, parameters.get().excludes.get())) {
            return false
        }
        // Skip our own adapter/wrapper classes
        return !classData.className.startsWith("com.bugsee.")
    }
}
