package com.bugsee.android.gradle.instrumentation.util

import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor

/**
 * Thrown when one of Bugsee's bytecode instrumentations fails on a
 * specific method. Carries the offending class + method so the failure
 * is actionable instead of an opaque ASM stack trace, and tells the
 * consumer how to unblock their build.
 */
internal class InstrumentationException(
    className: String,
    methodName: String?,
    methodDescriptor: String?,
    cause: Throwable,
) : RuntimeException(buildMessage(className, methodName, methodDescriptor), cause) {
    private companion object {
        fun buildMessage(className: String, methodName: String?, methodDescriptor: String?): String {
            val dotted = className.replace('/', '.')
            return "Bugsee: bytecode instrumentation failed for " +
                "$dotted.${methodName ?: "?"}${methodDescriptor ?: ""}. " +
                "This is a Bugsee Gradle plugin bug — please report it to Bugsee support with " +
                "this message and the stack trace below. To unblock your build in the meantime, " +
                "exclude the class from Bugsee instrumentation:\n" +
                "    bugsee {\n" +
                "        instrumentation {\n" +
                "            excludes.add(\"$dotted\")\n" +
                "        }\n" +
                "    }"
        }
    }
}

/**
 * Wraps an instrumenting [MethodVisitor] so that ANY failure during
 * bytecode transformation — including AGP's frame/max recomputation,
 * which ASM performs when `visitMaxs` is invoked — is ATTRIBUTED to the
 * exact class + method (via [InstrumentationException]) before it
 * propagates.
 *
 * Design rationale (mirrors Sentry's `CatchingMethodVisitor`): we
 * **re-throw**, we do NOT swallow. A method whose instrumentation threw
 * part-way is half-emitted, i.e. corrupt bytecode — which fails the
 * JVM/ART verifier at RUNTIME, a far worse and harder-to-diagnose failure
 * than a build-time error. Re-throwing fails the build deterministically
 * with a message naming the class, so the consumer can exclude it (see
 * [InstrumentationException]) while the underlying plugin bug is fixed.
 *
 * The per-`visitX` try/catch is effectively free when no exception is
 * thrown (the JVM's exception-table approach has zero steady-state cost),
 * so wrapping every instruction callback is cheap even on the broad
 * `InstrumentationScope.ALL` instrumentations.
 */
internal class CatchingMethodVisitor(
    apiVersion: Int,
    delegate: MethodVisitor,
    private val className: String,
    private val methodName: String?,
    private val methodDescriptor: String?,
) : MethodVisitor(apiVersion, delegate) {

    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: InstrumentationException) {
            // Already attributed by an inner guard — propagate as-is so we
            // don't bury the original class/method under a re-wrap.
            throw e
        } catch (t: Throwable) {
            throw InstrumentationException(className, methodName, methodDescriptor, t)
        }

    override fun visitCode() = guard { super.visitCode() }

    override fun visitInsn(opcode: Int) = guard { super.visitInsn(opcode) }

    override fun visitIntInsn(opcode: Int, operand: Int) =
        guard { super.visitIntInsn(opcode, operand) }

    override fun visitVarInsn(opcode: Int, varIndex: Int) =
        guard { super.visitVarInsn(opcode, varIndex) }

    override fun visitTypeInsn(opcode: Int, type: String?) =
        guard { super.visitTypeInsn(opcode, type) }

    override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) =
        guard { super.visitFieldInsn(opcode, owner, name, descriptor) }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean,
    ) = guard { super.visitMethodInsn(opcode, owner, name, descriptor, isInterface) }

    override fun visitInvokeDynamicInsn(
        name: String?,
        descriptor: String?,
        bootstrapMethodHandle: Handle?,
        vararg bootstrapMethodArguments: Any?,
    ) = guard {
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
    }

    override fun visitJumpInsn(opcode: Int, label: Label?) =
        guard { super.visitJumpInsn(opcode, label) }

    override fun visitLdcInsn(value: Any?) = guard { super.visitLdcInsn(value) }

    override fun visitIincInsn(varIndex: Int, increment: Int) =
        guard { super.visitIincInsn(varIndex, increment) }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label?, vararg labels: Label?) =
        guard { super.visitTableSwitchInsn(min, max, dflt, *labels) }

    override fun visitLookupSwitchInsn(dflt: Label?, keys: IntArray?, labels: Array<out Label>?) =
        guard { super.visitLookupSwitchInsn(dflt, keys, labels) }

    override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) =
        guard { super.visitMultiANewArrayInsn(descriptor, numDimensions) }

    override fun visitTryCatchBlock(start: Label?, end: Label?, handler: Label?, type: String?) =
        guard { super.visitTryCatchBlock(start, end, handler, type) }

    override fun visitLabel(label: Label?) = guard { super.visitLabel(label) }

    override fun visitFrame(
        type: Int,
        numLocal: Int,
        local: Array<out Any>?,
        numStack: Int,
        stack: Array<out Any>?,
    ) = guard { super.visitFrame(type, numLocal, local, numStack, stack) }

    override fun visitLineNumber(line: Int, start: Label?) =
        guard { super.visitLineNumber(line, start) }

    override fun visitLocalVariable(
        name: String?,
        descriptor: String?,
        signature: String?,
        start: Label?,
        end: Label?,
        index: Int,
    ) = guard { super.visitLocalVariable(name, descriptor, signature, start, end, index) }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) =
        guard { super.visitMaxs(maxStack, maxLocals) }

    override fun visitEnd() = guard { super.visitEnd() }
}
