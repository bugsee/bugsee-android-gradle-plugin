package com.bugsee.android.gradle.instrumentation.thread

import com.bugsee.android.gradle.instrumentation.util.CatchingMethodVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * ClassVisitor that targets the `run()V` method and injects
 * `BugseeThreadAdapter.registerThread()` as its first instruction.
 *
 * For `HandlerThread` subclasses that don't override `run()`, a synthetic
 * `run()` method is generated that calls `registerThread()` then `super.run()`.
 *
 * Only applied to classes that implement `Runnable` or extend `Thread`
 * (filtering is done in [ThreadClassVisitorFactory.isInstrumentable]).
 */
internal class ThreadClassVisitor(
    nextClassVisitor: ClassVisitor,
    private val extendsHandlerThread: Boolean,
    private val className: String,
) : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {

    private var superName: String? = null
    private var hasRunMethod = false

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        this.superName = superName
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor? {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
        if (name == "run" && descriptor == "()V") {
            hasRunMethod = true
            // Wrap so a failure in our transform (or AGP's frame
            // recomputation) is attributed to this class+method and
            // re-thrown, never swallowed into corrupt bytecode. See
            // CatchingMethodVisitor. The non-instrumented pass-through
            // below returns the raw delegate, unwrapped.
            return CatchingMethodVisitor(Opcodes.ASM9, ThreadRunMethodVisitor(mv), className, name, descriptor)
        }
        return mv
    }

    override fun visitEnd() {
        if (extendsHandlerThread && !hasRunMethod) {
            generateSyntheticRun()
        }
        super.visitEnd()
    }

    /**
     * Generates a synthetic `run()` method equivalent to:
     * ```java
     * public void run() {
     *     BugseeThreadAdapter.registerThread();
     *     super.run();
     * }
     * ```
     */
    private fun generateSyntheticRun() {
        val delegate = cv.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null) ?: return
        // Wrap so a failure emitting the synthetic run() (or AGP's frame
        // recomputation in visitMaxs) is attributed to this class+method
        // and re-thrown, never swallowed into corrupt bytecode. See
        // CatchingMethodVisitor.
        val mv = CatchingMethodVisitor(Opcodes.ASM9, delegate, className, "run", "()V")
        mv.visitCode()
        // BugseeThreadAdapter.registerThread()
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            BUGSEE_THREAD_ADAPTER_CLASS,
            "registerThread",
            "()V",
            false
        )
        // super.run()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            superName,
            "run",
            "()V",
            false
        )
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
    }
}

/**
 * MethodVisitor that injects `BugseeThreadAdapter.registerThread()` as the
 * very first instruction of the `run()V` method.
 *
 * `visitCode()` is called once before any bytecode instructions, making it
 * the ideal injection point. The injected call is a single static void
 * method with no arguments — no stack or local variable effects.
 */
private class ThreadRunMethodVisitor(
    methodVisitor: MethodVisitor
) : MethodVisitor(Opcodes.ASM9, methodVisitor) {

    override fun visitCode() {
        super.visitCode()
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            BUGSEE_THREAD_ADAPTER_CLASS,
            "registerThread",
            "()V",
            false
        )
    }
}

private const val BUGSEE_THREAD_ADAPTER_CLASS =
    "com/bugsee/library/adapters/BugseeThreadAdapter"
