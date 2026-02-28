package com.bugsee.android.gradle.instrumentation.compose_input

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * ClassVisitor that instruments `AndroidComposeView.dispatchTouchEvent(MotionEvent)`
 * to call `BugseeComposeInputAdapter.onComposeTouch(view, event)` at method entry.
 *
 * The injected bytecode is equivalent to:
 * ```java
 * public boolean dispatchTouchEvent(MotionEvent event) {
 *     BugseeComposeInputAdapter.onComposeTouch(this, event);
 *     // ... original method body ...
 * }
 * ```
 */
internal class ComposeInputClassVisitor(
    nextClassVisitor: ClassVisitor
) : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor? {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null

        if (name == DISPATCH_TOUCH_EVENT && descriptor == DISPATCH_TOUCH_EVENT_DESC) {
            return ComposeInputMethodVisitor(mv)
        }

        return mv
    }

    companion object {
        private const val DISPATCH_TOUCH_EVENT = "dispatchTouchEvent"
        private const val DISPATCH_TOUCH_EVENT_DESC = "(Landroid/view/MotionEvent;)Z"
    }
}

/**
 * MethodVisitor that injects a call to `BugseeComposeInputAdapter.onComposeTouch`
 * at the very start of `dispatchTouchEvent`.
 *
 * Injected bytecode:
 * ```
 * ALOAD 0        // this (AndroidComposeView, which is a View)
 * ALOAD 1        // event (MotionEvent)
 * INVOKESTATIC   BugseeComposeInputAdapter.onComposeTouch(View, MotionEvent)V
 * ```
 */
private class ComposeInputMethodVisitor(
    methodVisitor: MethodVisitor
) : MethodVisitor(Opcodes.ASM9, methodVisitor) {

    override fun visitCode() {
        super.visitCode()

        // Inject: BugseeComposeInputAdapter.onComposeTouch(this, event)
        mv.visitVarInsn(Opcodes.ALOAD, 0)  // this (AndroidComposeView)
        mv.visitVarInsn(Opcodes.ALOAD, 1)  // event (MotionEvent)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            ADAPTER_CLASS,
            ADAPTER_METHOD,
            ADAPTER_METHOD_DESC,
            false
        )
    }

    companion object {
        private const val ADAPTER_CLASS =
            "com/bugsee/library/adapters/BugseeComposeInputAdapter"
        private const val ADAPTER_METHOD = "onComposeTouch"
        private const val ADAPTER_METHOD_DESC =
            "(Landroid/view/View;Landroid/view/MotionEvent;)V"
    }
}
