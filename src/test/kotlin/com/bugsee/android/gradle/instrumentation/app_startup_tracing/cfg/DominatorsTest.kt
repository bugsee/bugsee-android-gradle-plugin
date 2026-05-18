package com.bugsee.android.gradle.instrumentation.app_startup_tracing.cfg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.BitSet

/**
 * Standalone unit tests for [Dominators] using hand-crafted CFG graphs.
 *
 * The dominator computation is exercised end-to-end via the natural-loop
 * detection in [com.bugsee.android.gradle.instrumentation.app_startup_tracing.DetailedTierTransformTest],
 * but that path mixes Analyzer-driven CFG construction, body computation,
 * and bytecode rewriting, which makes failures hard to localize. These
 * tests pin the algorithm to its formal contract:
 *
 *  - `dom(0) = {0}`
 *  - `dom(n) = {n} ∪ (⋂ over preds p of n: dom(p))`
 *  - Unreachable nodes (no path from entry node 0) get an empty
 *    dominator set so [NaturalLoops]' `dom[u].get(0)` reachability
 *    filter can correctly exclude them.
 */
class DominatorsTest {

    // ── trivial shapes ──────────────────────────────────────────────

    @Test fun `single-node graph has dom_0 = {0}`() {
        val dom = Dominators.compute(MethodCfg.fromSuccessorsForTest(arrayOf(IntArray(0))))
        assertEquals(1, dom.size)
        assertEquals(bits(0), dom[0])
    }

    @Test fun `linear chain — each node dominates itself plus all predecessors`() {
        //  0 → 1 → 2 → 3
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(
            intArrayOf(1),
            intArrayOf(2),
            intArrayOf(3),
            IntArray(0),
        ))
        val dom = Dominators.compute(cfg)

        assertEquals(bits(0), dom[0])
        assertEquals(bits(0, 1), dom[1])
        assertEquals(bits(0, 1, 2), dom[2])
        assertEquals(bits(0, 1, 2, 3), dom[3])
    }

    // ── branch + join ───────────────────────────────────────────────

    @Test fun `diamond — join node dominated by entry only`() {
        //      0
        //     / \
        //    1   2
        //     \ /
        //      3
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(
            intArrayOf(1, 2),
            intArrayOf(3),
            intArrayOf(3),
            IntArray(0),
        ))
        val dom = Dominators.compute(cfg)

        assertEquals(bits(0), dom[0])
        assertEquals(bits(0, 1), dom[1])
        assertEquals(bits(0, 2), dom[2])
        // node 3 has predecessors {1, 2}; intersection of dom(1)={0,1} and
        // dom(2)={0,2} is {0}; plus self = {0, 3}.
        assertEquals(bits(0, 3), dom[3])
    }

    // ── loop ────────────────────────────────────────────────────────

    @Test fun `self loop — back-edge does not change dominator set`() {
        //  0 → 1 → 2, with 1 → 1 (self-loop)
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(
            intArrayOf(1),
            intArrayOf(1, 2),
            IntArray(0),
        ))
        val dom = Dominators.compute(cfg)

        assertEquals(bits(0), dom[0])
        assertEquals(bits(0, 1), dom[1])
        assertEquals(bits(0, 1, 2), dom[2])
    }

    @Test fun `simple loop — header dominates body and exit`() {
        //  0 → 1 → 2 → 3
        //          ↑   ↓
        //          └─── (back-edge from 3 to 1)
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(
            intArrayOf(1),         // entry → header
            intArrayOf(2),         // header → body
            intArrayOf(3),         // body → back-jump
            intArrayOf(1, 4),      // back-jump → header (loop) or exit
            IntArray(0),           // exit
        ))
        val dom = Dominators.compute(cfg)

        assertEquals(bits(0), dom[0])
        assertEquals(bits(0, 1), dom[1])
        assertEquals(bits(0, 1, 2), dom[2])
        assertEquals(bits(0, 1, 2, 3), dom[3])
        assertEquals(bits(0, 1, 2, 3, 4), dom[4])
    }

    // ── unreachable nodes ───────────────────────────────────────────

    @Test fun `unreachable node has empty dominator set`() {
        //  0 → 1            (2 is an island)
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(
            intArrayOf(1),
            IntArray(0),
            IntArray(0),
        ))
        val dom = Dominators.compute(cfg)

        assertEquals(bits(0), dom[0])
        assertEquals(bits(0, 1), dom[1])
        // Unreachable: empty (not "all bits set"). This is the contract
        // [NaturalLoops] depends on so dom[u].get(0) returns false for
        // unreachable nodes — without the explicit clear, the initial
        // "full set" would falsely report unreachable nodes as reachable.
        assertTrue("unreachable node 2 should have empty dom set",
            dom[2].isEmpty)
        assertFalse("dom[2].get(0) must be false for the reachability filter to work",
            dom[2].get(0))
    }

    @Test fun `unreachable cluster — chain of nodes all unreachable`() {
        //  0 → 1            (2 → 3 → 4 is a disconnected chain)
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(
            intArrayOf(1),
            IntArray(0),
            intArrayOf(3),
            intArrayOf(4),
            IntArray(0),
        ))
        val dom = Dominators.compute(cfg)

        // Reachable from entry.
        assertEquals(bits(0), dom[0])
        assertEquals(bits(0, 1), dom[1])

        // Unreachable cluster — node 2 has no predecessors so the worklist
        // clears it directly; node 3 / 4 have predecessors but they all
        // sit in the unreachable cluster, so their dom intersections never
        // include bit 0.
        assertTrue("node 2 unreachable", dom[2].isEmpty)
        assertFalse("dom[3].get(0) false", dom[3].get(0))
        assertFalse("dom[4].get(0) false", dom[4].get(0))
    }

    // ── edge cases ─────────────────────────────────────────────────

    @Test fun `empty CFG returns empty array`() {
        val dom = Dominators.compute(MethodCfg.fromSuccessorsForTest(emptyArray()))
        assertEquals(0, dom.size)
    }

    @Test fun `convergence — every reachable node sees bit 0 in dominator set`() {
        // 7-node graph with multiple branches and a join.
        //          0
        //         / \
        //        1   2
        //        |   |
        //        3   4
        //         \ /
        //          5
        //          |
        //          6
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(
            intArrayOf(1, 2),
            intArrayOf(3),
            intArrayOf(4),
            intArrayOf(5),
            intArrayOf(5),
            intArrayOf(6),
            IntArray(0),
        ))
        val dom = Dominators.compute(cfg)

        for (i in 0..6) {
            assertTrue("node $i should be reachable (bit 0 set in dom[$i])",
                dom[i].get(0))
            assertTrue("node $i should be in its own dom set",
                dom[i].get(i))
        }
        // 0 is the entry — every node sees 0.
        // 5 has preds {3, 4}; dom(3)={0,1,3} ∩ dom(4)={0,2,4} = {0}; ∪ self.
        assertEquals(bits(0, 5), dom[5])
        // 6 is downstream of 5, which dominates it.
        assertEquals(bits(0, 5, 6), dom[6])
    }

    // ── multi-successor (TABLESWITCH-shape) ─────────────────────────

    @Test fun `node with 5 successors — all immediately dominated by it`() {
        // TABLESWITCH compiles to a single instruction with N successors
        // (one per case label). The dominator algorithm must handle any
        // out-degree, not just binary branches. Hand-crafted CFG with a
        // 5-way fan-out — every case node should be immediately dominated
        // by node 0.
        //
        //          0
        //        / | | | \
        //       1  2 3 4  5
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(
            intArrayOf(1, 2, 3, 4, 5),
            IntArray(0),
            IntArray(0),
            IntArray(0),
            IntArray(0),
            IntArray(0),
        ))
        val dom = Dominators.compute(cfg)
        assertEquals(bits(0), dom[0])
        for (i in 1..5) {
            assertEquals("node $i must be dominated by {0, $i}", bits(0, i), dom[i])
        }
    }

    // ── large CFG performance smoke ─────────────────────────────────

    @Test(timeout = 2000L)
    fun `large linear chain of 1000 nodes completes within budget`() {
        // Linear chain: 0 → 1 → 2 → ... → 999. Each node dominates its
        // entire suffix; the iterative fixpoint must converge in
        // bounded time even on long chains. Test fails if the algorithm
        // accidentally regresses to quadratic behavior. The 2s budget
        // is generous on CI hardware — real wall time on a workstation
        // is well under 100ms.
        val n = 1000
        val successors = Array(n) { i ->
            if (i == n - 1) IntArray(0) else intArrayOf(i + 1)
        }
        val cfg = MethodCfg.fromSuccessorsForTest(successors)
        val dom = Dominators.compute(cfg)
        assertEquals(n, dom.size)
        // Spot-check correctness at boundaries: first, mid, last.
        assertTrue("entry dominates itself", dom[0].get(0))
        assertEquals(1, dom[0].cardinality())
        assertTrue("mid node dominated by entry", dom[500].get(0))
        assertEquals(501, dom[500].cardinality())  // {0..500}
        assertTrue("last node dominated by entry", dom[n - 1].get(0))
        assertEquals(n, dom[n - 1].cardinality())  // {0..999}
    }

    // ── helpers ─────────────────────────────────────────────────────

    private fun bits(vararg indices: Int): BitSet {
        val b = BitSet()
        for (i in indices) b.set(i)
        return b
    }
}
