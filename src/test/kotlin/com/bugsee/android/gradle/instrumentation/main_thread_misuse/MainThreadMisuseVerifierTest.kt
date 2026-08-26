package com.bugsee.android.gradle.instrumentation.main_thread_misuse

import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Verifier coverage for the main-thread-misuse lane.
 *
 * This lane injects a stack-neutral `()V` guard call BEFORE a guarded operation — here
 * `new FileInputStream(String)`, which triggers `checkDiskRead`. The injection lands
 * between a NEW/DUP pair and the `<init>` call, which is the region of a method where
 * the verifier is most particular about uninitialised references, so "stack-neutral"
 * is worth proving rather than assuming.
 */
class MainThreadMisuseVerifierTest {

    private fun fixture(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Reader", null, "java/lang/Object", null)
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "read", "(Ljava/lang/String;)V", null,
            arrayOf("java/io/IOException"),
        )
        mv.visitCode()
        mv.visitTypeInsn(Opcodes.NEW, "java/io/FileInputStream")
        mv.visitInsn(Opcodes.DUP)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL, "java/io/FileInputStream", "<init>",
            "(Ljava/lang/String;)V", false,
        )
        mv.visitVarInsn(Opcodes.ASTORE, 1)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(3, 2)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun transform(): ByteArray =
        AsmTestHarness.transform(fixture()) { w -> MainThreadMisuseClassVisitor(w, "com.example.Reader") }

    @Test
    fun `injected guard produces verifiable bytecode`() {
        AsmTestHarness.verify(transform()).assertOk()
    }

    /** Not vacuous: the guard must actually have been injected. */
    @Test
    fun `the guard call is injected`() {
        var guarded = false
        ClassReader(transform()).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    a: Int, n: String?, d: String?, s: String?, e: Array<out String>?,
                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        op: Int, owner: String?, name: String?, desc: String?, itf: Boolean,
                    ) {
                        if (owner?.endsWith("BugseeMainThreadGuardAdapter") == true) guarded = true
                    }
                }
            },
            0,
        )
        assertTrue("a guarded FileInputStream construction must be instrumented", guarded)
    }
}
