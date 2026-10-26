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
 *  2. prove the buffered result can be written: a scratch `ClassWriter` with the SAME
 *     compute flags as the real one (AGP: `COMPUTE_MAXS` for class files older than Java 6,
 *     `COMPUTE_FRAMES` otherwise), `toByteArray` included;
 *  3. only then replay it into the real writer — or, if anything above failed, replay the
 *     recorded INPUT instead. The real writer sees exactly one complete method either way.
 *
 * ### What actually triggers it
 *
 * The realistic trigger is `MethodTooLargeException`: a huge (typically obfuscator-produced)
 * method that is valid as is but exceeds 65,535 bytes once instrumented; ASM raises it from
 * `toByteArray`, outside the visitor chain. Frame-computation failures that exist only for the
 * transformed form are rarer on AGP, whose writer resolves unknown classes leniently
 * (`java/lang/Object`), so an unresolvable hierarchy does NOT fail a real build. The unit
 * tests simulate such failures artificially and say so.
 *
 * ### Known limits
 *
 *  - The fallback replays the method THROUGH the real writer, so its frames are recomputed
 *    once more. A method whose ORIGINAL form already defeats frame computation therefore still
 *    fails with the attributed [InstrumentationException]: the stream-through "copy" fast path
 *    is only available to a method a lane declined before buffering it.
 *  - The trial proves only THIS lane's output. A method within a few bytes of 65,535 can still
 *    overflow if a later lane in the chain adds code after the trial passed.
 *  - `ClassTooLargeException` (constant pool beyond 65,535 entries) still fails the build: the
 *    trial writer's pool is a copy taken at trial time, so it cannot see the real one filling up.
 *  - Version-50 classes with `JSR/RET`: AGP retries the whole class with `COMPUTE_MAXS` iff it
 *    sees the bare `IllegalArgumentException("JSR/RET are not supported with computeFrames
 *    option")`. That exception is therefore never wrapped or swallowed here ([JSR_RET_MESSAGE]),
 *    so AGP's retry still happens (and the retry's writer is `COMPUTE_MAXS`, which the trial
 *    mirrors).
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
    private val computeFlags: Int,
    private val sourceReader: ClassReader?,
) {

    /** True when [t] is the exception AGP keys its `COMPUTE_MAXS` retry on, for a version-50 class. */
    fun isAgpRetrySignal(t: Throwable): Boolean =
        (classVersion and 0xFFFF) == Opcodes.V1_6 && isJsrRetSignal(t)

    /**
     * Throws if [method] cannot be written with recomputed frames — the same condition under
     * which the real writer would throw for it.
     */
    fun trial(method: MethodNode) {
        val scratch = if (sourceReader != null) {
            TrialWriter(sourceReader, hierarchy, hierarchyLookup, computeFlags)
        } else {
            TrialWriter(hierarchy, hierarchyLookup, computeFlags)
        }
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

    /**
     * Seeded from the SAME reader the real writer was built from (when known), so original
     * constants keep their pool indices. That matters near the 64KB limit: ASM sizes `ldc` (2
     * bytes) vs `ldc_w` (3 bytes) by pool index, so a fresh pool would mis-measure the method and
     * let `MethodTooLargeException` slip past the trial (or revert a method that would fit).
     */
    private class TrialWriter : ClassWriter {
        private val real: ClassWriter
        private val lookup: Method?

        constructor(source: ClassReader, real: ClassWriter, lookup: Method?, flags: Int) : super(source, flags) {
            this.real = real
            this.lookup = lookup
        }

        constructor(real: ClassWriter, lookup: Method?, flags: Int) : super(flags) {
            this.real = real
            this.lookup = lookup
        }

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
                FrameFallbackContext(ownerInternalName, version, writer, findLookup(writer), computeFlagsOf(writer, version), reader)
            }
        } catch (t: Throwable) {
            null
        }

        /**
         * The flags the REAL writer computes with. Read from the writer itself so a retry with a
         * different mode (AGP's `COMPUTE_MAXS` retry) is mirrored; if the private field cannot be
         * read, fall back to AGP's rule by class version.
         */
        private fun computeFlagsOf(writer: ClassWriter, version: Int): Int {
            try {
                val f = ClassWriter::class.java.getDeclaredField("compute").apply { isAccessible = true }
                // ASM-internal: 4 = COMPUTE_ALL_FRAMES, 1 = COMPUTE_MAX_STACK_AND_LOCAL.
                return if (f.getInt(writer) == 4) ClassWriter.COMPUTE_FRAMES else ClassWriter.COMPUTE_MAXS
            } catch (t: Throwable) {
                return if ((version and 0xFFFF) < Opcodes.V1_6) ClassWriter.COMPUTE_MAXS else ClassWriter.COMPUTE_FRAMES
            }
        }

        const val JSR_RET_MESSAGE = "JSR/RET are not supported with computeFrames option"

        internal fun isJsrRetSignal(t: Throwable): Boolean =
            t is IllegalArgumentException && t.message == JSR_RET_MESSAGE

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

        /**
         * One warning per failing method. Deliberately NOT de-duplicated process-wide: the daemon
         * outlives builds, so a static set would silence later builds that still leave the method
         * uninstrumented, and Gradle's transform cache already means each class is processed once
         * per input.
         */
        internal fun report(
            className: String,
            methodName: String,
            descriptor: String,
            cause: Throwable,
            laneThrew: Boolean,
        ) {
            val dotted = className.replace('/', '.')
            val prefix = dotted.substringBeforeLast('.', missingDelimiterValue = dotted)
            val reason = (if (laneThrew) {
                "the instrumentation itself failed on it"
            } else {
                "its instrumented bytecode could not be written"
            }) + " (${cause.javaClass.simpleName}: ${cause.message})"
            val alternative = if (prefix != dotted) {
                " or, for an obfuscated library whose class names are short, its whole package " +
                    "`excludes.add(\"$prefix\")`"
            } else {
                ""
            }
            val message = "Bugsee: $dotted.$methodName$descriptor was left UNINSTRUMENTED: $reason. " +
                "The build continues; Bugsee captures nothing in that method. To stop instrumenting " +
                "it deliberately, exclude the class: `bugsee { instrumentation { excludes.add(\"$dotted\") } }`" +
                "$alternative. Note that an exclude disables ALL Bugsee instrumentation for what it matches."
            val sink = reportSink
            if (sink != null) {
                sink(message)
            } else {
                LOGGER.warn(message)
                // The stack trace is what makes a plugin defect reportable; keep the warning short.
                LOGGER.info("Bugsee: cause of the fallback for $dotted.$methodName$descriptor", cause)
            }
        }

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
        var laneThrew = false
        var dropped = false
        try {
            laneThrew = true
            val lane = build(out)
            if (lane == null) {
                dropped = true
            } else {
                accept(lane)
                laneThrew = false
                if (!FrameFallbackContext.sameCode(this, out)) {
                    context.trial(out)
                }
            }
        } catch (t: Throwable) {
            if (!FrameFallbackContext.isRecoverable(t) || context.isAgpRetrySignal(t)) throw t
            failure = t
        }
        if (dropped) return
        val dest = target() ?: return
        if (failure == null) {
            try {
                out.accept(dest)
            } catch (t: Throwable) {
                throw attributed(t)
            }
        } else {
            FrameFallbackContext.report(className, methodName, methodDescriptor, unwrap(failure), laneThrew)
            try {
                accept(dest)
            } catch (t: Throwable) {
                // The ORIGINAL method fails too (see "Known limits"): nothing left to fall back to.
                if (!context.isAgpRetrySignal(t)) t.addSuppressed(failure)
                throw attributed(t)
            }
        }
    }

    /** Keeps AGP's retry signal bare; attributes everything else. */
    private fun attributed(t: Throwable): Throwable = when {
        t is InstrumentationException || context.isAgpRetrySignal(t) -> t
        else -> InstrumentationException(className, methodName, methodDescriptor, t)
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
