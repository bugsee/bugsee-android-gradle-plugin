package com.bugsee.android.gradle.instrumentation.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class CatchingMethodVisitorTest {

    private fun wrap(delegate: MethodVisitor): CatchingMethodVisitor =
        CatchingMethodVisitor(
            Opcodes.ASM9,
            delegate,
            "com/example/Foo",
            "bar",
            "()V",
        )

    @Test
    fun attributesFailureToClassAndMethodAndWraps() {
        val boom = IllegalStateException("kaboom")
        val cmv = wrap(object : MethodVisitor(Opcodes.ASM9) {
            override fun visitInsn(opcode: Int) {
                throw boom
            }
        })

        try {
            cmv.visitInsn(Opcodes.RETURN)
            fail("expected InstrumentationException")
        } catch (e: InstrumentationException) {
            // Class is rendered dotted; method name + descriptor present.
            assertTrue(e.message!!.contains("com.example.Foo.bar()V"))
            // The actionable workaround is surfaced.
            assertTrue(e.message!!.contains("excludes.add(\"com.example.Foo\")"))
            // Original cause is preserved (not lost).
            assertSame(boom, e.cause)
        }
    }

    @Test
    fun catchesFailuresFromFrameComputationAtVisitMaxs() {
        // AGP's frame recomputation surfaces at visitMaxs; that path must
        // be attributed too.
        val cmv = wrap(object : MethodVisitor(Opcodes.ASM9) {
            override fun visitMaxs(maxStack: Int, maxLocals: Int) {
                throw RuntimeException("frames")
            }
        })
        try {
            cmv.visitMaxs(0, 0)
            fail("expected InstrumentationException")
        } catch (e: InstrumentationException) {
            assertTrue(e.message!!.contains("com.example.Foo.bar()V"))
        }
    }

    @Test
    fun doesNotDoubleWrapAlreadyAttributedException() {
        // An inner guard already produced an InstrumentationException for a
        // DIFFERENT class; an outer guard must propagate it unchanged so the
        // original (innermost) attribution is preserved.
        val inner = InstrumentationException(
            "com/example/Inner", "m", "()V", RuntimeException("x")
        )
        val cmv = wrap(object : MethodVisitor(Opcodes.ASM9) {
            override fun visitEnd() {
                throw inner
            }
        })
        try {
            cmv.visitEnd()
            fail("expected InstrumentationException")
        } catch (e: InstrumentationException) {
            assertSame(inner, e)
            assertTrue(e.message!!.contains("com.example.Inner.m"))
        }
    }

    @Test
    fun passesThroughWhenNoExceptionThrown() {
        val seen = mutableListOf<String>()
        val cmv = wrap(object : MethodVisitor(Opcodes.ASM9) {
            override fun visitInsn(opcode: Int) {
                seen.add("insn:$opcode")
            }

            override fun visitMaxs(maxStack: Int, maxLocals: Int) {
                seen.add("maxs:$maxStack,$maxLocals")
            }
        })
        cmv.visitInsn(Opcodes.NOP)
        cmv.visitMaxs(1, 2)
        assertEquals(listOf("insn:0", "maxs:1,2"), seen)
    }
}
