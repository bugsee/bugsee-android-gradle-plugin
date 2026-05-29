package com.bugsee.android.gradle.instrumentation.compose_input

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.bugsee.android.gradle.instrumentation.BugseeInstrumentationParameters
import org.objectweb.asm.ClassVisitor

/**
 * ASM class visitor factory for Compose touch input instrumentation.
 *
 * Instruments `AndroidComposeView.dispatchTouchEvent` to call
 * `BugseeComposeInputAdapter.onComposeTouch` at method entry.
 *
 * Note: [isInstrumentable] accepts all `androidx.compose.ui.platform` classes
 * because AGP may not pass every class through the filter individually (observed
 * with large classes like `AndroidComposeView`). The exact class name check
 * happens inside [createClassVisitor] instead.
 */
abstract class ComposeInputClassVisitorFactory :
    AsmClassVisitorFactory<BugseeInstrumentationParameters> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        if (classContext.loadClassData(parameters.get().targetClass.get()) == null) {
            return nextClassVisitor
        }
        val className = classContext.currentClassData.className
        if (className != ANDROID_COMPOSE_VIEW_CLASS) {
            return nextClassVisitor
        }
        return ComposeInputClassVisitor(nextClassVisitor, className)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        return classData.className.startsWith(COMPOSE_PLATFORM_PACKAGE)
    }

    companion object {
        private const val ANDROID_COMPOSE_VIEW_CLASS =
            "androidx.compose.ui.platform.AndroidComposeView"
        private const val COMPOSE_PLATFORM_PACKAGE =
            "androidx.compose.ui.platform."
    }
}
