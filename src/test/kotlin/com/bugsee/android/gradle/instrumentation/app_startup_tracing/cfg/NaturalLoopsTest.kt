package com.bugsee.android.gradle.instrumentation.app_startup_tracing.cfg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.BitSet

/**
 * Standalone unit tests for [NaturalLoops] using hand-crafted CFG graphs.
 *
 * Exercises:
 *  - Back-edge detection: `(u, v)` is a back-edge iff `v ∈ dom(u)`.
 *  - Body computation (standard stack algorithm): start with `{header,
 *    back-edge-source}`, transitively add predecessors until fixpoint.
 *  - Multi-back-edge consolidation: multiple `continue`s targeting the
 *    same header deduplicate to one [NaturalLoop].
 *  - Top-level filter: a loop is top-level iff its header is not in any
 *    other loop's body.
 *  - Unreachable-node filter: edges out of unreachable nodes never count
 *    as back-edges.
 */
class NaturalLoopsTest {

    // ── back-edge detection / single loop ───────────────────────────

    @Test fun `simple loop — single back-edge produces single NaturalLoop`() {
        //  0 → 1 → 2 → 3
        //      ↑       │
        //      └───────┘  (back-edge 3 → 1)
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(
            intArrayOf(1),
            intArrayOf(2),
            intArrayOf(3),
            intArrayOf(1, 4),
            IntArray(0),
        ))
        val dom = Dominators.compute(cfg)

        val loops = NaturalLoops.findAll(cfg, dom)
        assertEquals(1, loops.size)

        val loop = loops[0]
        assertEquals(1, loop.header)
        assertTrue("header in body", loop.body.get(1))
        assertTrue("body includes 2", loop.body.get(2))
        assertTrue("body includes 3", loop.body.get(3))
        assertFalse("entry NOT in body", loop.body.get(0))
        assertFalse("exit NOT in body", loop.body.get(4))
    }

    // ── multiple back-edges with shared header ──────────────────────

    @Test fun `multiple back-edges to same header collapse into one loop`() {
        //          0
        //          │
        //          ▼
        //  ┌──────►1◄──────┐
        //  │       │        │
        //  │       ▼        │
        //  │       2        │
        //  │      / \       │
        //  │     /   \      │
        //  3────/     \────4     (3 and 4 both back-edge to 1)
        //                │
        //                ▼
        //                5
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(
            intArrayOf(1),         // 0 → 1
            intArrayOf(2),         // 1 → 2
            intArrayOf(3, 4),      // 2 → 3 or 4
            intArrayOf(1),         // 3 → 1 (back-edge)
            intArrayOf(1, 5),      // 4 → 1 (back-edge) or 5 (exit)
            IntArray(0),           // 5 exit
        ))
        val dom = Dominators.compute(cfg)

        val loops = NaturalLoops.findAll(cfg, dom)
        // Header is 1 (dominates both 3 and 4); one loop, two back-edges
        // folded into one body.
        assertEquals(1, loops.size)
        val loop = loops[0]
        assertEquals(1, loop.header)
        assertTrue(loop.body.get(1))
        assertTrue(loop.body.get(2))
        assertTrue(loop.body.get(3))
        assertTrue(loop.body.get(4))
        assertFalse(loop.body.get(5))
    }

    // ── nested loops ────────────────────────────────────────────────

    @Test fun `nested loops — two distinct headers, inner header NOT top-level`() {
        //  0 → 1 → 2 → 3 → 4 → 5
        //      ↑       ↑   │   │
        //      │       │   │   │
        //      │       └───┘   │  (inner: back-edge 4 → 3)
        //      └───────────────┘  (outer: back-edge 5 → 1)
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(
            intArrayOf(1),
            intArrayOf(2),
            intArrayOf(3),
            intArrayOf(4),
            intArrayOf(3, 5),      // 4 → 3 (inner back) or 5 (continue outer)
            intArrayOf(1, 6),      // 5 → 1 (outer back) or 6 (exit)
            IntArray(0),
        ))
        val dom = Dominators.compute(cfg)

        val loops = NaturalLoops.findAll(cfg, dom)
        assertEquals(2, loops.size)

        // Find the two loops by header.
        val byHeader = loops.associateBy { it.header }
        val outer = byHeader[1]!!
        val inner = byHeader[3]!!

        // Outer body should contain inner's header.
        assertTrue("outer body contains inner header 3", outer.body.get(3))
        // Inner body does NOT contain outer header 1.
        assertFalse("inner body does NOT contain outer header 1", inner.body.get(1))

        // Top-level filter: only outer survives.
        val topLevel = NaturalLoops.filterTopLevel(loops)
        assertEquals(1, topLevel.size)
        assertEquals(1, topLevel[0].header)
    }

    // ── two sibling loops (neither nested) ──────────────────────────

    @Test fun `two sibling loops — both top-level`() {
        //          ┌────────┐         ┌────────┐
        //          │        ▼         │        ▼
        //  0 ────► 1 ─────► 2 ──────► 3 ─────► 4 ─────► 5
        //          ▲                  ▲
        //          └── (back-edge 2 → 1)
        //                             └── (back-edge 4 → 3)
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(
            intArrayOf(1),
            intArrayOf(2),
            intArrayOf(1, 3),    // back to 1 (loop A) or continue to 3
            intArrayOf(4),
            intArrayOf(3, 5),    // back to 3 (loop B) or exit
            IntArray(0),
        ))
        val dom = Dominators.compute(cfg)

        val loops = NaturalLoops.findAll(cfg, dom)
        assertEquals(2, loops.size)

        val topLevel = NaturalLoops.filterTopLevel(loops)
        assertEquals("both sibling loops are top-level", 2, topLevel.size)

        val headers = topLevel.map { it.header }.toSet()
        assertEquals(setOf(1, 3), headers)
    }

    // ── self-loop ───────────────────────────────────────────────────

    @Test fun `self-loop — header is body`() {
        //  0 → 1 → 2, with 1 → 1 (self-loop)
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(
            intArrayOf(1),
            intArrayOf(1, 2),     // 1 → 1 (self) or 1 → 2 (exit)
            IntArray(0),
        ))
        val dom = Dominators.compute(cfg)

        val loops = NaturalLoops.findAll(cfg, dom)
        assertEquals(1, loops.size)

        val loop = loops[0]
        assertEquals(1, loop.header)
        assertTrue(loop.body.get(1))
        assertEquals("self-loop body is the header alone", 1, loop.body.cardinality())
    }

    // ── no loops at all ─────────────────────────────────────────────

    @Test fun `acyclic graph — no loops`() {
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

        assertEquals(0, NaturalLoops.findAll(cfg, dom).size)
        assertEquals(0, NaturalLoops.filterTopLevel(NaturalLoops.findAll(cfg, dom)).size)
    }

    // ── unreachable-source back-edges are filtered ──────────────────

    @Test fun `back-edge from unreachable node is not detected`() {
        //  0 → 1 → 2          (reachable chain, no loops)
        //  3 → 1              (unreachable node 3 jumps to 1 — looks like a back-edge
        //                      but the source has no path from entry)
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(
            intArrayOf(1),
            intArrayOf(2),
            IntArray(0),
            intArrayOf(1),  // unreachable from entry
        ))
        val dom = Dominators.compute(cfg)
        assertTrue("node 3 unreachable", dom[3].isEmpty)

        val loops = NaturalLoops.findAll(cfg, dom)
        assertEquals("no loops — back-edge from unreachable 3 is filtered out",
            0, loops.size)
    }

    // ── empty / trivial ─────────────────────────────────────────────

    @Test fun `single-node graph has no loops`() {
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(IntArray(0)))
        val dom = Dominators.compute(cfg)
        assertEquals(0, NaturalLoops.findAll(cfg, dom).size)
    }

    @Test fun `filterTopLevel on empty input returns empty`() {
        assertEquals(0, NaturalLoops.filterTopLevel(emptyList()).size)
    }

    @Test fun `filterTopLevel on single loop returns it`() {
        val onlyLoop = NaturalLoop(header = 5, body = BitSet().apply { set(5) })
        val filtered = NaturalLoops.filterTopLevel(listOf(onlyLoop))
        assertEquals(1, filtered.size)
        assertEquals(5, filtered[0].header)
    }

    // ── irreducible CFG ─────────────────────────────────────────────

    @Test fun `irreducible region — multi-entry SCC produces zero natural loops`() {
        // Classic irreducible CFG: two distinct entries (1 and 2) into a
        // strongly-connected region. Edges:
        //   0 → 1, 0 → 2, 1 → 2, 2 → 1
        // Neither 1 nor 2 dominates the other (each is reachable from
        // entry 0 through the other), so neither edge in the SCC has
        // its target dominating the source — no back-edge satisfies the
        // natural-loop dominance predicate. NaturalLoops.findAll must
        // return an empty list rather than mis-identifying one of the
        // SCC edges as a back-edge.
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(
            intArrayOf(1, 2),
            intArrayOf(2),
            intArrayOf(1),
        ))
        val dom = Dominators.compute(cfg)
        // Sanity-check the dominator computation: neither node 1 nor 2
        // dominates the other (each has both 0 and itself, no overlap
        // with the other in dom set).
        assertFalse("node 1 does NOT dominate node 2 (multi-entry SCC)", dom[2].get(1))
        assertFalse("node 2 does NOT dominate node 1 (multi-entry SCC)", dom[1].get(2))

        // Loop detection: zero natural loops.
        val loops = NaturalLoops.findAll(cfg, dom)
        assertEquals("irreducible SCCs yield zero natural loops", 0, loops.size)
    }

    // ── TABLESWITCH-shape multi-successor ───────────────────────────

    @Test fun `node with 5+ successors and back-edge — loop detected normally`() {
        // A switch statement compiles to a multi-successor node. The
        // back-edge logic must handle a header with >2 successors
        // without error. Layout:
        //
        //          0
        //          ▼
        //          1 (multi-successor: 2, 3, 4, 5, 6)
        //         /│\ \ \
        //        2 3 4 5 6
        //         \ │ / / /
        //          ▼▼▼▼▼
        //          7
        //          │
        //          ▼
        //          1   (back-edge 7 → 1, body is {1..7})
        val cfg = MethodCfg.fromSuccessorsForTest(arrayOf(
            intArrayOf(1),
            intArrayOf(2, 3, 4, 5, 6),       // multi-successor (table switch)
            intArrayOf(7),
            intArrayOf(7),
            intArrayOf(7),
            intArrayOf(7),
            intArrayOf(7),
            intArrayOf(1, 8),                  // back-edge 7 → 1 or exit
            IntArray(0),
        ))
        val dom = Dominators.compute(cfg)

        val loops = NaturalLoops.findAll(cfg, dom)
        assertEquals("exactly one loop detected for TABLESWITCH-shape header",
            1, loops.size)
        val loop = loops[0]
        assertEquals(1, loop.header)
        // Body includes all 5 case branches and the join.
        for (i in 1..7) {
            assertTrue("node $i must be in loop body", loop.body.get(i))
        }
        // Exit is NOT in body.
        assertFalse(loop.body.get(8))
    }
}
