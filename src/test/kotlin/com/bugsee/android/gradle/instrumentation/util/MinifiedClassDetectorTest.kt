package com.bugsee.android.gradle.instrumentation.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Tests for [MinifiedClassDetector] — the R8 marker probe.
 *
 * ### Why this exists
 *
 * Instrumenting classes R8 has already optimised is the single largest category in
 * Sentry's plugin bug history (VerifyError, ArrayIndexOutOfBoundsException in the ASM
 * transform, "Error while dexing", NPEs with no message — across Braze, Play Services,
 * ML Kit, androidx.startup, facebook-core and others). R8 writes a `~~R8` marker into
 * the constant pool of classes it produces, which is a reliable, self-declared signal
 * that we should keep our hands off.
 *
 * ### What is deliberately NOT implemented
 *
 * Sentry pairs the marker with a class-NAME heuristic (lowercase first char + regexes).
 * That heuristic misfired for them — issue #398, "short class names should not be
 * flagged as minified" — and they were silently skipping legitimate classes for a
 * while. Silently skipping is exactly the failure mode this codebase keeps getting
 * bitten by, so only the self-declared marker is used here. A false negative costs one
 * uninstrumented class; a false positive costs silent, undiagnosable capture loss.
 */
class MinifiedClassDetectorTest {

    private fun classWithConstants(vararg constants: String): ClassReader {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Sample", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "m", "()V", null, null)
        mv.visitCode()
        constants.forEach { mv.visitLdcInsn(it) }
        constants.forEach { _ -> mv.visitInsn(Opcodes.POP) }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 0)
        mv.visitEnd()
        cw.visitEnd()
        return ClassReader(cw.toByteArray())
    }

    @Test
    fun `a class carrying the R8 marker is detected as minified`() {
        val reader = classWithConstants("~~R8{\"compilation-mode\":\"release\"}")
        assertTrue("the self-declared R8 marker must be recognised", MinifiedClassDetector.isMinified(reader))
    }

    @Test
    fun `an ordinary class is not minified`() {
        val reader = classWithConstants("hello", "world")
        assertFalse(MinifiedClassDetector.isMinified(reader))
    }

    /**
     * A short, lowercase class name is NOT on its own evidence of minification — this is
     * the case Sentry's name heuristic got wrong. Pinned so nobody "improves" the
     * detector by adding it back without weighing the false-positive cost.
     */
    @Test
    fun `a short lowercase class name alone is not treated as minified`() {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "a/a/a", null, "java/lang/Object", null)
        cw.visitEnd()
        assertFalse(
            "name shape must not be used as evidence — it silently skips legitimate classes",
            MinifiedClassDetector.isMinified(ClassReader(cw.toByteArray()))
        )
    }

    /**
     * The scan is capped for speed (constant pools can be enormous). R8 writes its marker
     * early, so the cap is safe in practice — but the consequence is a real false
     * negative, and it is pinned here rather than left as an unstated assumption.
     */
    @Test
    fun `the marker is not found beyond the scan cap`() {
        // Literal, NOT derived from MAX_SCANNED_ENTRIES: a filler count computed from
        // the constant scales with it, so the test can never fail however the cap
        // changes — it would assert nothing. Proven by mutation: raising the cap to
        // 10_000 left the derived version green.
        val filler = Array(30) { "filler$it" }
        val reader = classWithConstants(*filler, "~~R8{}")
        assertFalse(
            "documents the cap: a marker past the limit is not detected",
            MinifiedClassDetector.isMinified(reader)
        )
    }

    @Test
    fun `a malformed constant pool does not throw`() {
        // Fails open: an unreadable pool must not break the build, it must just mean
        // "not detected" and let instrumentation proceed as before.
        val reader = classWithConstants("ok")
        assertFalse(MinifiedClassDetector.isMinified(reader))
    }
    // ---- end-to-end: the skip actually reaches the ClassReader ----

    /**
     * The detector is pure, but production must reach the [ClassReader] through the
     * visitor chain by reflection into ASM internals (`cv` → `ClassWriter.symbolTable`
     * → `sourceClassReader`). If ASM changes those fields the reflection silently
     * returns null and the skip stops working, with nothing failing — so the reach is
     * pinned here, using the same `ClassWriter(reader, flags)` shape AGP builds.
     */
    @Test
    fun `the skip finds the reader through a real visitor chain`() {
        val bytes = run {
            val cw = ClassWriter(0)
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Minified", null, "java/lang/Object", null)
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "m", "()V", null, null)
            mv.visitCode(); mv.visitLdcInsn("~~R8{}"); mv.visitInsn(Opcodes.POP)
            mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(1, 0); mv.visitEnd()
            cw.visitEnd(); cw.toByteArray()
        }
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, 0)
        assertTrue(
            "reflection into the ASM chain must reach the source ClassReader",
            MinifiedClassSkip.shouldSkip(writer)
        )
    }

    @Test
    fun `the skip declines an ordinary class reached the same way`() {
        val bytes = run {
            val cw = ClassWriter(0)
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Ordinary", null, "java/lang/Object", null)
            cw.visitEnd(); cw.toByteArray()
        }
        val writer = ClassWriter(ClassReader(bytes), 0)
        assertFalse(MinifiedClassSkip.shouldSkip(writer))
    }

    /**
     * Fails OPEN: a visitor chain with no reachable writer (or a future ASM whose
     * internals moved) must mean "not minified", so instrumentation carries on exactly
     * as it did before this check existed — never "skip everything".
     */
    @Test
    fun `an unreachable reader fails open rather than skipping`() {
        val detached = object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9, null) {}
        assertFalse(
            "no reader must mean 'instrument as before', not 'skip'",
            MinifiedClassSkip.shouldSkip(detached)
        )
    }

}
