package com.bugsee.android.gradle.instrumentation.util

import org.gradle.api.logging.Logging
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TryCatchBlockNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.Interpreter
import org.objectweb.asm.tree.analysis.Value

/**
 * Rewrites `NEW T` + `INVOKESPECIAL T.<init>` allocation pairs to another type, pairing
 * the two by DATA FLOW rather than by instruction order.
 *
 * Why not by order: the JVM verifier (JVMS 4.10.1.9) requires an `<init>` call to target
 * exactly the type of the uninitialized reference it is invoked on. javac emits
 * `NEW; DUP; args; <init>` strictly LIFO, but obfuscated / post-processed bytecode may keep
 * uninitialized references in locals and initialize them in a different order
 * (`NEW A; astore 1; NEW B; astore 2; aload 1; <init A>; aload 2; <init B>`). Remapping
 * "the next matching `<init>`" there yields `NEW Wrapper(A)` initialized by `<init> A`,
 * which fails class verification (`VerifyError: Call to wrong <init> method`).
 *
 * How: an [Analyzer] runs over the method with an interpreter whose values carry the
 * identity of the `NEW` that produced them. Copies (`DUP*`, `ALOAD`/`ASTORE`, swaps)
 * preserve that identity, so for every `<init>` the receiver's origin NEW is known exactly.
 * A NEW and its `<init>` call(s) are rewritten together, only when every `<init>` that
 * consumes the NEW's value targets the NEW's own type.
 *
 * Conservative by construction: if the analysis fails (analyzer exception), or an `<init>`
 * of a rewritable type sits in unreachable code, the WHOLE method is left unrewritten
 * (valid bytecode, merely not instrumented). `<init>` calls with no origin NEW —
 * `super(...)` / `this(...)` on `uninitializedThis` — are never touched.
 */
internal object NewInitPairing {

    /**
     * Rewrites provable pairs in [method] in place. [remap] returns the replacement
     * internal name for a rewritable type, or null. Returns the number of NEWs rewritten.
     */
    fun rewrite(ownerClass: String, method: MethodNode, remap: (String) -> String?): Int {
        val candidates = HashSet<TypeInsnNode>()
        val inits = ArrayList<MethodInsnNode>()
        var insn: AbstractInsnNode? = method.instructions.first
        while (insn != null) {
            if (insn is TypeInsnNode && insn.opcode == Opcodes.NEW && remap(insn.desc) != null) {
                candidates += insn
            } else if (insn is MethodInsnNode && insn.opcode == Opcodes.INVOKESPECIAL &&
                insn.name == "<init>" && remap(insn.owner) != null
            ) {
                inits += insn
            }
            insn = insn.next
        }
        if (candidates.isEmpty()) return 0

        // Recompute max stack/locals from the code itself: earlier visitors in AGP's chain (and
        // this one) may have added pushes or locals without updating the declared limits, and a
        // stale limit makes the analyzer throw. The writer recomputes maxs regardless.
        val analyzer = object : Analyzer<Origin>(OriginInterpreter(candidates)) {
            // ASM 9.7's analyzer treats LABEL/LINE/FRAME pseudo-instructions inside a try range
            // as throwing, which merges unrelated paths into the handler and loses the NEW's
            // identity. Only real instructions (opcode >= 0) can throw, so this is sound.
            override fun newControlFlowExceptionEdge(insnIndex: Int, tryCatchBlock: TryCatchBlockNode): Boolean =
                method.instructions.get(insnIndex).opcode >= 0
        }
        val frames = try {
            analyzer.analyzeAndComputeMaxs(ownerClass, method)
        } catch (e: Exception) {
            return skip(ownerClass, method, "data-flow analysis failed: $e")
        }

        val initsByNew = HashMap<TypeInsnNode, MutableList<MethodInsnNode>>()
        for (init in inits) {
            val frame = frames[method.instructions.indexOf(init)]
                ?: return skip(ownerClass, method, "rewritable <init> in unreachable code")
            val receiverIdx = frame.stackSize - 1 - Type.getArgumentTypes(init.desc).size
            if (receiverIdx < 0) return skip(ownerClass, method, "<init> receiver not on the stack")
            val origin = frame.getStack(receiverIdx)?.origin ?: continue // super()/this() call
            initsByNew.getOrPut(origin) { ArrayList() } += init
        }

        var rewritten = 0
        for (new in candidates) {
            val pairedInits = initsByNew[new] ?: continue
            if (pairedInits.any { it.owner != new.desc }) {
                skip(ownerClass, method, "NEW ${new.desc} initialized by a different type")
                continue
            }
            val target = remap(new.desc) ?: continue
            new.desc = target
            pairedInits.forEach { it.owner = target }
            rewritten++
        }
        return rewritten
    }

    private val LOGGER = Logging.getLogger(NewInitPairing::class.java)

    private fun skip(ownerClass: String, method: MethodNode, reason: String): Int {
        LOGGER.debug(
            "Bugsee: file I/O rewrite skipped for {}.{}{}: {}",
            ownerClass.replace('/', '.'), method.name, method.desc, reason,
        )
        return 0
    }

    /** Value that remembers which candidate NEW produced it (null for everything else). */
    private data class Origin(val slots: Int, val origin: TypeInsnNode?) : Value {
        override fun getSize(): Int = slots
    }

    private class OriginInterpreter(private val candidates: Set<TypeInsnNode>) :
        Interpreter<Origin>(Opcodes.ASM9) {

        private val one = Origin(1, null)
        private val two = Origin(2, null)
        private fun of(size: Int) = if (size == 2) two else one

        override fun newValue(type: Type?): Origin? = when {
            type == null -> one
            type.sort == Type.VOID -> null
            else -> of(type.size)
        }

        override fun newOperation(insn: AbstractInsnNode): Origin = when (insn.opcode) {
            Opcodes.NEW -> if (insn in candidates) Origin(1, insn as TypeInsnNode) else one
            Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1 -> two
            Opcodes.LDC -> {
                when (val cst = (insn as LdcInsnNode).cst) {
                    is Long, is Double -> two
                    is ConstantDynamic -> of(Type.getType(cst.descriptor).size)
                    else -> one
                }
            }
            Opcodes.GETSTATIC -> of(Type.getType((insn as FieldInsnNode).desc).size)
            else -> one
        }

        // Identity-preserving: DUP*, xLOAD, xSTORE, SWAP all route through here.
        override fun copyOperation(insn: AbstractInsnNode, value: Origin): Origin = value

        override fun unaryOperation(insn: AbstractInsnNode, value: Origin): Origin = when (insn.opcode) {
            Opcodes.LNEG, Opcodes.DNEG, Opcodes.I2L, Opcodes.I2D, Opcodes.L2D, Opcodes.F2L,
            Opcodes.F2D, Opcodes.D2L -> two
            Opcodes.GETFIELD -> of(Type.getType((insn as FieldInsnNode).desc).size)
            else -> one
        }

        override fun binaryOperation(insn: AbstractInsnNode, v1: Origin, v2: Origin): Origin =
            when (insn.opcode) {
                Opcodes.LALOAD, Opcodes.DALOAD, Opcodes.LADD, Opcodes.DADD, Opcodes.LSUB,
                Opcodes.DSUB, Opcodes.LMUL, Opcodes.DMUL, Opcodes.LDIV, Opcodes.DDIV,
                Opcodes.LREM, Opcodes.DREM, Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR,
                Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR -> two
                else -> one
            }

        override fun ternaryOperation(insn: AbstractInsnNode, v1: Origin, v2: Origin, v3: Origin): Origin = one

        override fun naryOperation(insn: AbstractInsnNode, values: MutableList<out Origin>): Origin {
            val desc = when (insn) {
                is MethodInsnNode -> insn.desc
                is InvokeDynamicInsnNode -> insn.desc
                else -> return one // MULTIANEWARRAY
            }
            return of(maxOf(Type.getReturnType(desc).size, 1))
        }

        override fun returnOperation(insn: AbstractInsnNode, value: Origin, expected: Origin) {}

        override fun merge(value1: Origin, value2: Origin): Origin =
            if (value1 == value2) value1 else one
    }
}
