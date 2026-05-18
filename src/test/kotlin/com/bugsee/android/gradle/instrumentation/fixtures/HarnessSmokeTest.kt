package com.bugsee.android.gradle.instrumentation.fixtures

import com.bugsee.test.fixtures.RecordingStartupDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Smoke tests for the Phase 4 ASM test harness.
 *
 * Verifies each piece of the harness works on its own, and that the
 * whole pipeline (compile → transform → verify → load → invoke) chains
 * cleanly using a trivial no-op transform. The real bytecode rewrites
 * land in Phase 5+ and reuse exactly this harness.
 */
class HarnessSmokeTest {

    @Before
    fun setUp() {
        RecordingStartupDispatcher.reset()
    }

    @After
    fun tearDown() {
        RecordingStartupDispatcher.reset()
    }

    // ── JavaSourceCompiler ───────────────────────────────────────────

    @Test
    fun `Java compile, load, invoke static method`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/Greeter.java",
            """
            package fixtures;
            public class Greeter {
                public static String greet(String name) {
                    return "Hi, " + name;
                }
            }
            """.trimIndent(),
        )
        val result = AsmTestHarness.loadAndInvokeStatic(
            classes,
            "fixtures.Greeter",
            "greet",
            arrayOf(String::class.java),
            arrayOf("World"),
        )
        assertEquals("Hi, World", result)
    }

    @Test
    fun `Java compile produces bytes for the named class`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/Empty.java",
            """
            package fixtures;
            public class Empty {}
            """.trimIndent(),
        )
        val bytes = classes["fixtures.Empty"]
        assertNotNull(bytes)
        assertTrue("non-empty bytes", bytes!!.isNotEmpty())
        // Without this assertion, the test would pass for any garbage
        // ByteArray of length >0. Parse the produced bytes through ClassReader
        // and confirm the internal class name matches what we asked the
        // compiler to produce — proves the bytes are a real .class for
        // the named class, not random data.
        val reader = ClassReader(bytes)
        assertEquals("fixtures/Empty", reader.className)
    }

    // ── KotlinSourceCompiler ─────────────────────────────────────────

    @Test
    fun `Kotlin compile, load, invoke static method`() {
        val classes = KotlinSourceCompiler.compile(
            "Counter.kt",
            """
            package fixtures
            object Counter {
                @JvmStatic
                fun doubled(x: Int): Int = x * 2
            }
            """.trimIndent(),
        )
        val result = AsmTestHarness.loadAndInvokeStatic(
            classes,
            "fixtures.Counter",
            "doubled",
            arrayOf(Int::class.javaPrimitiveType!!),
            arrayOf(7),
        )
        assertEquals(14, result)
    }

    // ── AsmTestHarness.transform + verify ────────────────────────────

    @Test
    fun `no-op transform on Java class still passes verification`() {
        val classes = JavaSourceCompiler.compile(
            "fixtures/Identity.java",
            """
            package fixtures;
            public class Identity {
                public static int echo(int x) { return x; }
            }
            """.trimIndent(),
        )
        // Trip a flag inside the no-op visitor's visit() so we can prove
        // the visitor actually saw the class header — without this, a
        // silently broken transform() that returned input bytes unchanged
        // would still pass `verify` and `assertEquals` below.
        val visited = AtomicBoolean(false)
        val transformed = AsmTestHarness.transform(classes["fixtures.Identity"]!!) { writer ->
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String?,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    visited.set(true)
                    super.visit(version, access, name, signature, superName, interfaces)
                }
            }
        }
        assertTrue("visitor must have observed class header", visited.get())
        AsmTestHarness.verify(transformed).assertOk()

        // Behavior preserved
        val result = AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.Identity" to transformed),
            "fixtures.Identity",
            "echo",
            arrayOf(Int::class.javaPrimitiveType!!),
            arrayOf(42),
        )
        assertEquals(42, result)
    }

    @Test
    fun `verify catches stack underflow via CheckClassAdapter`() {
        // Hand-crafted class with POP from an empty stack AND visitMaxs(0,
        // 0) so the verifier's max-stack check fires immediately. This
        // exercises the CheckClassAdapter path.
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V11, Opcodes.ACC_PUBLIC,
            "fixtures/Bogus", null, "java/lang/Object", null,
        )
        val mv = writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "broken", "()V", null, null,
        )
        mv.visitCode()
        mv.visitInsn(Opcodes.POP) // pop from empty stack — invalid
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        writer.visitEnd()

        val result = AsmTestHarness.verify(writer.toByteArray())
        // Pin the diagnostic specifically to the CheckClassAdapter path
        // (or its fatal-throw branch when the verifier bails). A loose
        // `||` against fatalThrowable that also accepts a successful
        // empty result would still pass for code that silently dropped
        // the verifier. Tighten to require an actual structural
        // diagnostic from CheckClassAdapter.
        assertTrue("expected CheckClassAdapter diagnostic, got none: $result",
            result.checkAdapterOutput.isNotBlank() ||
                    result.fatalThrowable != null)
        assertFalse("verifier must NOT silently accept invalid bytecode",
            result.isOk)
    }

    @Test
    fun `verify catches dataflow bug via Analyzer when maxs are correct`() {
        // Targeted test for the Analyzer + SimpleVerifier path: declare
        // visitMaxs so CheckClassAdapter's structural checks pass, then
        // call `Integer.intValue()` on a `String` receiver — type-incorrect
        // dataflow that only `SimpleVerifier` (not `BasicInterpreter`,
        // which erases everything to "REFERENCE") will reject. The
        // verifier resolves both classes through the test classloader
        // and notices `String` is not assignable to `Integer`. This is
        // the class of bug Phase 5+'s try/finally injection could
        // produce if a wrap left an unexpected receiver type on the
        // stack ahead of an injected dispatcher call. `ATHROW` of a
        // non-Throwable was the original choice here but `BasicVerifier`
        // (which `SimpleVerifier` extends) only checks "is reference"
        // for `ATHROW`, so that fixture would slip through both verifiers
        // — replaced with a virtual-call mismatch that SimpleVerifier
        // genuinely catches.
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V11, Opcodes.ACC_PUBLIC,
            "fixtures/AnalyzerOnly", null, "java/lang/Object", null,
        )
        val mv = writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "broken", "()V", null, null,
        )
        mv.visitCode()
        mv.visitLdcInsn("a string")
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Integer",
            "intValue",
            "()I",
            false,
        )
        mv.visitInsn(Opcodes.POP)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 0)
        mv.visitEnd()
        writer.visitEnd()

        val result = AsmTestHarness.verify(writer.toByteArray())
        assertTrue("expected analyzer diagnostic, got none: $result",
                result.analyzerErrors.isNotEmpty())
    }

    // ── RecordingStartupDispatcher round-trip ────────────────────────

    @Test
    fun `RecordingStartupDispatcher captures calls on the calling thread`() {
        RecordingStartupDispatcher.onMethodStart("App.onCreate")
        RecordingStartupDispatcher.onCallStart("Foo.init")
        RecordingStartupDispatcher.onCallEnd("Foo.init")
        RecordingStartupDispatcher.onMethodEnd("App.onCreate")

        val events = RecordingStartupDispatcher.events()
        assertEquals(4, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_START, events[0].kind)
        assertEquals("App.onCreate", events[0].siteId)
        assertEquals(RecordingStartupDispatcher.Kind.CALL_END, events[2].kind)
        assertEquals(Thread.currentThread().id, events[0].threadId)
    }

    @Test
    fun `INVOKESTATIC to RecordingStartupDispatcher resolves through InMemoryClassLoader`() {
        // Hand-craft a class whose static method() emits one INVOKESTATIC
        // against RecordingStartupDispatcher.onMethodStart("smoke.site").
        // Proves the dispatcher FQN resolves from transformed/loaded
        // bytecode — the exact resolution path Phase 5+ will rely on.
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(
            Opcodes.V11, Opcodes.ACC_PUBLIC,
            "fixtures/Emitter", null, "java/lang/Object", null,
        )
        val mv: MethodVisitor = writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "emit", "()V", null, null,
        )
        mv.visitCode()
        mv.visitLdcInsn("smoke.site")
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "com/bugsee/test/fixtures/RecordingStartupDispatcher",
            "onMethodStart",
            "(Ljava/lang/String;)V",
            false,
        )
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        writer.visitEnd()

        val bytes = writer.toByteArray()
        AsmTestHarness.verify(bytes).assertOk()

        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.Emitter" to bytes),
            "fixtures.Emitter",
            "emit",
        )

        val events = RecordingStartupDispatcher.events()
        assertEquals(1, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.METHOD_START, events[0].kind)
        assertEquals("smoke.site", events[0].siteId)
        // Pin the threadId so we prove the dispatcher recorded on the
        // calling thread (the InMemoryClassLoader invokes statically on
        // the test thread — no async dispatch should sneak in here).
        assertEquals(Thread.currentThread().id, events[0].threadId)
    }

    // ── onLoopStart / onLoopEnd dispatcher coverage ──────────────────

    @Test
    fun `RecordingStartupDispatcher records onLoopStart and onLoopEnd directly`() {
        // Symmetry coverage: the round-trip test above pinned the
        // method / call kinds but never exercised the loop kinds. A
        // typo in the recording dispatcher's enum-to-kind mapping for
        // onLoopStart/onLoopEnd would silently slip past every prior
        // test in this class.
        RecordingStartupDispatcher.onLoopStart("loop.site")
        RecordingStartupDispatcher.onLoopEnd("loop.site")

        val events = RecordingStartupDispatcher.events()
        assertEquals(2, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.LOOP_START, events[0].kind)
        assertEquals("loop.site", events[0].siteId)
        assertEquals(RecordingStartupDispatcher.Kind.LOOP_END, events[1].kind)
        assertEquals("loop.site", events[1].siteId)
        assertEquals(Thread.currentThread().id, events[0].threadId)
    }

    @Test
    fun `INVOKESTATIC to dispatcher onLoopStart and onLoopEnd resolves through InMemoryClassLoader`() {
        // Parallel to the existing onMethodStart resolution test —
        // proves the bytecode-resolved path for onLoopStart and
        // onLoopEnd also flows through the InMemoryClassLoader. Each
        // injected INVOKESTATIC matches the signature
        // `(Ljava/lang/String;)V`, identical to the production
        // dispatcher's loop entry points.
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(
            Opcodes.V11, Opcodes.ACC_PUBLIC,
            "fixtures/LoopEmitter", null, "java/lang/Object", null,
        )
        val mv: MethodVisitor = writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "emit", "()V", null, null,
        )
        mv.visitCode()
        mv.visitLdcInsn("loop.site")
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "com/bugsee/test/fixtures/RecordingStartupDispatcher",
            "onLoopStart",
            "(Ljava/lang/String;)V",
            false,
        )
        mv.visitLdcInsn("loop.site")
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "com/bugsee/test/fixtures/RecordingStartupDispatcher",
            "onLoopEnd",
            "(Ljava/lang/String;)V",
            false,
        )
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        writer.visitEnd()

        val bytes = writer.toByteArray()
        AsmTestHarness.verify(bytes).assertOk()
        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.LoopEmitter" to bytes),
            "fixtures.LoopEmitter",
            "emit",
        )

        val events = RecordingStartupDispatcher.events()
        assertEquals(2, events.size)
        assertEquals(RecordingStartupDispatcher.Kind.LOOP_START, events[0].kind)
        assertEquals("loop.site", events[0].siteId)
        assertEquals(RecordingStartupDispatcher.Kind.LOOP_END, events[1].kind)
        assertEquals("loop.site", events[1].siteId)
    }
}
