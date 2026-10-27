package com.bugsee.android.gradle.instrumentation.util

import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import com.bugsee.android.gradle.instrumentation.log.LogClassVisitor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassTooLargeException
import org.objectweb.asm.Opcodes

/**
 * A class whose constant pool is already at the 65,535-entry limit cannot take any instrumentation:
 * `ClassTooLargeException` is raised by AGP's writer after the visitor chain is done. Every lane
 * consults [MinifiedClassSkip.shouldSkip] first, which leaves such a class wholly untouched (#97).
 */
class PoolNearLimitSkipTest {

    /** A class with [entries] extra string constants (~2 pool entries each) and one `Log.d` call. */
    private fun classWithPool(entries: Int, poolSize: Int? = null): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "a/b/big", null, "java/lang/Object", null)
        var left = entries
        var n = 0
        while (left > 0) {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "f$n", "()V", null, null)
            mv.visitCode()
            val batch = minOf(left, 8000)
            repeat(batch) { mv.visitLdcInsn("s${n}_$it"); mv.visitInsn(Opcodes.POP) }
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
            left -= batch
            n++
        }
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "log", "()V", null, null)
        mv.visitCode()
        mv.visitLdcInsn("t")
        mv.visitLdcInsn("m")
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "android/util/Log", "d", "(Ljava/lang/String;Ljava/lang/String;)I", false)
        mv.visitInsn(Opcodes.POP)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        // Top up one Utf8 entry at a time so the pool lands exactly on [poolSize] (if given).
        if (poolSize != null) {
            var i = 0
            while (cw.newUTF8("pad$i") < poolSize - 1) i++
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun shouldSkip(bytes: ByteArray): Boolean {
        var skip = false
        AsmTestHarness.transform(bytes) { writer ->
            skip = MinifiedClassSkip.shouldSkip(writer)
            writer
        }
        return skip
    }

    /** What a factory does: skip first, otherwise install the lane. */
    private fun logLane(bytes: ByteArray): ByteArray =
        AsmTestHarness.transform(bytes) { writer ->
            if (MinifiedClassSkip.shouldSkip(writer)) writer else LogClassVisitor(writer, "a.b.big")
        }

    @Test
    fun `a class that is full is left untouched instead of failing the build`() {
        // 32,000 strings (2 entries each) topped up to 65,533 entries: one more Log call overflows.
        val original = classWithPool(32_000, poolSize = MinifiedClassSkip.MAX_POOL_ENTRIES - 2)
        assertTrue(ClassReader(original).itemCount > MinifiedClassSkip.MAX_POOL_ENTRIES - 10)

        try {
            AsmTestHarness.transform(original) { LogClassVisitor(it, "a.b.big") }
            fail("baseline: expected the pool to overflow")
        } catch (expected: ClassTooLargeException) {
            // What fails the app build today.
        }

        val out = logLane(original)
        assertFalse(String(out, Charsets.ISO_8859_1).contains("BugseeLogAdapter"))
        AsmTestHarness.verify(out).assertOk()
        assertArrayEquals(methodNames(original), methodNames(out))
    }

    @Test
    fun `a class inside the headroom is skipped, one comfortably below it is not`() {
        val limit = MinifiedClassSkip.MAX_POOL_ENTRIES - MinifiedClassSkip.POOL_HEADROOM
        val near = classWithPool(limit / 2 + 200)
        val far = classWithPool(limit / 2 - 2000)
        assertTrue(ClassReader(near).itemCount > limit)
        assertTrue(ClassReader(far).itemCount < limit)
        assertTrue(shouldSkip(near))
        assertFalse(shouldSkip(far))
    }

    @Test
    fun `the skip is reported once per class run, however many lanes consult it`() {
        val messages = mutableListOf<String>()
        MinifiedClassSkip.reportSink = { messages.add(it) }
        try {
            val big = classWithPool(32_000, poolSize = MinifiedClassSkip.MAX_POOL_ENTRIES - 2)
            AsmTestHarness.transform(big) { writer ->
                repeat(3) { assertTrue(MinifiedClassSkip.shouldSkip(writer)) }
                writer
            }
            assertEquals(messages.toString(), 1, messages.size)
            assertTrue(messages[0], messages[0].contains("a.b.big was left UNINSTRUMENTED"))

            // A separate run of the same class reports again (the daemon outlives builds).
            AsmTestHarness.transform(big) { writer -> MinifiedClassSkip.shouldSkip(writer); writer }
            assertEquals(2, messages.size)
        } finally {
            MinifiedClassSkip.reportSink = null
        }
    }

    @Test
    fun `a class below the headroom is still instrumented`() {
        val out = logLane(classWithPool(1_000))
        assertTrue(String(out, Charsets.ISO_8859_1).contains("BugseeLogAdapter"))
    }

    private fun methodNames(bytes: ByteArray): Array<String> {
        val cn = org.objectweb.asm.tree.ClassNode()
        ClassReader(bytes).accept(cn, 0)
        return cn.methods.map { it.name }.toTypedArray()
    }
}
