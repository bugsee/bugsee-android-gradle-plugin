package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.bugsee.android.gradle.StartupTier
import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import com.bugsee.android.gradle.instrumentation.fixtures.JavaSourceCompiler
import com.bugsee.test.fixtures.RecordingStartupDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.objectweb.asm.Opcodes

/**
 * End-to-end transform tests for the STANDARD tier (MINIMAL + top-level
 * `INVOKE*` wrapping).
 *
 * Same harness conventions as [MinimalTierTransformTest]: bypass the
 * factory's class-kind filter by calling
 * [AppStartupTracingClassVisitor] directly with an explicit candidate
 * set, so fixtures don't need Android stubs on the test classpath.
 */
class StandardTierTransformTest {

    private val dispatcherInternal = "com/bugsee/test/fixtures/RecordingStartupDispatcher"

    @Before
    fun setUp() {
        RecordingStartupDispatcher.reset()
    }

    @After
    fun tearDown() {
        RecordingStartupDispatcher.reset()
    }

    // ── basic call wrapping ──────────────────────────────────────────

    @Test
    fun `three sequential calls — one method + three call pairs`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/Calls.java",
            """
            package fixtures;
            public class Calls {
                public static void onCreate() {
                    String s1 = String.valueOf(1);
                    String s2 = String.valueOf(2);
                    String s3 = String.valueOf(3);
                }
            }
            """.trimIndent(),
        )
        val transformed = applyTransform(
            classes["fixtures.Calls"]!!,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()

        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.Calls" to transformed),
            "fixtures.Calls", "onCreate",
        )

        val events = RecordingStartupDispatcher.events()
        // 1 APPLICATION_START + 3×(CALL_START, CALL_END) + 1 APPLICATION_END = 8
        // (`onCreate ()V` → APPLICATION kind → onApplicationStart/End.)
        assertEquals(8, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_START, events.first().kind)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_END, events.last().kind)
        // Middle 6 events are the three call pairs.
        val callEvents = events.subList(1, 7)
        for (i in 0..5 step 2) {
            assertEquals(RecordingStartupDispatcher.Kind.CALL_START, callEvents[i].kind)
            assertEquals(RecordingStartupDispatcher.Kind.CALL_END, callEvents[i + 1].kind)
            assertEquals("java.lang.String#valueOf", callEvents[i].siteId)
            assertEquals("java.lang.String#valueOf", callEvents[i + 1].siteId)
        }
    }

    @Test
    fun `throwing call — call END fires via handler then exception propagates to method END`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/ThrowingCall.java",
            """
            package fixtures;
            public class ThrowingCall {
                public static void onCreate() {
                    Helper.boom();
                }
            }
            class Helper {
                static void boom() {
                    throw new RuntimeException("boom");
                }
            }
            """.trimIndent(),
        )
        val transformed = applyTransform(
            classes["fixtures.ThrowingCall"]!!,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()

        try {
            AsmTestHarness.loadAndInvokeStatic(
                // Helper class also goes in the loader so it can resolve.
                mapOf(
                    "fixtures.ThrowingCall" to transformed,
                    "fixtures.Helper" to classes["fixtures.Helper"]!!,
                ),
                "fixtures.ThrowingCall", "onCreate",
            )
            fail("expected RuntimeException to propagate")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertEquals("boom", e.targetException.message)
        }

        val events = RecordingStartupDispatcher.events()
        // APPLICATION_START, CALL_START, CALL_END (from per-call handler),
        // APPLICATION_END (from outer catch-any). Both the call's onCallEnd
        // and the method's kind-specific end must fire on the exception path.
        assertEquals(4, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_START, events[0].kind)
        assertEquals(RecordingStartupDispatcher.Kind.CALL_START, events[1].kind)
        assertEquals(RecordingStartupDispatcher.Kind.CALL_END, events[2].kind)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_END, events[3].kind)
    }

    // ── filter behaviors ─────────────────────────────────────────────

    @Test
    fun `call inside an existing user try-catch is NOT wrapped`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/UserTryCall.java",
            """
            package fixtures;
            public class UserTryCall {
                public static int onCreate() {
                    try {
                        return String.valueOf(7).length();
                    } catch (RuntimeException e) {
                        return -1;
                    }
                }
            }
            """.trimIndent(),
        )
        val transformed = applyTransform(
            classes["fixtures.UserTryCall"]!!,
            candidateMethods = setOf(MethodKey("onCreate", "()I")),
        )
        AsmTestHarness.verify(transformed).assertOk()

        val result = AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.UserTryCall" to transformed),
            "fixtures.UserTryCall", "onCreate",
        )
        assertEquals(1, result) // "7".length()

        val events = RecordingStartupDispatcher.events()
        // METHOD_START + METHOD_END only — the String.valueOf and
        // .length() calls are inside the user's try-catch and the
        // top-level filter skipped them.
        assertEquals(2, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_START, events[0].kind)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_END, events[1].kind)
    }

    @Test
    fun `dispatcher self-call is not re-wrapped`() {
        // Hand-crafted bytecode that already calls dispatcher.onMethodStart
        // directly. After running the STANDARD-tier transform on the
        // method, the only call events recorded should be from the
        // call site already present in the source — NOT from a wrapped
        // onMethodStart call (which would mean we wrapped our own
        // dispatcher INVOKESTATIC).
        //
        // We approximate by compiling a method that explicitly calls
        // RecordingStartupDispatcher.onMethodStart("manual"). If the
        // transform's self-recursion guard works, the recorded event
        // list contains only METHOD_START (from our outer wrap),
        // METHOD_START again (from the manual call inside the body —
        // because user code legitimately invoked it), and METHOD_END
        // (from our outer wrap). No CALL_START / CALL_END for the
        // manual invocation.
        val classes = JavaSourceCompiler.compile(
            "fixtures/SelfCall.java",
            """
            package fixtures;
            import com.bugsee.test.fixtures.RecordingStartupDispatcher;
            public class SelfCall {
                public static void onCreate() {
                    RecordingStartupDispatcher.onMethodStart("manual");
                }
            }
            """.trimIndent(),
        )
        val transformed = applyTransform(
            classes["fixtures.SelfCall"]!!,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()

        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.SelfCall" to transformed),
            "fixtures.SelfCall", "onCreate",
        )

        val events = RecordingStartupDispatcher.events()
        // Expected: APPLICATION_START (outer wrap on `onCreate ()V` →
        //                              APPLICATION kind),
        //           METHOD_START (the manual user-code call into the
        //                         generic onMethodStart, siteId = "manual"),
        //           APPLICATION_END (outer wrap).
        // No CALL_START / CALL_END entries — the dispatcher self-call
        // was excluded by the guard.
        assertEquals(3, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_START, events[0].kind)
        assertEquals("fixtures.SelfCall#onCreate", events[0].siteId)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_START, events[1].kind)
        assertEquals("manual", events[1].siteId)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_END, events[2].kind)
        // Sanity-check: no CALL_* events at all.
        assertTrue(events.none { it.kind == RecordingStartupDispatcher.Kind.CALL_START })
        assertTrue(events.none { it.kind == RecordingStartupDispatcher.Kind.CALL_END })
    }

    // ── INVOKEDYNAMIC skip ───────────────────────────────────────────

    @Test
    fun `INVOKEDYNAMIC lambda bootstrap is NOT wrapped but surrounding INVOKE is`() {
        // Lambda metafactory bootstraps compile to `INVOKEDYNAMIC`,
        // which TopLevelCallWrapper deliberately skips (only
        // MethodInsnNode is wrapped — InvokeDynamicInsnNode is a
        // different ASM node type). The surrounding `forEach` INVOKE
        // (an interface call) IS a MethodInsnNode and MUST be wrapped.
        //
        // Fixture: an inline lambda (not a method reference, which
        // would trigger an `Objects.requireNonNull` null-check call
        // and complicate the count). The inline lambda compiles to
        // a single `INVOKEDYNAMIC` plus the `INVOKEINTERFACE
        // list.forEach(Consumer)V`. We assert exactly one CALL pair
        // (the forEach) and pin the site id so the assertion would
        // fail if the indy bootstrap target leaked through.
        val classes = JavaSourceCompiler.compile(
            "fixtures/IndyLambda.java",
            """
            package fixtures;
            import java.util.List;
            public class IndyLambda {
                public static void onCreate(List<String> list) {
                    list.forEach(s -> {});
                }
            }
            """.trimIndent(),
        )
        val transformed = applyTransform(
            classes["fixtures.IndyLambda"]!!,
            candidateMethods = setOf(MethodKey("onCreate", "(Ljava/util/List;)V")),
        )
        AsmTestHarness.verify(transformed).assertOk()

        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.IndyLambda" to transformed),
            "fixtures.IndyLambda", "onCreate",
            arrayOf(List::class.java),
            arrayOf(listOf("a", "b")),
        )

        val events = RecordingStartupDispatcher.events()
        // METHOD_START + (exactly one CALL_START, CALL_END) + METHOD_END
        // = 4 events. If INVOKEDYNAMIC had been wrapped we'd see at
        // least one extra CALL pair (for the metafactory bootstrap).
        val callStarts = events.count { it.kind == RecordingStartupDispatcher.Kind.CALL_START }
        val callEnds = events.count { it.kind == RecordingStartupDispatcher.Kind.CALL_END }
        assertEquals("exactly one CALL_START (the forEach), no INVOKEDYNAMIC wrap",
            1, callStarts)
        assertEquals(1, callEnds)
        // The single wrapped call's site id must identify forEach, not
        // anything resembling the lambda bootstrap (java.lang.invoke.*).
        val callEvent = events.first { it.kind == RecordingStartupDispatcher.Kind.CALL_START }
        assertEquals("java.util.List#forEach", callEvent.siteId)
    }

    // ── tier gating ──────────────────────────────────────────────────

    @Test
    fun `tier MINIMAL does NOT wrap calls even with STANDARD-shaped fixture`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/MinimalGated.java",
            """
            package fixtures;
            public class MinimalGated {
                public static void onCreate() {
                    String.valueOf(1);
                    String.valueOf(2);
                }
            }
            """.trimIndent(),
        )
        val transformed = applyAtTier(
            classes["fixtures.MinimalGated"]!!,
            tier = StartupTier.MINIMAL,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()

        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.MinimalGated" to transformed),
            "fixtures.MinimalGated", "onCreate",
        )

        val events = RecordingStartupDispatcher.events()
        // MINIMAL: only the method wrap fires. Calls are not wrapped.
        // `onCreate ()V` → APPLICATION kind → onApplicationStart/End.
        assertEquals(2, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_START, events[0].kind)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_END, events[1].kind)
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun applyTransform(
        classBytes: ByteArray,
        candidateMethods: Set<MethodKey>,
    ): ByteArray = applyAtTier(classBytes, StartupTier.STANDARD, candidateMethods)

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
                className = "fixtures.Test",
            )
        }
    }
}
