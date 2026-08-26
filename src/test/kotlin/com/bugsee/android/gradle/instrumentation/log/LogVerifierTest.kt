package com.bugsee.android.gradle.instrumentation.log

import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Verifier coverage for the log lane: `android.util.Log.d(String, String)` is rewritten
 * to `BugseeLogAdapter.d(String, String)`.
 *
 * The rewrite is descriptor-preserving, so it is the lowest-risk of the lanes — but it is
 * still emitted bytecode nobody was checking, and "low risk" is an assumption worth
 * pinning rather than trusting. A future change to the adapter's descriptor would
 * silently produce an unverifiable class.
 */
class LogVerifierTest {

    private fun fixture(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Logs", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "go", "()V", null, null)
        mv.visitCode()
        mv.visitLdcInsn("tag")
        mv.visitLdcInsn("msg")
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, "android/util/Log", "d",
            "(Ljava/lang/String;Ljava/lang/String;)I", false,
        )
        mv.visitInsn(Opcodes.POP)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(2, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun transform(): ByteArray =
        AsmTestHarness.transform(fixture()) { w -> LogClassVisitor(w, "com.example.Logs") }

    @Test
    fun `rewritten log call produces verifiable bytecode`() {
        AsmTestHarness.verify(
            transform(),
            syntheticTypes = mapOf("android/util/Log" to "java/lang/Object"),
        ).assertOk()
    }

    /** Not vacuous: the rewrite must actually have happened. */
    @Test
    fun `the call is redirected to the Bugsee adapter`() {
        var redirected = false
        ClassReader(transform()).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    a: Int, n: String?, d: String?, s: String?, e: Array<out String>?,
                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        op: Int, owner: String?, name: String?, desc: String?, itf: Boolean,
                    ) {
                        if (owner?.endsWith("BugseeLogAdapter") == true) redirected = true
                    }
                }
            },
            0,
        )
        assertTrue("android.util.Log call must be redirected", redirected)
    }
}
