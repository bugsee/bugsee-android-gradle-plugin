package com.bugsee.android.gradle.instrumentation.app_startup_tracing.cfg

import java.util.BitSet

/**
 * Computes the dominator set for every node in a CFG via the classical
 * iterative worklist algorithm.
 *
 * For a graph with entry node 0:
 *  - `dom(0) = {0}`
 *  - `dom(n) = {n} ∪ (⋂ over preds p of n: dom(p))`
 *
 * Iterated to fixpoint. Stored as a [BitSet] per node for compact
 * set-intersection (`BitSet.and` is one method call on internal long
 * arrays — much faster than `Set<Int>` operations).
 *
 * Complexity is `O(V·E·d)` where `d` is the maximum dominator-set
 * cardinality (bounded by `V`). For init methods (a few hundred
 * instructions at most), the runtime is well under a millisecond. The
 * fancier Cooper-Harvey-Kennedy algorithm is faster asymptotically but
 * unnecessary at this scale.
 *
 * Unreachable nodes (no path from entry) get an **empty** dominator
 * set. The mathematically correct answer for the empty-predecessor
 * intersection would be the universal set, but for the loop analysis
 * pipeline downstream we need a value that lets [NaturalLoops] cleanly
 * reject those nodes via a `dom[u].get(0)` reachability check — empty
 * makes that test false; the universal set would make it tautologically
 * true and silently re-include unreachable code in back-edge detection.
 * The clear happens explicitly inside the worklist for any non-entry
 * node with no predecessors.
 */
internal object Dominators {

    /**
     * @return for each instruction index `i`, a BitSet containing all
     * indices that dominate `i`. The array length matches
     * `cfg.instructionCount`.
     */
    fun compute(cfg: MethodCfg): Array<BitSet> {
        val n = cfg.instructionCount
        if (n == 0) return emptyArray()

        // Initialize:
        //   dom[0] = {0}
        //   dom[i] = full set, for i != 0 (will be narrowed)
        // Starting with the full set is the standard initialization;
        // it ensures the first intersection of any predecessors'
        // dom-sets yields useful information.
        val full = BitSet(n).apply { set(0, n) }
        val dom = Array(n) { i ->
            if (i == 0) {
                BitSet(n).apply { set(0) }
            } else {
                full.clone() as BitSet
            }
        }

        var changed = true
        while (changed) {
            changed = false
            for (i in 1 until n) {
                val preds = cfg.predecessorsOf(i)
                if (preds.isEmpty()) {
                    // Unreachable (no predecessors, and i != 0). Narrow
                    // dom[i] to {} so the `dom[u].get(0)` reachability
                    // check in [NaturalLoops.findAll] correctly classifies
                    // this node as unreachable and skips its outgoing
                    // edges from back-edge detection. Without this clear,
                    // dom[i] would stay at the initial "all bits set"
                    // value (which includes bit 0), making the
                    // reachability filter a tautological no-op.
                    if (!dom[i].isEmpty) {
                        dom[i].clear()
                        changed = true
                    }
                    continue
                }
                // newDom = {i} ∪ (intersection of dom[p] for all p in preds)
                val newDom = dom[preds[0]].clone() as BitSet
                for (k in 1 until preds.size) {
                    newDom.and(dom[preds[k]])
                }
                newDom.set(i)
                if (newDom != dom[i]) {
                    dom[i] = newDom
                    changed = true
                }
            }
        }
        return dom
    }
}
