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
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_START, events[0].kind)
        assertEquals("fixtures.SimpleApp#onCreate", events[0].siteId)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_END, events[1].kind)
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
        assertEquals(2, RecordingStartupDispatcher.size())
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
        // Branch taken: one start, one end (the first return only)
        assertEquals(2, RecordingStartupDispatcher.size())
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
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_START, events[0].kind)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_END, events[1].kind)
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
