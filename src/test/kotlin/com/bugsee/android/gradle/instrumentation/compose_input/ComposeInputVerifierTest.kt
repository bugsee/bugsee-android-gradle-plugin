package com.bugsee.android.gradle.instrumentation.compose_input

import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Verifier coverage for the Compose-input lane.
 *
 * This lane carries the highest verifier risk of the call-site lanes: it does not merely
 * insert a stack-neutral `()V` call, it loads `this` AND the `MotionEvent` argument
 * before invoking the adapter. A wrong local slot or a wrong type produces bytecode that
 * the compiler and the plugin build both accept and that fails at CLASS LOAD on a
 * device, as `VerifyError`, inside whichever app happens to contain an
 * `AndroidComposeView`.
 *
 * `AsmTestHarness.verify` runs `CheckClassAdapter.verify` plus per-method
 * `SimpleVerifier` dataflow — `SimpleVerifier` resolves real reference types, so it
 * catches an argument-type mismatch that `BasicInterpreter` would erase to "REFERENCE".
 */
class ComposeInputVerifierTest {

    /** A minimal stand-in for `AndroidComposeView.dispatchTouchEvent(MotionEvent)Z`. */
    private fun fixture(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(
            Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            "androidx/compose/ui/platform/AndroidComposeView",
            null, "android/view/ViewGroup", null,
        )
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC, "dispatchTouchEvent", "(Landroid/view/MotionEvent;)Z", null, null,
        )
        mv.visitCode()
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(1, 2)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun transform(bytes: ByteArray): ByteArray =
        AsmTestHarness.transform(bytes) { writer ->
            ComposeInputClassVisitor(writer, "androidx.compose.ui.platform.AndroidComposeView")
        }

    @Test
    fun `injected touch dispatch produces verifiable bytecode`() {
        // The hierarchy matters: the injected call passes `this` where a View is
        // expected, so AndroidComposeView must be assignable to View for the dataflow
        // check to mean anything.
        AsmTestHarness.verify(
            transform(fixture()),
            syntheticTypes = mapOf(
                "androidx/compose/ui/platform/AndroidComposeView" to "android/view/ViewGroup",
                "android/view/ViewGroup" to "android/view/View",
                "android/view/View" to "java/lang/Object",
                "android/view/MotionEvent" to "java/lang/Object",
            ),
        ).assertOk()
    }

    /**
     * Guards against the test passing vacuously: if the lane silently stopped injecting,
     * the verifier check above would still be green while capture was dead.
     */
    @Test
    fun `the adapter call is actually injected`() {
        var found = false
        ClassReader(transform(fixture())).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    a: Int, n: String?, d: String?, s: String?, e: Array<out String>?,
                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        op: Int, owner: String?, name: String?, desc: String?, itf: Boolean,
                    ) {
                        if (owner?.endsWith("BugseeComposeInputAdapter") == true) found = true
                    }
                }
            },
            0,
        )
        assertTrue("the Compose-input lane must inject the adapter call", found)
    }
}
