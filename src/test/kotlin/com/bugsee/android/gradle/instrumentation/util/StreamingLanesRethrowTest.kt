package com.bugsee.android.gradle.instrumentation.util

import com.bugsee.android.gradle.instrumentation.compose_input.ComposeInputClassVisitor
import com.bugsee.android.gradle.instrumentation.extensions_init.ExtensionSpec
import com.bugsee.android.gradle.instrumentation.extensions_init.ExtensionsInitClassVisitor
import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import com.bugsee.android.gradle.instrumentation.http_engine.HttpEngineClassVisitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import java.io.File

/**
 * Pins the issue's "keep re-throw for the streaming lanes" decision, and that the buffering
 * lanes really are wired to the fallback (the factory-level wiring is otherwise untested:
 * the behavioural tests call `FrameFallbackLane.install` directly).
 *
 * The streaming lanes get a method their lane instruments (so the lane wraps it) that the
 * COMPUTE_FRAMES writer cannot frame (JSR/RET). The build must still fail, with the exception
 * AGP expects — never a silent half-instrumented class.
 */
class StreamingLanesRethrowTest {

    private val root = File("src/main/kotlin/com/bugsee/android/gradle/instrumentation")

    @Test
    fun `operation-dispatch factory installs the per-method fallback`() {
        val src = File(root, "operation_dispatch/OperationDispatchClassVisitorFactory.kt").readText()
        assertTrue(
            "OperationDispatchClassVisitorFactory must build its visitor via FrameFallbackLane.install",
            src.contains("FrameFallbackLane.install"),
        )
    }

    @Test
    fun `startup visitor wires the kind-candidate path to the fallback`() {
        val src = File(root, "app_startup_tracing/AppStartupTracingClassVisitor.kt").readText()
        assertTrue(src.contains("FrameFallbackContext.wrapMethod"))
    }

    private fun jsrClass(method: String, desc: String, returnsInt: Boolean): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V1_7, Opcodes.ACC_PUBLIC, "fixtures/Odd", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, desc, null, null)
        val sub = Label()
        mv.visitCode()
        mv.visitJumpInsn(Opcodes.JSR, sub)
        if (returnsInt) {
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitInsn(Opcodes.IRETURN)
        } else {
            mv.visitInsn(Opcodes.RETURN)
        }
        mv.visitLabel(sub)
        mv.visitVarInsn(Opcodes.ASTORE, 3)
        mv.visitVarInsn(Opcodes.RET, 3)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun assertStillFails(bytes: ByteArray, lane: (ClassWriter) -> ClassVisitor) {
        try {
            AsmTestHarness.transform(bytes) { lane(it) }
            fail("streaming lanes keep the re-throw")
        } catch (e: IllegalArgumentException) {
            // Bare, so AGP's version-50 COMPUTE_MAXS retry can still key on it.
            assertEquals("JSR/RET are not supported with computeFrames option", e.message)
        }
    }

    @Test
    fun `http-engine lane still re-throws`() {
        assertStillFails(jsrClass("m", "()V", false)) { HttpEngineClassVisitor(it, "fixtures.Odd") }
    }

    @Test
    fun `compose-input lane still re-throws`() {
        assertStillFails(jsrClass("dispatchTouchEvent", "(Landroid/view/MotionEvent;)Z", true)) {
            ComposeInputClassVisitor(it, "fixtures.Odd")
        }
    }

    @Test
    fun `extensions-init lane still re-throws`() {
        val spec = ExtensionSpec("com/bugsee/library/Facade", "register", "com.bugsee.library.XInitProvider")
        assertStillFails(jsrClass("initializeExtensions", "()V", false)) {
            ExtensionsInitClassVisitor(Opcodes.ASM9, it, listOf(spec), "fixtures.Odd")
        }
    }
}
