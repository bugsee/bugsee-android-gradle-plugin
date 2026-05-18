package com.bugsee.android.gradle.instrumentation.app_startup_tracing.cfg

import java.util.ArrayDeque
import java.util.BitSet

/**
 * One natural loop in a method's CFG.
 *
 * @property header instruction index of the loop's entry — by definition
 * the dominator of every body node, reachable on every iteration
 * @property body BitSet of instruction indices in the loop body
 * (includes the [header])
 */
internal data class NaturalLoop(
    val header: Int,
    val body: BitSet,
)

/**
 * Identifies natural loops from back-edges in a CFG.
 *
 * A **back-edge** is an edge `(n, h)` in the CFG where `h` dominates `n`
 * — i.e. the source's flow path always passed through the target. The
 * **natural loop** of that back-edge is the set of nodes from which `n`
 * is reachable without going through `h`. Formally: start with `{h, n}`
 * and grow by transitively adding predecessors of any node already in
 * the set (other than `h`), until fixpoint.
 *
 * Multiple back-edges can share a header (e.g. a loop with two
 * `continue`s); the body is computed once per unique header by seeding
 * the worklist with all back-edge sources for that header.
 *
 * **Top-level filter:** a loop `L` is top-level iff no other loop's body
 * contains `L`'s header. Equivalently: `L` is not nested inside any
 * other loop. We instrument only top-level loops so a 1000-iteration
 * outer loop containing a tight inner loop produces 1 loop event, not
 * 1 outer + 1000 inner.
 */
internal object NaturalLoops {

    /**
     * @return all natural loops found in [cfg], deduplicated by header.
     * Order is unspecified.
     */
    fun findAll(cfg: MethodCfg, dom: Array<BitSet>): List<NaturalLoop> {
        val backEdgesByHeader = HashMap<Int, MutableList<Int>>()
        for (u in 0 until cfg.instructionCount) {
            // Reachability filter: [Dominators.compute] narrows
            // dom[u] to {} for nodes not reachable from entry node 0
            // (the explicit clear in its worklist's empty-predecessors
            // branch). `dom[u].get(0)` is `true` iff bit 0 is set in
            // dom[u] — which is the case for the entry node itself
            // (initialized to {0}) and for every reachable node (since
            // node 0 dominates them by definition), but false for the
            // unreachable nodes that Dominators explicitly cleared.
            // Skipping unreachable nodes here keeps the back-edge
            // detection from looking at edges that ASM's Analyzer never
            // actually emitted anyway — defense in depth against future
            // changes that might populate `successors` for unreachable
            // code via a different mechanism.
            if (!dom[u].get(0)) continue

            for (v in cfg.successors[u]) {
                // (u, v) is a back-edge iff v dominates u.
                if (v in 0 until cfg.instructionCount && dom[u].get(v)) {
                    backEdgesByHeader.getOrPut(v) { ArrayList(2) }.add(u)
                }
            }
        }

        if (backEdgesByHeader.isEmpty()) return emptyList()

        val loops = ArrayList<NaturalLoop>(backEdgesByHeader.size)
        for ((header, sources) in backEdgesByHeader) {
            val body = naturalLoopBody(cfg, header, sources)
            loops.add(NaturalLoop(header, body))
        }
        return loops
    }

    /** Standard stack algorithm for natural-loop body computation. */
    private fun naturalLoopBody(cfg: MethodCfg, header: Int, sources: List<Int>): BitSet {
        val body = BitSet(cfg.instructionCount).apply { set(header) }
        val stack = ArrayDeque<Int>()
        for (s in sources) {
            if (s != header && !body.get(s)) {
                body.set(s)
                stack.push(s)
            }
        }
        while (stack.isNotEmpty()) {
            val d = stack.pop()
            for (p in cfg.predecessorsOf(d)) {
                if (!body.get(p)) {
                    body.set(p)
                    stack.push(p)
                }
            }
        }
        return body
    }

    /**
     * Filters [loops] to those that are top-level — i.e. their header
     * is not contained in any other loop's body.
     *
     * Single-pass O(n²) — fine at this scale; the typical method has
     * 0-3 loops.
     */
    fun filterTopLevel(loops: List<NaturalLoop>): List<NaturalLoop> {
        if (loops.size <= 1) return loops
        val out = ArrayList<NaturalLoop>(loops.size)
        for (l in loops) {
            var nested = false
            for (other in loops) {
                if (other === l) continue
                if (other.body.get(l.header)) {
                    nested = true
                    break
                }
            }
            if (!nested) out.add(l)
        }
        return out
    }
}
