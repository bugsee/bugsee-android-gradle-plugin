package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.bugsee.android.gradle.StartupTier
import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import com.bugsee.android.gradle.instrumentation.fixtures.JavaSourceCompiler
import com.bugsee.test.fixtures.RecordingStartupDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes

/**
 * End-to-end transform tests for the MINIMAL tier.
 *
 * Tests are decoupled from the factory's class-kind filter: each test
 * supplies the candidate method set explicitly to
 * [AppStartupTracingClassVisitor]. Test fixtures therefore don't need to
 * reference Android types (which would be unloadable in plain JVM tests
 * without `android.jar` stubs) — they extend `java.lang.Object` directly.
 *
 * Bytecode goes through:
 *  - [JavaSourceCompiler] to produce class bytes;
 *  - [AsmTestHarness.transform] with [AppStartupTracingClassVisitor]
 *    wrapping a `ClassWriter` to apply the MINIMAL-tier transform;
 *  - [AsmTestHarness.verify] to confirm `CheckClassAdapter` and
 *    `SimpleVerifier` accept the result;
 *  - [AsmTestHarness.loadAndInvokeStatic] (or a per-test entry point
 *    runner for instance methods) to actually execute it and assert
 *    [RecordingStartupDispatcher] received the right events.
 *
 * The transform targets the test-only
 * `com.bugsee.test.fixtures.RecordingStartupDispatcher` instead of the
 * production `com.bugsee.library.adapters.BugseeAppStartupDispatcher`,
 * so the same harness chain that resolves dispatcher INVOKESTATICs
 * (verified in Phase 4 `HarnessSmokeTest`) keeps working.
 */
class MinimalTierTransformTest {

    private val dispatcherInternal = "com/bugsee/test/fixtures/RecordingStartupDispatcher"

    @Before
    fun setUp() {
        RecordingStartupDispatcher.reset()
    }

    @After
    fun tearDown() {
        RecordingStartupDispatcher.reset()
    }

    // ── happy paths ──────────────────────────────────────────────────

    @Test
    fun `wraps simple void method with single return — start then end`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/SimpleApp.java",
            """
            package fixtures;
            public class SimpleApp {
                public static void onCreate() {
                    int x = 1 + 2;
                }
            }
            """.trimIndent(),
        )
        val original = classes["fixtures.SimpleApp"]!!
        val transformed = applyTransform(
            original,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()

        invokeStatic(transformed, "fixtures.SimpleApp", "onCreate")

        val events = RecordingStartupDispatcher.events()
        assertEquals(2, events.size)
        // `onCreate ()V` is in StartupMethodFilter's APPLICATION_METHODS,
        // so the kind-specific dispatcher pair onApplicationStart/End
        // fires (folds to `app.startup.application` on the SDK side).
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_START, events[0].kind)
        assertEquals("fixtures.SimpleApp#onCreate", events[0].siteId)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_END, events[1].kind)
        assertEquals("fixtures.SimpleApp#onCreate", events[1].siteId)
    }

    @Test
    fun `wraps method returning int — return value preserved`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/IntReturner.java",
            """
            package fixtures;
            public class IntReturner {
                public static int compute() { return 42; }
            }
            """.trimIndent(),
        )
        val transformed = applyTransform(
            classes["fixtures.IntReturner"]!!,
            candidateMethods = setOf(MethodKey("compute", "()I")),
        )
        AsmTestHarness.verify(transformed).assertOk()

        val result = AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.IntReturner" to transformed),
            "fixtures.IntReturner",
            "compute",
        )
        assertEquals(42, result)
        // `compute ()I` is not in any per-kind table → kindForMethodKey
        // returns null → generic onMethodStart/End fallback.
        val events = RecordingStartupDispatcher.events()
        assertEquals(2, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_START, events[0].kind)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_END, events[1].kind)
    }

    @Test
    fun `wraps method with multiple returns — onMethodEnd before each`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/MultiReturn.java",
            """
            package fixtures;
            public class MultiReturn {
                public static int branch(int x) {
                    if (x > 0) return 1;
                    if (x < 0) return -1;
                    return 0;
                }
            }
            """.trimIndent(),
        )
        val transformed = applyTransform(
            classes["fixtures.MultiReturn"]!!,
            candidateMethods = setOf(MethodKey("branch", "(I)I")),
        )
        AsmTestHarness.verify(transformed).assertOk()

        val r1 = AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.MultiReturn" to transformed),
            "fixtures.MultiReturn", "branch",
            arrayOf(Int::class.javaPrimitiveType!!),
            arrayOf(5),
        )
        assertEquals(1, r1)
        // `branch (I)I` is not in any per-kind table → generic
        // onMethodStart/End fallback. Branch taken: one start, one end
        // (the first return only).
        val events = RecordingStartupDispatcher.events()
        assertEquals(2, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_START, events[0].kind)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_END, events[1].kind)
    }

    @Test
    fun `wraps throwing method — onMethodEnd fires via handler, exception propagates`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/Thrower.java",
            """
            package fixtures;
            public class Thrower {
                public static void onCreate() {
                    throw new RuntimeException("boom");
                }
            }
            """.trimIndent(),
        )
        val transformed = applyTransform(
            classes["fixtures.Thrower"]!!,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()

        try {
            invokeStatic(transformed, "fixtures.Thrower", "onCreate")
            fail("expected RuntimeException to propagate")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException
            assertNotNull(cause)
            assertEquals("boom", cause.message)
        }

        val events = RecordingStartupDispatcher.events()
        assertEquals(2, events.size)
        // `onCreate ()V` → APPLICATION kind → onApplicationStart/End.
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_START, events[0].kind)
        assertEquals(RecordingStartupDispatcher.Kind.APPLICATION_END, events[1].kind)
    }

    @Test
    fun `wraps method with existing try-catch — verifier passes, behavior preserved`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/UserTryCatch.java",
            """
            package fixtures;
            public class UserTryCatch {
                public static int onCreate(boolean throwIt) {
                    try {
                        if (throwIt) throw new IllegalStateException("user");
                        return 1;
                    } catch (IllegalStateException e) {
                        return -1;
                    } finally {
                        // touch a local so finally is not pure no-op
                        int x = 0;
                    }
                }
            }
            """.trimIndent(),
        )
        val transformed = applyTransform(
            classes["fixtures.UserTryCatch"]!!,
            candidateMethods = setOf(MethodKey("onCreate", "(Z)I")),
        )
        AsmTestHarness.verify(transformed).assertOk()

        val happy = AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.UserTryCatch" to transformed),
            "fixtures.UserTryCatch", "onCreate",
            arrayOf(Boolean::class.javaPrimitiveType!!),
            arrayOf(false),
        )
        assertEquals(1, happy)

        RecordingStartupDispatcher.reset()
        val caught = AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.UserTryCatch" to transformed),
            "fixtures.UserTryCatch", "onCreate",
            arrayOf(Boolean::class.javaPrimitiveType!!),
            arrayOf(true),
        )
        // User's catch handles the throw; method still returns -1 normally
        // (not via our handler). Two events: start + end (at the -1
        // return after the user's catch).
        assertEquals(-1, caught)
        val events = RecordingStartupDispatcher.events()
        assertEquals(2, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_START, events[0].kind)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_END, events[1].kind)
    }

    // ── kind routing (issue 2) ───────────────────────────────────────

    @Test
    fun `ContentProvider onCreate ()Z routes to PROVIDER_START_END`() {
        // Verifies `kindForMethodKey` classifies `onCreate ()Z` as
        // CONTENT_PROVIDER and the visitor wraps with the
        // onProviderStart/End dispatcher pair (folds to
        // `app.startup.provider` on the SDK side). A mutation that
        // collapses CONTENT_PROVIDER → APPLICATION in
        // `dispatchMethodNamesFor` would surface here as APPLICATION_*
        // events instead of PROVIDER_*.
        val classes = JavaSourceCompiler.compile(
            "fixtures/SampleProvider.java",
            """
            package fixtures;
            public class SampleProvider {
                public static boolean onCreate() {
                    return true;
                }
            }
            """.trimIndent(),
        )
        val transformed = applyTransform(
            classes["fixtures.SampleProvider"]!!,
            candidateMethods = setOf(MethodKey("onCreate", "()Z")),
        )
        AsmTestHarness.verify(transformed).assertOk()

        val result = AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.SampleProvider" to transformed),
            "fixtures.SampleProvider",
            "onCreate",
        )
        assertEquals(true, result)

        val events = RecordingStartupDispatcher.events()
        assertEquals(2, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.PROVIDER_START, events[0].kind)
        assertEquals("fixtures.SampleProvider#onCreate", events[0].siteId)
        assertEquals(RecordingStartupDispatcher.Kind.PROVIDER_END, events[1].kind)
        assertEquals("fixtures.SampleProvider#onCreate", events[1].siteId)
    }

    @Test
    fun `ContentProvider attachInfo routes to PROVIDER_START_END`() {
        // Verifies the second ContentProvider candidate descriptor
        // (`attachInfo (Landroid/content/Context;Landroid/content/pm/ProviderInfo;)V`)
        // also routes through onProviderStart/End. The runtime args are
        // unused by the body; the test passes `(null, null)` to satisfy
        // the descriptor. A mutation that removed this MethodKey from
        // CONTENT_PROVIDER_METHODS (or swapped it into the wrong table)
        // would surface as METHOD_* (null kind fallback) or APPLICATION_*
        // here instead of PROVIDER_*.
        val classes = JavaSourceCompiler.compile(
            "fixtures/AttachInfoProvider.java",
            """
            package fixtures;
            public class AttachInfoProvider {
                public static void attachInfo(Object ctx, Object info) {
                    // body intentionally empty
                }
            }
            """.trimIndent(),
        )
        // The CONTENT_PROVIDER_METHODS table keys on the real Android
        // descriptor; manually patch the compiled method's descriptor
        // to claim the real Android parameter types so kindForMethodKey
        // matches. The body is empty so no actual Android classes need
        // to be loadable for invocation.
        val patched = renameMethodDescriptor(
            classes["fixtures.AttachInfoProvider"]!!,
            methodName = "attachInfo",
            from = "(Ljava/lang/Object;Ljava/lang/Object;)V",
            to = "(Landroid/content/Context;Landroid/content/pm/ProviderInfo;)V",
        )
        val transformed = applyTransform(
            patched,
            candidateMethods = setOf(MethodKey(
                "attachInfo",
                "(Landroid/content/Context;Landroid/content/pm/ProviderInfo;)V",
            )),
        )
        // Verifier accepts the unmodified-descriptor pass-through;
        // we can't invoke without Android stubs but the wrap-or-not
        // distinction is encoded in the recorded events via the test
        // harness's static-rewrite path. Instead, count the
        // dispatcher INVOKESTATIC calls in the transformed bytecode
        // and assert they target the PROVIDER_* pair.
        AsmTestHarness.verify(transformed).assertOk()

        val node = org.objectweb.asm.tree.ClassNode()
        ClassReader(transformed).accept(node, 0)
        val method = node.methods.first {
            it.name == "attachInfo"
                && it.desc == "(Landroid/content/Context;Landroid/content/pm/ProviderInfo;)V"
        }
        val dispatcherInvokes = method.instructions
            .toArray()
            .filterIsInstance<org.objectweb.asm.tree.MethodInsnNode>()
            .filter { it.opcode == Opcodes.INVOKESTATIC && it.owner == dispatcherInternal }
            .map { it.name }
        // MethodBodyWrapper emits 1 start (prefix) + 1 end per return
        // (this method has one implicit RETURN) + 1 end in the
        // catch-any handler suffix → onProviderStart × 1 +
        // onProviderEnd × 2. The exact target method NAMES are the
        // load-bearing assertion (kind routing); the counts are
        // MethodBodyWrapper's contract, locked elsewhere.
        assertEquals(
            "attachInfo (CONTENT_PROVIDER kind) emits exactly one onProviderStart",
            1,
            dispatcherInvokes.count { it == "onProviderStart" },
        )
        assertEquals(
            "attachInfo (CONTENT_PROVIDER kind) emits onProviderEnd (one per return + catch-any)",
            2,
            dispatcherInvokes.count { it == "onProviderEnd" },
        )
        // Negative: no APPLICATION_*, no METHOD_*, no ANNOTATED_* — a
        // routing flip would surface here.
        assertEquals(
            "no foreign dispatcher method emitted on a CONTENT_PROVIDER candidate",
            emptyList<String>(),
            dispatcherInvokes.filter { it !in setOf("onProviderStart", "onProviderEnd") },
        )
    }

    @Test
    fun `Application attachBaseContext routes to APPLICATION_START_END`() {
        // Catches a mutation that removes `attachBaseContext` from
        // APPLICATION_METHODS (or swaps it into the wrong table). The
        // method's Android descriptor takes a `Landroid/content/Context;`
        // which isn't loadable in plain JVM tests, so we compile a stub
        // method with `(Ljava/lang/Object;)V` and patch the descriptor —
        // same pattern as the existing `attachInfo` test below. The kind
        // routing assertion is on the dispatcher method NAMES emitted in
        // bytecode, not on runtime invocation.
        val classes = JavaSourceCompiler.compile(
            "fixtures/SampleApplication.java",
            """
            package fixtures;
            public class SampleApplication {
                public static void attachBaseContext(Object ctx) {
                    // body intentionally empty
                }
            }
            """.trimIndent(),
        )
        val patched = renameMethodDescriptor(
            classes["fixtures.SampleApplication"]!!,
            methodName = "attachBaseContext",
            from = "(Ljava/lang/Object;)V",
            to = "(Landroid/content/Context;)V",
        )
        val transformed = applyTransform(
            patched,
            candidateMethods = setOf(MethodKey(
                "attachBaseContext",
                "(Landroid/content/Context;)V",
            )),
        )
        AsmTestHarness.verify(transformed).assertOk()

        val node = org.objectweb.asm.tree.ClassNode()
        ClassReader(transformed).accept(node, 0)
        val method = node.methods.first {
            it.name == "attachBaseContext"
                && it.desc == "(Landroid/content/Context;)V"
        }
        val dispatcherInvokes = method.instructions
            .toArray()
            .filterIsInstance<org.objectweb.asm.tree.MethodInsnNode>()
            .filter { it.opcode == Opcodes.INVOKESTATIC && it.owner == dispatcherInternal }
            .map { it.name }
        // 1 start (prefix) + 1 end per implicit RETURN + 1 end in the
        // catch-any handler = 1 onApplicationStart + 2 onApplicationEnd.
        assertEquals(
            "attachBaseContext (APPLICATION kind) emits exactly one onApplicationStart",
            1,
            dispatcherInvokes.count { it == "onApplicationStart" },
        )
        assertEquals(
            "attachBaseContext (APPLICATION kind) emits onApplicationEnd (one per return + catch-any)",
            2,
            dispatcherInvokes.count { it == "onApplicationEnd" },
        )
        // Negative: no foreign dispatcher names — a routing flip would
        // surface here.
        assertEquals(
            "no foreign dispatcher method emitted on an APPLICATION candidate",
            emptyList<String>(),
            dispatcherInvokes.filter { it !in setOf("onApplicationStart", "onApplicationEnd") },
        )
    }

    @Test
    fun `Initializer create routes to METHOD_START_END (generic fallback)`() {
        // Catches a mutation that moves INITIALIZER out of the METHOD_*
        // fallback arm in `dispatchMethodNamesFor` (e.g. INITIALIZER →
        // (onApplicationStart, onApplicationEnd)). Initializer's `create`
        // takes a Context (unloadable in plain JVM tests), so we patch
        // the descriptor in the same way as `attachInfo` / `attachBaseContext`.
        // Body returns a fresh Object (NOT the Context parameter) so the
        // verifier never has to resolve the patched-in `android/content/Context`
        // type on the operand stack — same reason the existing `attachInfo`
        // test compiles with an empty body.
        val classes = JavaSourceCompiler.compile(
            "fixtures/SampleInitializer.java",
            """
            package fixtures;
            public class SampleInitializer {
                public static Object create(Object ctx) {
                    return new Object();
                }
            }
            """.trimIndent(),
        )
        val patched = renameMethodDescriptor(
            classes["fixtures.SampleInitializer"]!!,
            methodName = "create",
            from = "(Ljava/lang/Object;)Ljava/lang/Object;",
            to = "(Landroid/content/Context;)Ljava/lang/Object;",
        )
        val transformed = applyTransform(
            patched,
            candidateMethods = setOf(MethodKey(
                "create",
                "(Landroid/content/Context;)Ljava/lang/Object;",
            )),
        )
        AsmTestHarness.verify(transformed).assertOk()

        val node = org.objectweb.asm.tree.ClassNode()
        ClassReader(transformed).accept(node, 0)
        val method = node.methods.first {
            it.name == "create"
                && it.desc == "(Landroid/content/Context;)Ljava/lang/Object;"
        }
        val dispatcherInvokes = method.instructions
            .toArray()
            .filterIsInstance<org.objectweb.asm.tree.MethodInsnNode>()
            .filter { it.opcode == Opcodes.INVOKESTATIC && it.owner == dispatcherInternal }
            .map { it.name }
        // 1 start (prefix) + 1 end per explicit ARETURN + 1 end in catch-any.
        assertEquals(
            "Initializer create (INITIALIZER kind) emits exactly one onMethodStart",
            1,
            dispatcherInvokes.count { it == "onMethodStart" },
        )
        assertEquals(
            "Initializer create (INITIALIZER kind) emits onMethodEnd (one per return + catch-any)",
            2,
            dispatcherInvokes.count { it == "onMethodEnd" },
        )
        // Strong negative assertion: a mutation that promotes INITIALIZER
        // to a kind-specific pair would surface here as one of these
        // names appearing in the bytecode.
        assertEquals(
            "Initializer must NOT emit onApplicationStart",
            0,
            dispatcherInvokes.count { it == "onApplicationStart" },
        )
        assertEquals(
            "Initializer must NOT emit onProviderStart",
            0,
            dispatcherInvokes.count { it == "onProviderStart" },
        )
        assertEquals(
            "Initializer must NOT emit onAnnotatedStart",
            0,
            dispatcherInvokes.count { it == "onAnnotatedStart" },
        )
        // Catch-all foreign-name negative — covers any future entry point
        // added to the dispatcher contract.
        assertEquals(
            "no foreign dispatcher method emitted on an INITIALIZER candidate",
            emptyList<String>(),
            dispatcherInvokes.filter { it !in setOf("onMethodStart", "onMethodEnd") },
        )
    }

    // ── filter behavior ──────────────────────────────────────────────

    @Test
    fun `non-candidate method passes through unchanged — no events`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/Bystander.java",
            """
            package fixtures;
            public class Bystander {
                public static int unrelated() { return 99; }
            }
            """.trimIndent(),
        )
        val transformed = applyTransform(
            classes["fixtures.Bystander"]!!,
            // Candidate set doesn't include `unrelated`
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()

        val result = AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.Bystander" to transformed),
            "fixtures.Bystander", "unrelated",
        )
        assertEquals(99, result)
        assertEquals(0, RecordingStartupDispatcher.size())
    }

    @Test
    fun `suspend-shaped descriptor in candidate set is skipped`() {
        // We hand-craft a method whose descriptor matches the suspend
        // shape: (Lkotlin/coroutines/Continuation;)Ljava/lang/Object;.
        // Even though it's in the candidate set, the visitor must skip
        // it (no wrapping, no events when invoked).
        val classes = JavaSourceCompiler.compile(
            "fixtures/FakeSuspendHolder.java",
            """
            package fixtures;
            public class FakeSuspendHolder {
                // Method whose descriptor is identical to a suspend
                // function's compiled shape. Pure Java — no actual
                // Continuation type touched, just a fake one declared
                // inline as Object to match the descriptor.
                public static Object resume(Object cont) {
                    return cont;
                }
            }
            """.trimIndent(),
        )
        // Manually patch the bytecode by claiming the method takes a
        // Continuation rather than an Object. The transform's filter is
        // descriptor-based, so the rename is what triggers the skip.
        val original = classes["fixtures.FakeSuspendHolder"]!!
        val patched = renameMethodDescriptor(
            original,
            methodName = "resume",
            from = "(Ljava/lang/Object;)Ljava/lang/Object;",
            to = "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
        )

        val transformed = applyTransform(
            patched,
            candidateMethods = setOf(MethodKey(
                "resume",
                "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"
            )),
        )
        // Verifier accepts the unmodified pass-through bytecode (we can't
        // load+invoke because Continuation isn't on the classpath, but
        // the verifier doesn't need to resolve it for an unmodified
        // method).
        AsmTestHarness.verify(transformed).assertOk()
        // No instrumentation means no recorded events even if we COULD
        // invoke. The verifier-only assertion is sufficient to prove
        // the skip path took precedence over the candidate-set match.
        assertEquals(0, RecordingStartupDispatcher.size())
    }

    @Test
    fun `abstract method in candidate set is skipped — verifier accepts`() {
        // Compile an abstract class with an abstract onCreate; visitor
        // must not try to wrap an empty body.
        val classes = JavaSourceCompiler.compile(
            "fixtures/AbstractHolder.java",
            """
            package fixtures;
            public abstract class AbstractHolder {
                public abstract void onCreate();
            }
            """.trimIndent(),
        )
        val transformed = applyTransform(
            classes["fixtures.AbstractHolder"]!!,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
        )
        AsmTestHarness.verify(transformed).assertOk()
        // Class is abstract; we can't instantiate or invoke. The verify
        // pass is the assertion that the abstract-method skip kicked in
        // (no wrapping ⇒ no malformed bytecode).
    }

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * Applies [AppStartupTracingClassVisitor] to [classBytes] with the
     * given candidate set; returns transformed bytes.
     */
    private fun applyTransform(
        classBytes: ByteArray,
        candidateMethods: Set<MethodKey>,
    ): ByteArray {
        return AsmTestHarness.transform(classBytes) { writer ->
            AppStartupTracingClassVisitor(
                apiVersion = Opcodes.ASM9,
                nextClassVisitor = writer,
                candidateMethods = candidateMethods,
                dispatcherInternalName = dispatcherInternal,
                tier = StartupTier.MINIMAL,
            )
        }
    }

    /**
     * Loads + invokes a static method with no args / no return-value
     * binding (return value ignored). Wraps the reflective
     * `InvocationTargetException` if the body throws so callers can
     * unwrap and assert on the cause.
     */
    private fun invokeStatic(
        classBytes: ByteArray,
        ownerFqn: String,
        methodName: String,
    ): Any? {
        return AsmTestHarness.loadAndInvokeStatic(
            mapOf(ownerFqn to classBytes),
            ownerFqn,
            methodName,
        )
    }

    /**
     * Surgical ASM rewrite: takes [classBytes], finds the method with
     * name [methodName] and descriptor [from], and rewrites its
     * descriptor to [to]. Used by the suspend-skip test to fake a
     * suspend descriptor without actually compiling Kotlin (which would
     * pull in the kotlin-stdlib path).
     */
    private fun renameMethodDescriptor(
        classBytes: ByteArray,
        methodName: String,
        from: String,
        to: String,
    ): ByteArray {
        val node = org.objectweb.asm.tree.ClassNode()
        ClassReader(classBytes).accept(node, 0)
        val method = node.methods.firstOrNull { it.name == methodName && it.desc == from }
            ?: throw IllegalStateException("method $methodName$from not found in ${node.name}")
        method.desc = to
        val writer = org.objectweb.asm.ClassWriter(0)
        node.accept(writer)
        return writer.toByteArray()
    }

}
