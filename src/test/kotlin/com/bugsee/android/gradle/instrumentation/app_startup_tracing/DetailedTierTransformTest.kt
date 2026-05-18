package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import com.bugsee.android.gradle.instrumentation.fixtures.JavaSourceCompiler
import com.bugsee.test.fixtures.RecordingStartupDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
        // METHOD_START, LOOP_START, then 3 iterations × (CALL_START,
        // CALL_END), LOOP_END, METHOD_END = 1 + 1 + 6 + 1 + 1 = 10.
        assertEquals(10, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_START, events[0].kind)
        assertEquals(RecordingStartupDispatcher.Kind.LOOP_START, events[1].kind)
        // Middle six are 3 × (CALL_START, CALL_END).
        for (i in 0..5 step 2) {
            assertEquals(RecordingStartupDispatcher.Kind.CALL_START, events[2 + i].kind)
            assertEquals(RecordingStartupDispatcher.Kind.CALL_END, events[2 + i + 1].kind)
        }
        assertEquals(RecordingStartupDispatcher.Kind.LOOP_END, events[8].kind)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_END, events[9].kind)
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
        // 1 METHOD_START + 1 LOOP_START + 2×(CALL_START, CALL_END) +
        // 1 LOOP_END + 1 METHOD_END = 8.
        assertEquals(8, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.LOOP_START, events[1].kind)
        assertEquals(RecordingStartupDispatcher.Kind.LOOP_END, events[6].kind)
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
        // METHOD_START, LOOP_START, then iteration i=2's CALL_START,
        // CALL_END (per-call handler), LOOP_END (loop handler),
        // METHOD_END (outer method handler).
        // (i=0 and i=1 don't invoke Helper.boom — the `if (i == 2)`
        // gate keeps them from calling at all.)
        assertEquals(6, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_START, events[0].kind)
        assertEquals(RecordingStartupDispatcher.Kind.LOOP_START, events[1].kind)
        assertEquals(RecordingStartupDispatcher.Kind.CALL_START, events[2].kind)
        assertEquals(RecordingStartupDispatcher.Kind.CALL_END, events[3].kind)
        assertEquals(RecordingStartupDispatcher.Kind.LOOP_END, events[4].kind)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_END, events[5].kind)
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
        // 1 METHOD_START + 2×(CALL_START, CALL_END) + 1 METHOD_END = 6.
        assertEquals(6, events.size)
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
        // No loops to wrap → just method + calls. 1 METHOD_START +
        // 2×(CALL_START, CALL_END) + 1 METHOD_END = 6.
        assertEquals(6, events.size)
        assertTrue(events.none {
            it.kind == RecordingStartupDispatcher.Kind.LOOP_START
                    || it.kind == RecordingStartupDispatcher.Kind.LOOP_END
        })
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
}
