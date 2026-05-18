package com.bugsee.android.gradle.instrumentation.app_startup_tracing.cfg

import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue

/**
 * Control-flow graph view of a [MethodNode].
 *
 * Built once via ASM's [Analyzer], which already walks the method to
 * compute frames for verification — we hijack its
 * [Analyzer.newControlFlowEdge] / [Analyzer.newControlFlowExceptionEdge]
 * hooks to record edges on the side without doing the analysis ourselves.
 *
 * **Exception edges are deliberately excluded** from the CFG used for
 * loop detection. This is the single most important filter in the
 * back-edge pipeline:
 *  - `synchronized` blocks compile to `MONITORENTER; try { body } catch
 *    (any) { MONITOREXIT; ATHROW } MONITOREXIT`. The exception edge from
 *    body instructions to the synthetic monitor-cleanup handler would,
 *    if treated as a normal edge, manufacture a back-edge whose target
 *    (the handler) dominates the throw site — yielding a spurious
 *    "loop" that's actually just `synchronized` cleanup.
 *  - User `try { … } catch { … }` similarly produces exception edges
 *    that aren't user-visible control flow.
 *  - Kotlin's coroutine state-machine dispatch is shaped to look like
 *    forward-only flow on the normal CFG and only spans back via
 *    state-restoration `GOTO`s — those are handled separately by the
 *    suspend-descriptor skip in [LoopAnalysis]; the exception-edge
 *    filter is the second line of defense.
 */
internal class MethodCfg private constructor(
    val instructionCount: Int,
    /** `successors[i]` is the list of normal-CFG successor indices of instruction `i`. */
    val successors: Array<IntArray>,
) {

    /**
     * Returns the predecessor list for instruction [i]. Computed lazily
     * the first time it's requested and cached for subsequent calls.
     */
    fun predecessorsOf(i: Int): IntArray {
        val cached = mPredecessorsCache[i]
        if (cached != null) return cached
        ensurePredecessorsBuilt()
        return mPredecessorsCache[i] ?: EMPTY
    }

    private val mPredecessorsCache: Array<IntArray?> = arrayOfNulls(instructionCount)
    private var mPredecessorsBuilt: Boolean = false

    private fun ensurePredecessorsBuilt() {
        if (mPredecessorsBuilt) return
        // Collect predecessor counts so each per-node array can be sized
        // exactly. Saves the dynamic-grow cost during construction.
        val counts = IntArray(instructionCount)
        for (u in 0 until instructionCount) {
            for (v in successors[u]) {
                if (v in 0 until instructionCount) counts[v]++
            }
        }
        val writeIdx = IntArray(instructionCount)
        for (v in 0 until instructionCount) {
            mPredecessorsCache[v] = if (counts[v] == 0) EMPTY else IntArray(counts[v])
        }
        for (u in 0 until instructionCount) {
            for (v in successors[u]) {
                if (v in 0 until instructionCount) {
                    val arr = mPredecessorsCache[v]!!
                    arr[writeIdx[v]++] = u
                }
            }
        }
        mPredecessorsBuilt = true
    }

    companion object {
        private val EMPTY = IntArray(0)

        /**
         * Test seam — wraps a hand-crafted successors table in a
         * [MethodCfg] without going through ASM's [Analyzer]. Lets the
         * analyzer-layer unit tests ([DominatorsTest], [NaturalLoopsTest])
         * exercise the algorithms on tiny synthetic graphs without
         * needing to synthesize matching bytecode. Caller owns the array
         * and must guarantee `successors[i]` is in `[0, successors.size)`
         * for all entries — out-of-range successors trigger
         * IndexOutOfBoundsException downstream.
         */
        internal fun fromSuccessorsForTest(successors: Array<IntArray>): MethodCfg {
            return MethodCfg(successors.size, successors)
        }

        /**
         * Builds the CFG for [methodNode] in the context of class
         * [ownerInternalName] (used by the Analyzer for type resolution).
         * Returns `null` if the method has no instructions (abstract /
         * native) — the caller is expected to skip such methods.
         */
        fun buildOrNull(ownerInternalName: String, methodNode: MethodNode): MethodCfg? {
            val n = methodNode.instructions.size()
            if (n == 0) return null

            // Per-source edge collectors. Using HashSet rather than
            // List to deduplicate non-consecutive duplicate edges (which
            // TABLESWITCH / LOOKUPSWITCH with overlapping case labels
            // can produce non-consecutively). The downstream predecessor
            // count arithmetic assumes each (u, v) pair is recorded at
            // most once.
            val edgeCollector = arrayOfNulls<LinkedHashSet<Int>>(n)

            val analyzer = object : Analyzer<BasicValue>(BasicInterpreter()) {
                override fun newControlFlowEdge(insn: Int, successor: Int) {
                    if (insn !in 0 until n || successor !in 0 until n) return
                    val set = edgeCollector[insn]
                        ?: LinkedHashSet<Int>(2).also { edgeCollector[insn] = it }
                    set.add(successor)
                }

                override fun newControlFlowExceptionEdge(insn: Int, successor: Int): Boolean {
                    // Deliberately not recorded: see KDoc on this class
                    // for why exception edges are excluded from the CFG
                    // used for loop detection. Returning false also tells
                    // the Analyzer this edge should NOT contribute to
                    // data-flow merging; we don't care about the analysis
                    // result either way (we only care about edge
                    // notifications), but returning false is the canonical
                    // "I'm just observing, don't widen the lattice" hint.
                    return false
                }
            }

            try {
                analyzer.analyze(ownerInternalName, methodNode)
            } catch (e: AnalyzerException) {
                // Pathologically malformed bytecode — the Analyzer can
                // throw on things like duplicate labels or unreachable
                // code without proper frames. Treat as "CFG unavailable"
                // and let the caller bail out of loop detection for this
                // method. Catch is narrow on purpose: VirtualMachineError
                // (OOM, StackOverflow), LinkageError, NPE on null
                // arguments, etc. are real plugin bugs we want to fail
                // loudly on rather than silently disable instrumentation
                // for one class.
                return null
            }

            val successors = Array(n) { idx ->
                val set = edgeCollector[idx]
                if (set == null || set.isEmpty()) {
                    EMPTY
                } else {
                    val arr = IntArray(set.size)
                    var w = 0
                    for (v in set) { arr[w++] = v }
                    arr
                }
            }

            return MethodCfg(n, successors)
        }
    }
}
