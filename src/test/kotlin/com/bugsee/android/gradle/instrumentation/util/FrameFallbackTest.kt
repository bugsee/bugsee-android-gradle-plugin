package com.bugsee.android.gradle.instrumentation.util

import com.bugsee.android.gradle.StartupTier
import com.bugsee.android.gradle.instrumentation.app_startup_tracing.AppStartupTracingClassVisitor
import com.bugsee.android.gradle.instrumentation.app_startup_tracing.MethodKey
import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import com.bugsee.android.gradle.instrumentation.fixtures.JavaSourceCompiler
import com.bugsee.android.gradle.instrumentation.operation_dispatch.OperationDispatchClassVisitor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * Per-method fallback for the buffering lanes (issue #43): a method whose transformed form
 * cannot have its frames computed is left UNINSTRUMENTED instead of failing the build.
 *
 * Every test drives the REAL lane through a `ClassWriter(COMPUTE_FRAMES)` the way AGP does
 * (writer at the end of the chain, `FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS`
 * lanes). The "baseline" assertion runs the same input WITHOUT the fallback and requires the
 * exception, which is what the pre-fix code did.
 */
class FrameFallbackTest {

    private val reports = mutableListOf<String>()

    @Before
    fun setUp() {
        FrameFallbackContext.resetReported()
        reports.clear()
        FrameFallbackContext.reportSink = { reports.add(it) }
    }

    @After
    fun tearDown() {
        FrameFallbackContext.reportSink = null
        FrameFallbackContext.resetReported()
    }

    /**
     * `c ? new FileInputStream(..) : new ByteArrayInputStream(..)` — after the op-dispatch
     * remap the join merges `BugseeFileInputStream` with `ByteArrayInputStream`. Resolving
     * that common superclass needs the Bugsee type to be loadable, which (as for an SDK class
     * missing from the hierarchy resolver) it is not here: only the TRANSFORMED method breaks.
     */
    private val branchySource = """
        package fixtures;
        import java.io.*;
        public class Branchy {
            public static int pick(boolean c, byte[] b) throws Exception {
                InputStream in = c ? new FileInputStream("/dev/null") : new ByteArrayInputStream(b);
                return in.read();
            }
        }
    """.trimIndent()

    private val plainSource = """
        package fixtures;
        import java.io.*;
        public class Plain {
            public static InputStream open(String p) throws Exception {
                return new FileInputStream(p);
            }
        }
    """.trimIndent()

    private fun compile(path: String, src: String, fqn: String): ByteArray =
        JavaSourceCompiler.compile(path, src)[fqn]!!

    private fun opDispatchWithFallback(bytes: ByteArray, name: String): ByteArray =
        AsmTestHarness.transform(bytes) { writer ->
            FrameFallbackLane.install(writer, name) { next -> OperationDispatchClassVisitor(next, name) }
        }

    private fun opDispatchPlain(bytes: ByteArray, name: String): ByteArray =
        AsmTestHarness.transform(bytes) { writer -> OperationDispatchClassVisitor(writer, name) }

    private fun insns(bytes: ByteArray, method: String): List<AbstractInsnNode> {
        val cn = ClassNode()
        ClassReader(bytes).accept(cn, 0)
        return cn.methods.first { it.name == method }.instructions.toList()
            .filter { it.opcode >= 0 }
    }

    private fun signature(nodes: List<AbstractInsnNode>): List<String> = nodes.map {
        when (it) {
            is MethodInsnNode -> "${it.opcode} ${it.owner}.${it.name}${it.desc}"
            is TypeInsnNode -> "${it.opcode} ${it.desc}"
            is VarInsnNode -> "${it.opcode} ${it.`var`}"
            else -> "${it.opcode}"
        }
    }

    @Test
    fun `op-dispatch - baseline without fallback fails the build on an unframeable transformed method`() {
        val original = compile("fixtures/Branchy.java", branchySource, "fixtures.Branchy")
        try {
            opDispatchPlain(original, "fixtures.Branchy")
            fail("expected frame computation to fail on the transformed method")
        } catch (expected: RuntimeException) {
            // TypeNotPresentException from getCommonSuperClass: what fails the app build on main.
        }
    }

    @Test
    fun `op-dispatch - unframeable transformed method is left uninstrumented and valid`() {
        val original = compile("fixtures/Branchy.java", branchySource, "fixtures.Branchy")

        val out = opDispatchWithFallback(original, "fixtures.Branchy")

        // Valid: JVM verifier + dataflow, and it still runs with the original semantics.
        AsmTestHarness.verify(out).assertOk()
        val result = AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.Branchy" to out), "fixtures.Branchy", "pick",
            arrayOf(Boolean::class.javaPrimitiveType!!, ByteArray::class.java),
            arrayOf(false, byteArrayOf(7)),
        )
        assertEquals(7, result)

        // Uninstrumented: no Bugsee reference anywhere, same instruction stream as the input.
        assertFalse("no Bugsee type may leak", String(out, Charsets.ISO_8859_1).contains("com/bugsee"))
        assertEquals(signature(insns(original, "pick")), signature(insns(out, "pick")))

        // Reported exactly once, naming the class and the escape hatch.
        assertEquals(1, reports.size)
        assertTrue(reports[0], reports[0].contains("fixtures.Branchy.pick"))
        assertTrue(reports[0], reports[0].contains("excludes.add(\"fixtures\")"))
    }

    @Test
    fun `op-dispatch - a failure is reported once even if the class is processed twice`() {
        val original = compile("fixtures/Branchy.java", branchySource, "fixtures.Branchy")
        opDispatchWithFallback(original, "fixtures.Branchy")
        opDispatchWithFallback(original, "fixtures.Branchy")
        assertEquals(1, reports.size)
    }

    @Test
    fun `op-dispatch - a normal class in the same lane is still instrumented`() {
        val original = compile("fixtures/Plain.java", plainSource, "fixtures.Plain")

        val out = opDispatchWithFallback(original, "fixtures.Plain")

        val news = insns(out, "open").filterIsInstance<TypeInsnNode>().map { it.desc }
        assertTrue(news.toString(), news.any { it.endsWith("BugseeFileInputStream") })
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `op-dispatch - only the failing method is reverted, its sibling stays instrumented`() {
        val src = """
            package fixtures;
            import java.io.*;
            public class Mixed {
                public static int pick(boolean c, byte[] b) throws Exception {
                    InputStream in = c ? new FileInputStream("/dev/null") : new ByteArrayInputStream(b);
                    return in.read();
                }
                public static InputStream open(String p) throws Exception {
                    return new FileInputStream(p);
                }
            }
        """.trimIndent()
        val original = compile("fixtures/Mixed.java", src, "fixtures.Mixed")

        val out = opDispatchWithFallback(original, "fixtures.Mixed")

        assertTrue(insns(out, "pick").filterIsInstance<TypeInsnNode>().none { it.desc.contains("Bugsee") })
        assertTrue(insns(out, "open").filterIsInstance<TypeInsnNode>().any { it.desc.endsWith("BugseeFileInputStream") })
        assertEquals(1, reports.size)
    }

    /**
     * KNOWN LIMIT, pinned on purpose: when the ORIGINAL method already defeats frame
     * recomputation (JSR/RET) there is nothing to fall back to, because the lane has already
     * buffered the method and so forfeited ASM's copy fast path. The build still fails — with
     * the attributed, actionable [InstrumentationException] rather than a bare ASM trace.
     */
    @Test
    fun `op-dispatch - a method that cannot be framed even unmodified still fails with attribution`() {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V1_4, Opcodes.ACC_PUBLIC, "fixtures/Jsr", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "m", "()V", null, null)
        val sub = Label()
        mv.visitCode()
        mv.visitJumpInsn(Opcodes.JSR, sub)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitLabel(sub)
        mv.visitVarInsn(Opcodes.ASTORE, 0)
        mv.visitVarInsn(Opcodes.RET, 0)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()

        try {
            opDispatchWithFallback(cw.toByteArray(), "fixtures.Jsr")
            fail("expected an attributed InstrumentationException")
        } catch (e: InstrumentationException) {
            assertTrue(e.message!!, e.message!!.contains("fixtures.Jsr.m()V"))
        }
    }

    /**
     * A ProGuard-style huge method (65,530 bytes of code) holding one guarded call: valid as
     * is, but the dispatch wrapper pushes it past the 65,535 limit. ASM raises
     * MethodTooLargeException from `toByteArray`, i.e. OUTSIDE the visitor chain, so the
     * per-method trial (which also calls `toByteArray`) is what catches it.
     */
    @Test
    fun `op-dispatch - a method that only becomes too large after instrumentation is left unmodified`() {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "a/b/e", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "a", "(Ljava/net/URL;)V", null, null)
        mv.visitCode()
        repeat(65524) { mv.visitInsn(Opcodes.NOP) }
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/URL", "openStream", "()Ljava/io/InputStream;", false)
        mv.visitInsn(Opcodes.POP)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        val original = cw.toByteArray()

        try {
            opDispatchPlain(original, "a.b.e")
            fail("baseline: expected MethodTooLargeException")
        } catch (expected: org.objectweb.asm.MethodTooLargeException) {
        }

        val out = opDispatchWithFallback(original, "a.b.e")

        assertFalse(String(out, Charsets.ISO_8859_1).contains("com/bugsee"))
        assertEquals(signature(insns(original, "a")), signature(insns(out, "a")))
        assertEquals(1, reports.size)
        assertTrue(reports[0], reports[0].contains("excludes.add(\"a.b\")"))
    }

    @Test
    fun `startup lane - a kind-candidate method whose wrapped form cannot be written falls back to the original`() {
        val src = """
            package fixtures;
            public class App {
                public static void onCreate() {
                    Object o = System.nanoTime() > 0 ? "a" : Integer.valueOf(1);
                    o.hashCode();
                }
            }
        """.trimIndent()
        val original = compile("fixtures/App.java", src, "fixtures.App")

        // The first hierarchy lookup fails (the trial's), later ones succeed: models a failure
        // that only the transformed form triggers, which the real writer then does not see.
        val reader = ClassReader(original)
        var calls = 0
        val writer = object : ClassWriter(reader, COMPUTE_FRAMES) {
            override fun getCommonSuperClass(type1: String, type2: String): String {
                if (calls++ == 0) throw TypeNotPresentException(type1, null)
                return super.getCommonSuperClass(type1, type2)
            }
        }
        val visitor: ClassVisitor = AppStartupTracingClassVisitor(
            apiVersion = Opcodes.ASM9,
            nextClassVisitor = writer,
            candidateMethods = setOf(MethodKey("onCreate", "()V")),
            dispatcherInternalName = "com/bugsee/test/fixtures/RecordingStartupDispatcher",
            tier = StartupTier.MINIMAL,
            className = "fixtures.App",
        )
        reader.accept(visitor, ClassReader.EXPAND_FRAMES)
        val out = writer.toByteArray()

        AsmTestHarness.verify(out).assertOk()
        assertFalse(String(out, Charsets.ISO_8859_1).contains("RecordingStartupDispatcher"))
        assertEquals(signature(insns(original, "onCreate")), signature(insns(out, "onCreate")))
        assertEquals(1, reports.size)
        assertTrue(reports[0], reports[0].contains("fixtures.App.onCreate()V"))
    }

    @Test
    fun `sameCode distinguishes an instrumented method from an untouched one`() {
        val a = compile("fixtures/Plain.java", plainSource, "fixtures.Plain")
        val b = opDispatchWithFallback(a, "fixtures.Plain")
        fun node(bytes: ByteArray) =
            ClassNode().also { ClassReader(bytes).accept(it, 0) }.methods.first { it.name == "open" }
        assertTrue(FrameFallbackContext.sameCode(node(a), node(a)))
        assertFalse(FrameFallbackContext.sameCode(node(a), node(b)))
    }
}
