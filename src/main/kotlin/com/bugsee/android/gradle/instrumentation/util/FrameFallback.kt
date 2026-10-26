package com.bugsee.android.gradle.instrumentation.util

import org.gradle.api.logging.Logging
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-method safety net for the buffering lanes (`MethodNode`-based): a method whose
 * transformed form cannot be written is left UNINSTRUMENTED instead of failing the app's
 * build.
 *
 * ### Why a trial, and why it must come before the real writer
 *
 * AGP's `ClassWriter` recomputes frames inside `visitMaxs`, i.e. while the method is being
 * streamed into it. By the time that throws, a half-written `MethodWriter` is already linked
 * into the class — there is no way to take it back. The only safe order is therefore:
 *
 *  1. record the lane's input, run the lane into a SCRATCH buffer;
 *  2. prove the buffered result can be written (a scratch `ClassWriter(COMPUTE_FRAMES)`
 *     whose `getCommonSuperClass` is the REAL writer's, so "unresolvable hierarchy" fails the
 *     trial exactly as it would fail the build);
 *  3. only then replay it into the real writer — or, if anything above failed, replay the
 *     recorded INPUT instead. The real writer sees exactly one complete method either way.
 *
 * ### Known limit
 *
 * The fallback replays the method THROUGH the real writer, so its frames are recomputed once
 * more. A method whose ORIGINAL form already defeats frame computation (e.g. `JSR/RET`)
 * therefore still fails, with the attributed [InstrumentationException]: the stream-through
 * "copy" fast path is only available to a method a lane declined before buffering it.
 * What this DOES cover is the case that matters in practice — the transformed code (or the
 * hierarchy lookup it adds, e.g. a Bugsee type merged with another at a branch join) being
 * what breaks frame computation.
 *
 * Streaming lanes (log, thread, okhttp, ...) deliberately do not use this: buffering every
 * method of every class just to have a fallback is the wrong trade for them, and they keep
 * the attributed re-throw of [CatchingMethodVisitor].
 */
internal class FrameFallbackContext private constructor(
    private val ownerInternalName: String,
    private val classVersion: Int,
    private val hierarchy: ClassWriter,
    private val hierarchyLookup: Method?,
) {

    /**
     * Throws if [method] cannot be written with recomputed frames — the same condition under
     * which the real writer would throw for it.
     */
    fun trial(method: MethodNode) {
        val scratch = TrialWriter(hierarchy, hierarchyLookup)
        scratch.visit(
            classVersion,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            ownerInternalName,
            null,
            "java/lang/Object",
            null,
        )
        method.accept(scratch)
        scratch.visitEnd()
        scratch.toByteArray()
    }

    private class TrialWriter(
        private val real: ClassWriter,
        private val lookup: Method?,
    ) : ClassWriter(COMPUTE_FRAMES) {
        override fun getCommonSuperClass(type1: String, type2: String): String {
            if (lookup == null) return "java/lang/Object"
            try {
                return lookup.invoke(real, type1, type2) as String
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    companion object {
        private val LOGGER = Logging.getLogger("com.bugsee.android.gradle.instrumentation")
        private val reported: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Test hook: receives every message that would be logged. */
        @Volatile
        internal var reportSink: ((String) -> Unit)? = null

        /**
         * Builds the context from the writer at the end of [chain]. Null (= no fallback,
         * behave exactly as before) when the writer cannot be located: failing open here
         * only costs the safety net, whereas guessing would risk a wrong trial.
         */
        fun from(chain: ClassVisitor, ownerInternalName: String): FrameFallbackContext? = try {
            val writer = MinifiedClassSkip.findClassWriter(chain)
            if (writer == null) {
                null
            } else {
                val reader: ClassReader? = MinifiedClassSkip.findClassReader(chain)
                // Offset 6 is the major version; with minor 0 that equals ASM's V* constant.
                val version = reader?.readShort(6)?.toInt() ?: Opcodes.V11
                FrameFallbackContext(ownerInternalName, version, writer, findLookup(writer))
            }
        } catch (t: Throwable) {
            null
        }

        private fun findLookup(writer: ClassWriter): Method? {
            var c: Class<*>? = writer.javaClass
            while (c != null) {
                try {
                    return c.getDeclaredMethod("getCommonSuperClass", String::class.java, String::class.java)
                        .apply { isAccessible = true }
                } catch (e: NoSuchMethodException) {
                    c = c.superclass
                } catch (t: Throwable) {
                    return null
                }
            }
            return null
        }

        /**
         * Wraps a lane's per-method visitor so a failure falls back to the unmodified method.
         * [build] receives the sink the lane must write into and returns the lane's visitor
         * (or null if the lane drops the method). With a null [context] this is exactly
         * `build(target())`: no buffering, no behaviour change.
         */
        fun wrapMethod(
            context: FrameFallbackContext?,
            api: Int,
            className: String,
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
            target: () -> MethodVisitor?,
            build: (MethodVisitor) -> MethodVisitor?,
        ): MethodVisitor? {
            if (context == null) return target()?.let(build)
            return FallbackMethodVisitor(
                api, context, className, access, name, descriptor, signature, exceptions, target, build,
            )
        }

        /** True for the failures a trial/transform may legitimately swallow. */
        internal fun isRecoverable(t: Throwable): Boolean =
            t !is VirtualMachineError || t is StackOverflowError

        internal fun report(className: String, methodName: String, descriptor: String, cause: Throwable) {
            val dotted = className.replace('/', '.')
            if (!reported.add("$dotted.$methodName$descriptor")) return
            val prefix = dotted.substringBeforeLast('.', missingDelimiterValue = dotted)
            val message = "Bugsee: left $dotted.$methodName$descriptor UNINSTRUMENTED — its " +
                "transformed bytecode could not be written (${cause.javaClass.simpleName}: ${cause.message}). " +
                "The build continues and the app is unaffected apart from missing Bugsee " +
                "capture in that method. To silence this, exclude the class or its package " +
                "(for obfuscated libraries, whose class names are short, the package prefix is " +
                "the form to use): bugsee { instrumentation { excludes.add(\"$prefix\") } }"
            val sink = reportSink
            if (sink != null) sink(message) else LOGGER.warn(message)
        }

        /** Forgets what was already reported. Tests only. */
        internal fun resetReported() = reported.clear()

        /**
         * Cheap structural comparison used to skip the (frame-computing) trial for methods
         * the lane did not change. Conservative: anything not compared is treated as a
         * difference only if instruction count / opcode / payload differ, and a false
         * "same" merely means no trial — i.e. today's behaviour.
         */
        internal fun sameCode(a: MethodNode, b: MethodNode): Boolean {
            if (a.instructions.size() != b.instructions.size()) return false
            if (a.tryCatchBlocks.size != b.tryCatchBlocks.size) return false
            for (i in a.tryCatchBlocks.indices) {
                if (a.tryCatchBlocks[i].type != b.tryCatchBlocks[i].type) return false
            }
            var x: AbstractInsnNode? = a.instructions.first
            var y: AbstractInsnNode? = b.instructions.first
            while (x != null && y != null) {
                if (x.type != y.type || x.opcode != y.opcode) return false
                val same = when (x) {
                    is MethodInsnNode -> y is MethodInsnNode && x.owner == y.owner &&
                        x.name == y.name && x.desc == y.desc && x.itf == y.itf
                    is FieldInsnNode -> y is FieldInsnNode && x.owner == y.owner &&
                        x.name == y.name && x.desc == y.desc
                    is TypeInsnNode -> y is TypeInsnNode && x.desc == y.desc
                    is LdcInsnNode -> y is LdcInsnNode && x.cst == y.cst
                    is VarInsnNode -> y is VarInsnNode && x.`var` == y.`var`
                    is IntInsnNode -> y is IntInsnNode && x.operand == y.operand
                    is IincInsnNode -> y is IincInsnNode && x.`var` == y.`var` && x.incr == y.incr
                    is InvokeDynamicInsnNode -> y is InvokeDynamicInsnNode &&
                        x.name == y.name && x.desc == y.desc
                    else -> true
                }
                if (!same) return false
                x = x.next
                y = y.next
            }
            return x == null && y == null
        }
    }
}

/**
 * Records one method, runs the lane over the recording into a scratch buffer, proves that
 * buffer writable, then replays either it or the recording into the real writer. See
 * [FrameFallbackContext] for the reasoning and the known limit.
 */
internal class FallbackMethodVisitor(
    api: Int,
    private val context: FrameFallbackContext,
    private val className: String,
    private val methodAccess: Int,
    private val methodName: String,
    private val methodDescriptor: String,
    private val methodSignature: String?,
    private val methodExceptions: Array<out String>?,
    private val target: () -> MethodVisitor?,
    private val build: (MethodVisitor) -> MethodVisitor?,
) : MethodNode(api, methodAccess, methodName, methodDescriptor, methodSignature, methodExceptions) {

    override fun visitEnd() {
        super.visitEnd()
        val out = scratch()
        var failure: Throwable? = null
        var dropped = false
        try {
            val lane = build(out)
            if (lane == null) {
                dropped = true
            } else {
                accept(lane)
                if (!FrameFallbackContext.sameCode(this, out)) {
                    context.trial(out)
                }
            }
        } catch (t: Throwable) {
            if (!FrameFallbackContext.isRecoverable(t)) throw t
            failure = t
        }
        if (dropped) return
        val dest = target() ?: return
        if (failure == null) {
            try {
                out.accept(dest)
            } catch (t: InstrumentationException) {
                throw t
            } catch (t: Throwable) {
                throw InstrumentationException(className, methodName, methodDescriptor, t)
            }
        } else {
            FrameFallbackContext.report(className, methodName, methodDescriptor, unwrap(failure))
            try {
                accept(dest)
            } catch (t: Throwable) {
                // The ORIGINAL method fails too (see "Known limit"): nothing left to fall back to.
                t.addSuppressed(failure)
                if (t is InstrumentationException) throw t
                throw InstrumentationException(className, methodName, methodDescriptor, t)
            }
        }
    }

    private fun scratch() = MethodNode(
        api, methodAccess, methodName, methodDescriptor, methodSignature,
        methodExceptions,
    )

    private fun unwrap(t: Throwable): Throwable =
        if (t is InstrumentationException && t.cause != null) t.cause!! else t
}

/**
 * Class-level installer for a lane that buffers EVERY method anyway (operation dispatch):
 * puts a [FallbackMethodVisitor] in front of each method without touching the lane's own
 * code. The lane is built over a [HandoffClassVisitor] so that, when the recorded method is
 * finally run through it, its `super.visitMethod` returns the scratch sink instead of the
 * real writer's method.
 */
internal object FrameFallbackLane {

    fun install(
        next: ClassVisitor,
        className: String,
        laneFactory: (ClassVisitor) -> ClassVisitor,
    ): ClassVisitor {
        val context = FrameFallbackContext.from(next, className.replace('.', '/'))
            ?: return laneFactory(next)
        val handoff = HandoffClassVisitor(next)
        return FallbackClassVisitor(laneFactory(handoff), handoff, next, context, className)
    }

    private class HandoffClassVisitor(next: ClassVisitor) : ClassVisitor(Opcodes.ASM9, next) {
        var pending: MethodVisitor? = null

        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            val sink = pending
            if (sink != null) {
                // One hand-off per recorded method; anything the lane adds on its own
                // (synthetic methods) goes straight to the real writer.
                pending = null
                return sink
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions)
        }
    }

    private class FallbackClassVisitor(
        lane: ClassVisitor,
        private val handoff: HandoffClassVisitor,
        private val next: ClassVisitor,
        private val context: FrameFallbackContext,
        private val className: String,
    ) : ClassVisitor(Opcodes.ASM9, lane) {

        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            if (name == null || descriptor == null) return super.visitMethod(access, name, descriptor, signature, exceptions)
            return FallbackMethodVisitor(
                Opcodes.ASM9, context, className, access, name, descriptor, signature, exceptions,
                target = { next.visitMethod(access, name, descriptor, signature, exceptions) },
                build = { sink ->
                    handoff.pending = sink
                    try {
                        super@FallbackClassVisitor.visitMethod(access, name, descriptor, signature, exceptions)
                    } finally {
                        handoff.pending = null
                    }
                },
            )
        }
    }
}
