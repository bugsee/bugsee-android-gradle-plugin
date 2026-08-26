package com.bugsee.android.gradle.instrumentation.thread

import com.bugsee.android.gradle.instrumentation.util.CatchingMethodVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * ClassVisitor that targets the `run()V` method and injects
 * `BugseeThreadAdapter.registerThread()` as its first instruction.
 *
 * For DIRECT `HandlerThread` subclasses that don't override `run()`, a synthetic
 * `run()` method is generated that calls `registerThread()` then `super.run()`.
 * Indirect subclasses inherit the synthetic run() generated into (or the entry
 * injection applied to) their instrumented parent — generating another one here
 * would risk overriding a `final run()` declared by an intermediate class,
 * which is a load-time VerifyError (see [visitEnd]).
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
        // Only generate the synthetic run() when the IMMEDIATE superclass is
        // android.os.HandlerThread itself. Two reasons:
        //
        //  1. **Correctness (VerifyError).** For `class B extends A` where
        //     `A extends HandlerThread` declares `final void run()`, emitting
        //     `public void run()` into B overrides a final method — the JVM/ART
        //     rejects B at class-definition time with a VerifyError, crashing
        //     the host app even if B is never started. The factory's ClassData
        //     only exposes superclass NAMES, so finality of an intermediate
        //     run() cannot be checked here; restricting generation to direct
        //     HandlerThread subclasses sidesteps the hazard entirely
        //     (HandlerThread.run() is known to be non-final).
        //
        //  2. **No coverage loss in the ordinary case.** An intermediate user
        //     (or third-party-JAR) HandlerThread subclass is itself in scope of
        //     this instrumentation, so IT receives the synthetic run() (or an
        //     entry injection into its real run()); subclasses inherit it and
        //     registerThread() still fires. Only an intermediate class the
        //     transform never sees (android.*, or user-excluded) loses the
        //     registration — a benign degradation, versus a load-time crash.
        if (extendsHandlerThread && !hasRunMethod && superName == HANDLER_THREAD_INTERNAL_NAME) {
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

/** Internal name of the only superclass a synthetic run() is generated under. */
private const val HANDLER_THREAD_INTERNAL_NAME = "android/os/HandlerThread"
