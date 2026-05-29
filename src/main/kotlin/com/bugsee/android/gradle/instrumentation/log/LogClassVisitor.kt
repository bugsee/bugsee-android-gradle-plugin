package com.bugsee.android.gradle.instrumentation.log

import com.bugsee.android.gradle.instrumentation.util.CatchingMethodVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * ClassVisitor that delegates to [LogMethodVisitor] for all methods.
 */
internal class LogClassVisitor(
    nextClassVisitor: ClassVisitor,
    private val className: String,
) : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor? {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
        // Wrap so a failure in our transform (or AGP's frame recomputation)
        // is attributed to this class+method and re-thrown, never swallowed
        // into corrupt bytecode. See CatchingMethodVisitor.
        return CatchingMethodVisitor(Opcodes.ASM9, LogMethodVisitor(mv), className, name, descriptor)
    }
}

/**
 * MethodVisitor that redirects `android.util.Log` static calls to `BugseeLogAdapter`.
 *
 * BugseeLogAdapter has the exact same static method signatures as android.util.Log
 * (v, d, i, w, e, wtf — all overloads). Each BugseeLogAdapter method:
 * 1. Forwards the log entry to Bugsee's capture pipeline
 * 2. Calls the original android.util.Log method
 * 3. Returns the original return value
 *
 * So the instrumentation is a simple owner replacement — no stack manipulation needed.
 *
 * Handled methods and their descriptors:
 *   v(String, String)I
 *   v(String, String, Throwable)I
 *   d(String, String)I
 *   d(String, String, Throwable)I
 *   i(String, String)I
 *   i(String, String, Throwable)I
 *   w(String, String)I
 *   w(String, String, Throwable)I
 *   w(String, Throwable)I
 *   e(String, String)I
 *   e(String, String, Throwable)I
 *   wtf(String, String)I
 *   wtf(String, Throwable)I
 *   wtf(String, String, Throwable)I
 *   println(int, String, String)I
 */
private class LogMethodVisitor(
    methodVisitor: MethodVisitor
) : MethodVisitor(Opcodes.ASM9, methodVisitor) {

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        if (opcode == Opcodes.INVOKESTATIC
            && owner == ANDROID_LOG_CLASS
            && name != null
            && descriptor != null
            && isRedirectableMethod(name, descriptor)
        ) {
            // Redirect: same method name, same descriptor, different owner
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                BUGSEE_LOG_ADAPTER_CLASS,
                name,
                descriptor,
                false
            )
        } else {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }
    }

    companion object {
        private const val ANDROID_LOG_CLASS = "android/util/Log"
        private const val BUGSEE_LOG_ADAPTER_CLASS = "com/bugsee/library/adapters/BugseeLogAdapter"

        private const val DESC_TAG_MSG = "(Ljava/lang/String;Ljava/lang/String;)I"
        private const val DESC_TAG_MSG_TR = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I"
        private const val DESC_TAG_TR = "(Ljava/lang/String;Ljava/lang/Throwable;)I"
        private const val DESC_PRINTLN = "(ILjava/lang/String;Ljava/lang/String;)I"

        private val REDIRECTABLE_METHODS = setOf(
            // v
            "v" to DESC_TAG_MSG,
            "v" to DESC_TAG_MSG_TR,
            // d
            "d" to DESC_TAG_MSG,
            "d" to DESC_TAG_MSG_TR,
            // i
            "i" to DESC_TAG_MSG,
            "i" to DESC_TAG_MSG_TR,
            // w
            "w" to DESC_TAG_MSG,
            "w" to DESC_TAG_MSG_TR,
            "w" to DESC_TAG_TR,
            // e
            "e" to DESC_TAG_MSG,
            "e" to DESC_TAG_MSG_TR,
            // wtf
            "wtf" to DESC_TAG_MSG,
            "wtf" to DESC_TAG_TR,
            "wtf" to DESC_TAG_MSG_TR,
            // println
            "println" to DESC_PRINTLN,
        )

        private fun isRedirectableMethod(name: String, descriptor: String): Boolean {
            return (name to descriptor) in REDIRECTABLE_METHODS
        }
    }
}
