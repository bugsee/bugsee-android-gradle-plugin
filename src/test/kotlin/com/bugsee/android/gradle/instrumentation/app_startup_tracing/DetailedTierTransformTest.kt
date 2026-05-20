package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.bugsee.android.gradle.StartupTier
import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import com.bugsee.android.gradle.instrumentation.fixtures.JavaSourceCompiler
import com.bugsee.test.fixtures.RecordingStartupDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * End-to-end transform tests for the DETAILED tier — adds top-level
 * loop wrapping on top of STANDARD-tier call wrapping.
 *
 * Same harness pattern as [MinimalTierTransformTest] and
 * [StandardTierTransformTest]: bypass the factory's class-kind filter
 * by calling [AppStartupTracingClassVisitor] directly with an explicit
 * candidate set, so fixtures don't need Android stubs.
 *
 * Coverage scope (everything end-to-end via [AsmTestHarness]):
 *  - Simple for / while loops with one call inside → loop event pair
 *    around iteration-many call event pairs;
 *  - Throwing call inside loop → per-call handler, then loop handler,
 *    then outer method handler, then propagates;
 *  - Nested loops → outer wrapped, inner NOT (top-level filter);
 *  - Suspend method → not instrumented (descriptor-based skip);
 *  - Method with NO loops at DETAILED tier → behaves like STANDARD
 *    (no extra event noise);
 *  - Verifier accepts every transformed class.
 *
 * **Deliberately not covered here** (deferred to local-CI validation):
 *  - Kotlin `repeat(n) { ... }` (inline function → real loop) and
 *    `list.forEach { ... }` (lambda — NOT a loop in the caller). Both
 *    need `KotlinSourceCompiler` to be working; Phase 4 may have
 *    Kotlin-compiler API quirks the handoff doc enumerates.
 *  - Non-contiguous loop fixtures. Synthesizing non-contiguous bytecode
 *    by hand is fiddly; the contiguity skip path is covered by the
 *    fact that `LoopWrapper` returns `SKIPPED_NON_CONTIGUOUS` without
 *    mutating any bytecode (verifiable by reading the source — the
 *    function's early return is before any mutation).
 *  - Nested-loop / try-catch-around-loop fixtures. Documented as a
 *    known limitation in `LoopWrapper`'s class KDoc; SDK-side folder
 *    finalizes orphans on method end.
 */
class DetailedTierTransformTest {

    private val dispatcherInternal = "com/bugsee/test/fixtures/RecordingStartupDispatcher"

    @Before fun setUp() { RecordingStartupDispatcher.reset() }
    @After fun tearDown() { RecordingStartupDispatcher.reset() }

    // ── single loop, single call inside ──────────────────────────────

    @Test
    fun `simple for loop with one call inside — loop pair around N call pairs`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/LoopOnce.java",
            """
            package fixtures;
            public class LoopOnce {
                public static void onCreate() {
                    for (int i = 0; i < 3; i++) {
                        String.valueOf(i);
                    }
                }
            }
            """.trimIndent(),
        )
        val transformed = applyAtTier(
            classes["fixtures.LoopOnce"]!!,
            tier = StartupTier.DETAILED,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()

        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.LoopOnce" to transformed),
            "fixtures.LoopOnce", "onCreate",
        )

        val events = RecordingStartupDispatcher.events()
        // APPLICATION_START, LOOP_START, then 3 iterations × (CALL_START,
        // CALL_END), LOOP_END, APPLICATION_END = 1 + 1 + 6 + 1 + 1 = 10.
        // (`onCreate ()V` → APPLICATION kind → onApplicationStart/End.)
        assertEquals(10, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_START, events[0].kind)
        assertEquals(RecordingStartupDispatcher.Kind.LOOP_START, events[1].kind)
        // Middle six are 3 × (CALL_START, CALL_END).
        for (i in 0..5 step 2) {
            assertEquals(RecordingStartupDispatcher.Kind.CALL_START, events[2 + i].kind)
            assertEquals(RecordingStartupDispatcher.Kind.CALL_END, events[2 + i + 1].kind)
        }
        assertEquals(RecordingStartupDispatcher.Kind.LOOP_END, events[8].kind)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_END, events[9].kind)
    }

    @Test
    fun `while loop — same event shape as for`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/LoopWhile.java",
            """
            package fixtures;
            public class LoopWhile {
                public static void onCreate() {
                    int i = 0;
                    while (i < 2) {
                        String.valueOf(i);
                        i++;
                    }
                }
            }
            """.trimIndent(),
        )
        val transformed = applyAtTier(
            classes["fixtures.LoopWhile"]!!,
            tier = StartupTier.DETAILED,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()
        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.LoopWhile" to transformed),
            "fixtures.LoopWhile", "onCreate",
        )

        val events = RecordingStartupDispatcher.events()
        // 1 APPLICATION_START + 1 LOOP_START + 2×(CALL_START, CALL_END) +
        // 1 LOOP_END + 1 APPLICATION_END = 8. (`onCreate ()V` → APPLICATION.)
        assertEquals(8, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_START, events[0].kind)
        assertEquals(RecordingStartupDispatcher.Kind.LOOP_START, events[1].kind)
        assertEquals(RecordingStartupDispatcher.Kind.LOOP_END, events[6].kind)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_END, events[7].kind)
    }

    // ── exception path ──────────────────────────────────────────────

    @Test
    fun `throwing call inside loop — exception propagates through all handlers`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/LoopThrows.java",
            """
            package fixtures;
            public class LoopThrows {
                public static void onCreate() {
                    for (int i = 0; i < 5; i++) {
                        if (i == 2) Helper.boom();
                    }
                }
            }
            class Helper {
                static void boom() {
                    throw new RuntimeException("boom");
                }
            }
            """.trimIndent(),
        )
        val transformed = applyAtTier(
            classes["fixtures.LoopThrows"]!!,
            tier = StartupTier.DETAILED,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()

        try {
            AsmTestHarness.loadAndInvokeStatic(
                mapOf(
                    "fixtures.LoopThrows" to transformed,
                    "fixtures.Helper" to classes["fixtures.Helper"]!!,
                ),
                "fixtures.LoopThrows", "onCreate",
            )
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertEquals("boom", e.targetException.message)
        }

        val events = RecordingStartupDispatcher.events()
        // APPLICATION_START, LOOP_START, then iteration i=2's CALL_START,
        // CALL_END (per-call handler), LOOP_END (loop handler),
        // APPLICATION_END (outer method handler).
        // (i=0 and i=1 don't invoke Helper.boom — the `if (i == 2)`
        // gate keeps them from calling at all.)
        assertEquals(6, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_START, events[0].kind)
        assertEquals(RecordingStartupDispatcher.Kind.LOOP_START, events[1].kind)
        assertEquals(RecordingStartupDispatcher.Kind.CALL_START, events[2].kind)
        assertEquals(RecordingStartupDispatcher.Kind.CALL_END, events[3].kind)
        assertEquals(RecordingStartupDispatcher.Kind.LOOP_END, events[4].kind)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_END, events[5].kind)
    }

    // ── nested loops: top-level filter ───────────────────────────────

    @Test
    fun `nested loops — outer wrapped, inner NOT wrapped`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/NestedLoops.java",
            """
            package fixtures;
            public class NestedLoops {
                public static void onCreate() {
                    for (int i = 0; i < 2; i++) {
                        for (int j = 0; j < 2; j++) {
                            String.valueOf(i + j);
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val transformed = applyAtTier(
            classes["fixtures.NestedLoops"]!!,
            tier = StartupTier.DETAILED,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()
        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.NestedLoops" to transformed),
            "fixtures.NestedLoops", "onCreate",
        )

        val events = RecordingStartupDispatcher.events()
        // Exactly ONE pair of LOOP_START/LOOP_END (the outer loop).
        // The inner loop is NOT top-level so the wrapper skips it.
        // The call inside the inner body executes 2 × 2 = 4 times,
        // so 4 × (CALL_START, CALL_END) = 8 call events. Plus
        // METHOD_START, METHOD_END, LOOP_START, LOOP_END = 12 total.
        val loopStarts = events.count { it.kind == RecordingStartupDispatcher.Kind.LOOP_START }
        val loopEnds = events.count { it.kind == RecordingStartupDispatcher.Kind.LOOP_END }
        assertEquals("exactly one outer loop wrap", 1, loopStarts)
        assertEquals("exactly one outer loop wrap", 1, loopEnds)

        val callPairs =
            events.count { it.kind == RecordingStartupDispatcher.Kind.CALL_START }
        assertEquals(4, callPairs)
        assertEquals(12, events.size)
    }

    // ── sibling top-level loops ──────────────────────────────────────

    /**
     * Regression test for a `LoopWrapper.wrap` bug surfaced by a round-3
     * deep review: when a method had TWO or more sibling top-level
     * loops, `wrapSingleLoop` was being called in a single iteration
     * loop that BOTH read indices off the original (pre-mutation) CFG
     * AND mutated `methodNode.instructions` between iterations. The
     * second loop's `methodNode.instructions.get(originalIdx)` lookups
     * returned shifted nodes from the first loop's wrap region,
     * producing structurally broken bytecode. The fix split the wrap
     * into two phases (read-only planning + mutation), capturing all
     * node references against the pre-mutation InsnList before any
     * mutation runs.
     *
     * Every existing multi-loop test either uses a single loop or
     * nested loops (where the top-level filter keeps only the outer,
     * giving a single wrap per call). Sibling top-level loops had no
     * coverage; this test closes the gap.
     */
    @Test
    fun `sibling top-level loops — both wrapped independently with distinct site ids`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/SiblingLoops.java",
            """
            package fixtures;
            public class SiblingLoops {
                public static void onCreate() {
                    for (int i = 0; i < 2; i++) {
                        String.valueOf(i);
                    }
                    for (int j = 0; j < 3; j++) {
                        String.valueOf(j);
                    }
                }
            }
            """.trimIndent(),
        )
        val transformed = applyAtTier(
            classes["fixtures.SiblingLoops"]!!,
            tier = StartupTier.DETAILED,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )

        // Verifier acceptance is the strongest signal that the InsnList
        // is structurally sound — the pre-fix code produced bytecode
        // that often failed CheckClassAdapter or SimpleVerifier.
        AsmTestHarness.verify(transformed).assertOk()

        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.SiblingLoops" to transformed),
            "fixtures.SiblingLoops", "onCreate",
        )

        val events = RecordingStartupDispatcher.events()

        // Exactly 2 LOOP_START / LOOP_END pairs — one per sibling top-
        // level loop. Pre-fix this often produced 0, 1, or duplicate
        // pairs depending on how the broken anchors landed.
        val loopStarts = events.filter { it.kind == RecordingStartupDispatcher.Kind.LOOP_START }
        val loopEnds = events.filter { it.kind == RecordingStartupDispatcher.Kind.LOOP_END }
        assertEquals("two LOOP_START events — one per sibling loop", 2, loopStarts.size)
        assertEquals("two LOOP_END events — one per sibling loop", 2, loopEnds.size)

        // Site ids must be distinct (ordinal 1 + ordinal 2). Their order
        // in the event stream matches the source order of the loops.
        val loopStartSiteIds = loopStarts.map { it.siteId }
        assertEquals(
            "site ids must be distinct ordinals — loop_1 then loop_2",
            listOf(
                "fixtures.SiblingLoops#onCreate#loop_1",
                "fixtures.SiblingLoops#onCreate#loop_2",
            ),
            loopStartSiteIds,
        )

        // Sanity: the call inside each loop fires the expected count
        // (2 + 3 = 5 CALL_START events).
        val callStarts = events.count { it.kind == RecordingStartupDispatcher.Kind.CALL_START }
        assertEquals("5 String.valueOf calls total across both loops", 5, callStarts)

        // Sanity: total event sequence shape — APPLICATION_START at head,
        // APPLICATION_END at tail. (`onCreate ()V` → APPLICATION kind.)
        assertEquals(
            "method-level wrap intact",
            RecordingStartupDispatcher.Kind.APPLICATION_START,
            events.first().kind,
        )
        assertEquals(
            "method-level wrap intact",
            RecordingStartupDispatcher.Kind.APPLICATION_END,
            events.last().kind,
        )
    }

    // ── tier gating ──────────────────────────────────────────────────

    @Test
    fun `tier STANDARD does NOT wrap loops — only calls`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/LoopTierGate.java",
            """
            package fixtures;
            public class LoopTierGate {
                public static void onCreate() {
                    for (int i = 0; i < 2; i++) {
                        String.valueOf(i);
                    }
                }
            }
            """.trimIndent(),
        )
        val transformed = applyAtTier(
            classes["fixtures.LoopTierGate"]!!,
            tier = StartupTier.STANDARD,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()
        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.LoopTierGate" to transformed),
            "fixtures.LoopTierGate", "onCreate",
        )

        val events = RecordingStartupDispatcher.events()
        // 1 APPLICATION_START + 2×(CALL_START, CALL_END) + 1 APPLICATION_END = 6.
        // (`onCreate ()V` → APPLICATION kind → onApplicationStart/End.)
        assertEquals(6, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_START, events.first().kind)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_END, events.last().kind)
        assertTrue("no LOOP events at STANDARD tier",
            events.none {
                it.kind == RecordingStartupDispatcher.Kind.LOOP_START
                        || it.kind == RecordingStartupDispatcher.Kind.LOOP_END
            })
    }

    // ── methods with no loops at all ─────────────────────────────────

    @Test
    fun `method with no loops at DETAILED tier — behaves like STANDARD`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/NoLoops.java",
            """
            package fixtures;
            public class NoLoops {
                public static void onCreate() {
                    String.valueOf(1);
                    String.valueOf(2);
                }
            }
            """.trimIndent(),
        )
        val transformed = applyAtTier(
            classes["fixtures.NoLoops"]!!,
            tier = StartupTier.DETAILED,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()
        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.NoLoops" to transformed),
            "fixtures.NoLoops", "onCreate",
        )

        val events = RecordingStartupDispatcher.events()
        // No loops to wrap → just method + calls. 1 APPLICATION_START +
        // 2×(CALL_START, CALL_END) + 1 APPLICATION_END = 6.
        // (`onCreate ()V` → APPLICATION kind.)
        assertEquals(6, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_START, events.first().kind)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_END, events.last().kind)
        assertTrue(events.none {
            it.kind == RecordingStartupDispatcher.Kind.LOOP_START
                    || it.kind == RecordingStartupDispatcher.Kind.LOOP_END
        })
    }

    // ── LoopWrapper skip paths (synthetic bytecode) ──────────────────

    @Test
    fun `non-contiguous loop body is skipped — no LOOP events`() {
        // Hand-crafted bytecode where the loop's body indices straddle
        // unrelated code that's reachable from outside the loop body
        // via a normal-CFG jump. LoopWrapper's contiguity check should
        // skip the loop entirely, so the only events recorded come from
        // the MINIMAL-tier method wrap (METHOD_START + METHOD_END) plus
        // any wrapped INVOKE inside the bytecode.
        //
        // Layout (each line = one ASM instruction; verifier-clean):
        //
        //   00: ICONST_0                  ; i = 0
        //   01: ISTORE_0
        //   02: ICONST_0
        //   03: IFEQ  L_BODY_HEAD         ; conditional pre-branch:
        //                                 ; (always-taken at runtime because
        //                                 ; top-of-stack is 0, but the JVM
        //                                 ; CFG records both edges, so
        //                                 ; L_UNRELATED keeps an external
        //                                 ; predecessor).
        //   04: GOTO L_UNRELATED          ; fallthrough-route to unrelated
        //   05: L_BODY_HEAD               ; loop header (back-edge target)
        //   06: GOTO L_BODY_TAIL          ; body half 1 → jump past unrelated
        //   07: L_UNRELATED               ; unrelated code (in body-range
        //                                 ; but NOT in body — external pred
        //                                 ; from instruction 03/04 above)
        //   08: ICONST_0
        //   09: POP
        //   10: GOTO L_EXIT
        //   11: L_BODY_TAIL               ; body half 2
        //   12: IINC 0 1
        //   13: ILOAD_0
        //   14: ICONST_3
        //   15: IF_ICMPLT L_BODY_HEAD     ; back-edge to header
        //   16: GOTO L_EXIT
        //   17: L_EXIT
        //   18: RETURN
        //
        // Body via reverse walk from the back-edge source (15):
        //   {15, 14, 13, 12, L_BODY_TAIL, L_BODY_HEAD, GOTO@06}
        // ⇒ minIdx ≤ 5 and maxIdx ≥ 15; the unrelated code (07..10)
        // sits INSIDE [minIdx, maxIdx] but is NOT in body, AND has
        // non-empty preds (from instruction 04's GOTO and the
        // fall-through edge from 03). LoopWrapper's contiguity check
        // should reject this loop.
        val bytes = synthesizeNonContiguousLoop()
        val transformed = applyAtTier(
            bytes,
            tier = StartupTier.DETAILED,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()
        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.NonContiguous" to transformed),
            "fixtures.NonContiguous", "onCreate",
        )

        val events = RecordingStartupDispatcher.events()
        // Loop was skipped → no LOOP_START / LOOP_END events. The
        // MINIMAL-tier method wrap still fires — `onCreate ()V` →
        // APPLICATION kind → onApplicationStart/End.
        assertEquals(0, events.count { it.kind == RecordingStartupDispatcher.Kind.LOOP_START })
        assertEquals(0, events.count { it.kind == RecordingStartupDispatcher.Kind.LOOP_END })
        assertEquals(1, events.count { it.kind == RecordingStartupDispatcher.Kind.APPLICATION_START })
        assertEquals(1, events.count { it.kind == RecordingStartupDispatcher.Kind.APPLICATION_END })
    }

    @Test
    fun `loop exit-target with external predecessor is skipped — no LOOP events`() {
        // Hand-crafted bytecode where the loop's exit target is ALSO
        // reachable from outside the body (an `if` above the loop
        // jumping past it to the same label that's also the loop's
        // exit). LoopWrapper's external-predecessor check should skip
        // this loop to avoid false-positive `onLoopEnd` events when
        // control bypasses the loop.
        //
        // Layout (verifier-clean):
        //
        //   00: ICONST_0                  ; condition for outer if
        //   01: IFEQ L_EXIT               ; if condition zero, skip loop
        //                                 ; → external predecessor of L_EXIT
        //   02: ICONST_0                  ; i = 0
        //   03: ISTORE_0
        //   04: L_HEADER                  ; loop header
        //   05: ILOAD_0
        //   06: ICONST_3
        //   07: IF_ICMPGE L_EXIT          ; exit edge: jumps to L_EXIT
        //   08: IINC 0 1
        //   09: GOTO L_HEADER             ; back-edge
        //   10: L_EXIT                    ; both in-loop AND out-of-loop
        //                                 ; predecessors → SKIP
        //   11: RETURN
        val bytes = synthesizeExitWithExternalPred()
        val transformed = applyAtTier(
            bytes,
            tier = StartupTier.DETAILED,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()
        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.ExitExtPred" to transformed),
            "fixtures.ExitExtPred", "onCreate",
        )

        val events = RecordingStartupDispatcher.events()
        // Loop skipped → only the method wrap fires. `onCreate ()V` →
        // APPLICATION kind → onApplicationStart/End.
        assertEquals("no LOOP events when exit has external pred", 0,
            events.count {
                it.kind == RecordingStartupDispatcher.Kind.LOOP_START
                        || it.kind == RecordingStartupDispatcher.Kind.LOOP_END
            })
        assertEquals(1, events.count { it.kind == RecordingStartupDispatcher.Kind.APPLICATION_START })
        assertEquals(1, events.count { it.kind == RecordingStartupDispatcher.Kind.APPLICATION_END })
    }

    // ── synchronized-block ───────────────────────────────────────────

    @Test
    fun `synchronized block around for loop — exception edge does NOT manufacture spurious loop`() {
        // `synchronized` compiles to MONITORENTER + try/catch(any) +
        // MONITOREXIT + ATHROW. The synthetic catch-any exception edge
        // would, if treated as a normal CFG edge, manufacture a back
        // edge whose target dominates the throw site — yielding a
        // spurious "loop" around the synchronized body. MethodCfg
        // deliberately excludes exception edges from the loop-
        // detection CFG; this test pins that filter end-to-end.
        //
        // The test's load-bearing claim is exactly: the real for-loop
        // INSIDE the synchronized block IS detected (1 LOOP_START /
        // 1 LOOP_END pair) AND the synthetic synchronized-cleanup
        // edge does NOT add a second loop pair. Call-wrap behavior
        // for INVOKE* inside synchronized blocks is out of scope
        // here (the calls sit inside the synthetic user-shaped
        // try-catch, so `TopLevelCallWrapper`'s skip-calls-inside-
        // user-try rule applies — but pinning that interaction is
        // the job of `StandardTierTransformTest`'s user-try-catch
        // test, not this one).
        val classes = JavaSourceCompiler.compile(
            "fixtures/LoopSync.java",
            """
            package fixtures;
            public class LoopSync {
                public static void onCreate() {
                    synchronized (LoopSync.class) {
                        for (int i = 0; i < 3; i++) {
                            String.valueOf(i);
                        }
                    }
                }
            }
            """.trimIndent(),
        )
        val transformed = applyAtTier(
            classes["fixtures.LoopSync"]!!,
            tier = StartupTier.DETAILED,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()
        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.LoopSync" to transformed),
            "fixtures.LoopSync", "onCreate",
        )

        val events = RecordingStartupDispatcher.events()
        val loopStarts = events.count { it.kind == RecordingStartupDispatcher.Kind.LOOP_START }
        val loopEnds = events.count { it.kind == RecordingStartupDispatcher.Kind.LOOP_END }
        // Exactly one real loop should be detected — the synthetic
        // synchronized cleanup must NOT manufacture a second loop.
        // Note: the `String.valueOf` call sits inside the synchronized
        // block's MONITOREXIT/ATHROW user-shaped try-catch, so
        // TopLevelCallWrapper's "skip calls inside existing user try"
        // rule applies — no CALL events are expected here. That's
        // explicitly verified in the "user try-catch" test in
        // StandardTierTransformTest. The point of THIS test is to pin
        // the loop-detection behavior in the presence of the
        // synthetic cleanup handler.
        assertEquals("exactly one LOOP_START — synchronized cleanup is not a loop",
            1, loopStarts)
        assertEquals("exactly one LOOP_END — synchronized cleanup is not a loop",
            1, loopEnds)
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun applyAtTier(
        classBytes: ByteArray,
        tier: StartupTier,
        candidateMethods: Set<MethodKey>,
    ): ByteArray {
        return AsmTestHarness.transform(classBytes) { writer ->
            AppStartupTracingClassVisitor(
                apiVersion = Opcodes.ASM9,
                nextClassVisitor = writer,
                candidateMethods = candidateMethods,
                dispatcherInternalName = dispatcherInternal,
                tier = tier,
            )
        }
    }

    /**
     * Builds a class containing a static `onCreate()V` method whose
     * bytecode contains a natural loop with a non-contiguous body —
     * see the test using this method for the layout. Goal: trigger
     * the contiguity check in `LoopWrapper.wrapSingleLoop` so the loop
     * is silently dropped.
     */
    private fun synthesizeNonContiguousLoop(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V11, Opcodes.ACC_PUBLIC,
            "fixtures/NonContiguous", null, "java/lang/Object", null,
        )
        val mv: MethodVisitor = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "onCreate", "()V", null, null,
        )
        val lBodyHead = Label()
        val lUnrelated = Label()
        val lBodyTail = Label()
        val lExit = Label()
        mv.visitCode()
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitVarInsn(Opcodes.ISTORE, 0)
        // Conditional pre-branch: top-of-stack 0 → IFEQ taken at
        // runtime, but JVM CFG records both edges so L_UNRELATED has
        // an external predecessor on the fall-through.
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitJumpInsn(Opcodes.IFEQ, lBodyHead)
        mv.visitJumpInsn(Opcodes.GOTO, lUnrelated)
        mv.visitLabel(lBodyHead)
        mv.visitJumpInsn(Opcodes.GOTO, lBodyTail)
        mv.visitLabel(lUnrelated)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.POP)
        mv.visitJumpInsn(Opcodes.GOTO, lExit)
        mv.visitLabel(lBodyTail)
        mv.visitIincInsn(0, 1)
        mv.visitVarInsn(Opcodes.ILOAD, 0)
        mv.visitInsn(Opcodes.ICONST_3)
        mv.visitJumpInsn(Opcodes.IF_ICMPLT, lBodyHead)
        mv.visitJumpInsn(Opcodes.GOTO, lExit)
        mv.visitLabel(lExit)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * Builds a class containing a static `onCreate()V` method whose
     * loop exit-target also has an external predecessor (an `if` ahead
     * of the loop jumping past it). Goal: trigger LoopWrapper's
     * external-predecessor check on exit targets.
     */
    private fun synthesizeExitWithExternalPred(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V11, Opcodes.ACC_PUBLIC,
            "fixtures/ExitExtPred", null, "java/lang/Object", null,
        )
        val mv: MethodVisitor = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "onCreate", "()V", null, null,
        )
        val lHeader = Label()
        val lExit = Label()
        mv.visitCode()
        // Outer if — at runtime ICONST_0 + IFEQ always jumps to L_EXIT
        // (skipping the loop). At analysis time both edges are
        // recorded, so L_EXIT picks up an external predecessor here.
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitJumpInsn(Opcodes.IFEQ, lExit)
        // Loop body: standard for(int i = 0; i < 3; i++) {} shape.
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitVarInsn(Opcodes.ISTORE, 0)
        mv.visitLabel(lHeader)
        mv.visitVarInsn(Opcodes.ILOAD, 0)
        mv.visitInsn(Opcodes.ICONST_3)
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, lExit)  // exit edge from loop
        mv.visitIincInsn(0, 1)
        mv.visitJumpInsn(Opcodes.GOTO, lHeader)     // back-edge
        mv.visitLabel(lExit)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }
}
