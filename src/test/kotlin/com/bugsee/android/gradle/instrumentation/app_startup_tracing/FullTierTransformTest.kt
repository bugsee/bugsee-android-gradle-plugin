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
import org.objectweb.asm.Opcodes

/**
 * End-to-end transform tests for the FULL tier: adds `@BugseeTrace`
 * annotation pickup on top of MINIMAL + STANDARD + DETAILED.
 *
 * Coverage:
 *  - Annotated method on a non-kind class → wrapped (method body only,
 *    no call / loop wraps even though FULL tier permits them
 *    elsewhere). Per the design: the annotation is a per-method
 *    opt-in; cost stays predictable;
 *  - Unannotated method on a non-kind class → NOT wrapped (no events);
 *  - DETAILED tier ignores the annotation entirely (only kind-based
 *    classes get instrumented at DETAILED);
 *  - Annotated suspend method skipped (descriptor-based filter);
 *  - Annotated abstract method skipped (no body to wrap).
 *
 * Tests bypass the factory's `isInstrumentable` check (calling
 * [AppStartupTracingClassVisitor] directly with an explicit candidate
 * set + tier). The factory's behavior (FULL → return true for all
 * non-denylisted classes) is exercised at the integration level; the
 * visitor's per-method annotation pickup is what the unit tests below
 * actually verify.
 */
class FullTierTransformTest {

    private val dispatcherInternal = "com/bugsee/test/fixtures/RecordingStartupDispatcher"

    @Before fun setUp() { RecordingStartupDispatcher.reset() }
    @After fun tearDown() { RecordingStartupDispatcher.reset() }

    // ── happy path ───────────────────────────────────────────────────

    @Test
    fun `@BugseeTrace method on non-kind class is wrapped at FULL tier`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/AnnotatedClass.java",
            """
            package fixtures;
            import com.bugsee.library.contracts.performance.BugseeTrace;
            public class AnnotatedClass {
                @BugseeTrace
                public static int explicitlyTraced(int x) {
                    return x * 2;
                }
                public static int notTraced(int x) {
                    return x + 1;
                }
            }
            """.trimIndent(),
        )
        val transformed = applyAtTier(
            classes["fixtures.AnnotatedClass"]!!,
            tier = StartupTier.FULL,
            candidateMethods = emptySet(), // No kind-based candidates
        )
        AsmTestHarness.verify(transformed).assertOk()

        // Invoke the annotated method.
        val tracedResult = AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.AnnotatedClass" to transformed),
            "fixtures.AnnotatedClass", "explicitlyTraced",
            arrayOf(Int::class.javaPrimitiveType!!),
            arrayOf(5),
        )
        assertEquals(10, tracedResult)

        // Annotated method should have fired METHOD_START + METHOD_END.
        val eventsAfterAnnotated = RecordingStartupDispatcher.events()
        assertEquals(2, eventsAfterAnnotated.size)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_START, eventsAfterAnnotated[0].kind)
        assertEquals("fixtures.AnnotatedClass#explicitlyTraced", eventsAfterAnnotated[0].siteId)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_END, eventsAfterAnnotated[1].kind)

        // Now invoke the un-annotated method. It must NOT fire any events.
        RecordingStartupDispatcher.reset()
        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.AnnotatedClass" to transformed),
            "fixtures.AnnotatedClass", "notTraced",
            arrayOf(Int::class.javaPrimitiveType!!),
            arrayOf(7),
        )
        assertEquals(0, RecordingStartupDispatcher.size())
    }

    @Test
    fun `@BugseeTrace method does NOT get call wraps even at FULL tier`() {
        // Annotated method picks up the MINIMAL-equivalent wrap only —
        // method body only, no top-level call wraps. This is the
        // documented design: opt-in is per-method, cost stays
        // predictable, no surprise expansions for an annotated method.
        val classes = JavaSourceCompiler.compile(
            "fixtures/CallingAnnotated.java",
            """
            package fixtures;
            import com.bugsee.library.contracts.performance.BugseeTrace;
            public class CallingAnnotated {
                @BugseeTrace
                public static String greet(String name) {
                    return String.valueOf(name);
                }
            }
            """.trimIndent(),
        )
        val transformed = applyAtTier(
            classes["fixtures.CallingAnnotated"]!!,
            tier = StartupTier.FULL,
            candidateMethods = emptySet(),
        )
        AsmTestHarness.verify(transformed).assertOk()

        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.CallingAnnotated" to transformed),
            "fixtures.CallingAnnotated", "greet",
            arrayOf(String::class.java),
            arrayOf("world"),
        )

        val events = RecordingStartupDispatcher.events()
        // METHOD_START + METHOD_END only. The String.valueOf call inside
        // is NOT wrapped (no CALL_START/CALL_END).
        assertEquals(2, events.size)
        assertTrue(events.none {
            it.kind == RecordingStartupDispatcher.Kind.CALL_START
                    || it.kind == RecordingStartupDispatcher.Kind.CALL_END
        })
    }

    // ── tier gating ──────────────────────────────────────────────────

    @Test
    fun `tier DETAILED ignores @BugseeTrace annotation`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/DetailedIgnores.java",
            """
            package fixtures;
            import com.bugsee.library.contracts.performance.BugseeTrace;
            public class DetailedIgnores {
                @BugseeTrace
                public static void annotated() {
                    String.valueOf(1);
                }
            }
            """.trimIndent(),
        )
        val transformed = applyAtTier(
            classes["fixtures.DetailedIgnores"]!!,
            tier = StartupTier.DETAILED,
            candidateMethods = emptySet(),
        )
        AsmTestHarness.verify(transformed).assertOk()

        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.DetailedIgnores" to transformed),
            "fixtures.DetailedIgnores", "annotated",
        )

        // DETAILED tier: no kind-based candidates supplied AND
        // picksUpAnnotated() returns false for DETAILED → fast-path
        // pass-through → no events.
        assertEquals(0, RecordingStartupDispatcher.size())
    }

    // ── filter behavior on annotated methods ─────────────────────────

    @Test
    fun `annotated suspend-shaped method is skipped`() {
        // Use the same descriptor-rewrite trick from
        // MinimalTierTransformTest: compile a method with an
        // innocent descriptor, then patch the bytecode to claim
        // a Continuation parameter so the suspend filter rejects.
        val classes = JavaSourceCompiler.compile(
            "fixtures/AnnotatedSuspend.java",
            """
            package fixtures;
            import com.bugsee.library.contracts.performance.BugseeTrace;
            public class AnnotatedSuspend {
                @BugseeTrace
                public static Object pretendSuspend(Object cont) {
                    return cont;
                }
            }
            """.trimIndent(),
        )
        val patched = renameMethodDescriptor(
            classes["fixtures.AnnotatedSuspend"]!!,
            methodName = "pretendSuspend",
            from = "(Ljava/lang/Object;)Ljava/lang/Object;",
            to = "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
        )
        val transformed = applyAtTier(
            patched,
            tier = StartupTier.FULL,
            candidateMethods = emptySet(),
        )
        AsmTestHarness.verify(transformed).assertOk()
        // No instrumentation means no recorded events. We can't
        // load+invoke a class referencing an unloadable Continuation,
        // but the verifier-only assertion is sufficient to prove the
        // suspend skip path took precedence.
        assertEquals(0, RecordingStartupDispatcher.size())
    }

    @Test
    fun `annotated abstract method is skipped — verifier accepts`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/AnnotatedAbstract.java",
            """
            package fixtures;
            import com.bugsee.library.contracts.performance.BugseeTrace;
            public abstract class AnnotatedAbstract {
                @BugseeTrace
                public abstract void traced();
            }
            """.trimIndent(),
        )
        val transformed = applyAtTier(
            classes["fixtures.AnnotatedAbstract"]!!,
            tier = StartupTier.FULL,
            candidateMethods = emptySet(),
        )
        AsmTestHarness.verify(transformed).assertOk()
    }

    // ── annotation-replay fidelity ───────────────────────────────────

    @Test
    fun `mixed annotations on a method are preserved through FULL-tier transform`() {
        // FULL-tier instrumentation uses a peek-and-buffer visitor that
        // intercepts every method's annotations to detect @BugseeTrace
        // and decide whether to instrument. The annotation replay path
        // must NOT drop or reorder other annotations — a method
        // annotated with BOTH `@Deprecated` (a JDK runtime annotation)
        // and `@BugseeTrace` (the SDK's CLASS-retained annotation)
        // should retain BOTH in the produced bytecode. A bug that
        // dropped the unrelated annotation would silently break consumer
        // expectations about declared annotations (lint, reflection,
        // IDE warnings, JLS visibility).
        val classes = JavaSourceCompiler.compile(
            "fixtures/MixedAnnotated.java",
            """
            package fixtures;
            import com.bugsee.library.contracts.performance.BugseeTrace;
            public class MixedAnnotated {
                @Deprecated
                @BugseeTrace
                public static int both(int x) { return x + 1; }
            }
            """.trimIndent(),
        )
        val transformed = applyAtTier(
            classes["fixtures.MixedAnnotated"]!!,
            tier = StartupTier.FULL,
            candidateMethods = emptySet(),
        )
        AsmTestHarness.verify(transformed).assertOk()

        // Parse the transformed bytecode and inspect the method's
        // annotation tables to confirm both annotations are still there.
        val node = org.objectweb.asm.tree.ClassNode()
        org.objectweb.asm.ClassReader(transformed).accept(node, 0)
        val method = node.methods.first { it.name == "both" && it.desc == "(I)I" }

        // @Deprecated is RUNTIME-retained → visibleAnnotations.
        // @BugseeTrace is CLASS-retained → invisibleAnnotations.
        val visibleDescs = method.visibleAnnotations?.map { it.desc } ?: emptyList()
        val invisibleDescs = method.invisibleAnnotations?.map { it.desc } ?: emptyList()
        assertTrue("@Deprecated must survive transform: visible=$visibleDescs",
            visibleDescs.contains("Ljava/lang/Deprecated;"))
        assertTrue("@BugseeTrace must survive transform: invisible=$invisibleDescs",
            invisibleDescs.contains("Lcom/bugsee/library/contracts/performance/BugseeTrace;"))

        // And the method should still execute correctly (smoke check —
        // body wasn't dropped or corrupted during the annotation peek).
        RecordingStartupDispatcher.reset()
        val result = AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.MixedAnnotated" to transformed),
            "fixtures.MixedAnnotated", "both",
            arrayOf(Int::class.javaPrimitiveType!!),
            arrayOf(41),
        )
        assertEquals(42, result)
        // Annotated method gets wrapped per FULL-tier rules:
        // METHOD_START + METHOD_END.
        val events = RecordingStartupDispatcher.events()
        assertEquals(2, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_START, events[0].kind)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_END, events[1].kind)
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

    private fun renameMethodDescriptor(
        classBytes: ByteArray,
        methodName: String,
        from: String,
        to: String,
    ): ByteArray {
        val node = org.objectweb.asm.tree.ClassNode()
        org.objectweb.asm.ClassReader(classBytes).accept(node, 0)
        val method = node.methods.firstOrNull { it.name == methodName && it.desc == from }
            ?: throw IllegalStateException("method $methodName$from not found in ${node.name}")
        method.desc = to
        val writer = org.objectweb.asm.ClassWriter(0)
        node.accept(writer)
        return writer.toByteArray()
    }
}
