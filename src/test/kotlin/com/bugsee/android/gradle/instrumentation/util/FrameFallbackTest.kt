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
        reports.clear()
        FrameFallbackContext.reportSink = { reports.add(it) }
    }

    @After
    fun tearDown() {
        FrameFallbackContext.reportSink = null
    }

    /**
     * `c ? new FileInputStream(..) : new ByteArrayInputStream(..)` — after the op-dispatch
     * remap the join merges `BugseeFileInputStream` with `ByteArrayInputStream`. Resolving
     * that common superclass needs the Bugsee type to be loadable, which (as for an SDK class
     * missing from the hierarchy resolver) it is not here: only the TRANSFORMED method breaks.
     */
    // NOTE on realism: on AGP the realistic trigger is MethodTooLargeException (see the
    // dedicated test and the consumer-app run in the PR). AGP's own writer resolves unknown
    // classes leniently, so the hierarchy-based failures below are ARTIFICIAL: they use the plain
    // ClassWriter from AsmTestHarness, whose Class.forName cannot load the Bugsee type. They
    // exercise the mechanism (trial, fallback, once-per-method report), not a production shape.
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
        assertTrue(reports[0], reports[0].contains("excludes.add(\"fixtures.Branchy\")"))
        assertTrue(reports[0], reports[0].contains("excludes.add(\"fixtures\")"))
        assertTrue(reports[0], reports[0].contains("could not be written"))
    }

    @Test
    fun `op-dispatch - the warning is not de-duplicated across processing runs`() {
        // A daemon outlives builds: a process-wide once-set would silence later builds that still
        // leave the method uninstrumented. Each processing run reports its own failures.
        val original = compile("fixtures/Branchy.java", branchySource, "fixtures.Branchy")
        opDispatchWithFallback(original, "fixtures.Branchy")
        opDispatchWithFallback(original, "fixtures.Branchy")
        assertEquals(2, reports.size)
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
     * KNOWN LIMIT, pinned on purpose (modelled with a version-51 class, where JSR is invalid but
     * an unframeable original is what is being simulated): when the ORIGINAL method already defeats frame
     * recomputation (JSR/RET) there is nothing to fall back to, because the lane has already
     * buffered the method and so forfeited ASM's copy fast path. The build still fails — with
     * the attributed, actionable [InstrumentationException] rather than a bare ASM trace.
     */
    @Test
    fun `op-dispatch - a method that cannot be framed even unmodified still fails`() {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V1_7, Opcodes.ACC_PUBLIC, "fixtures/Jsr", null, "java/lang/Object", null)
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
            fail("expected the build to still fail")
        } catch (e: InstrumentationException) {
            // Version 51 is not AGP's retry signal, so the failure is attributed. The lane left
            // the method unchanged, so no trial ran and no fallback was attempted: no report.
            assertTrue(e.message!!, e.message!!.contains("fixtures.Jsr.m()V"))
            assertTrue(reports.toString(), reports.isEmpty())
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

    /**
     * Regression (review): near the 64KB limit ASM sizes `ldc`/`ldc_w` by constant-pool index. A
     * trial writer with a FRESH pool shrinks the 100 original `ldc_w` to `ldc`, so it measures the
     * method 100 bytes smaller than the real writer does, passes, and the real writer then throws.
     * The trial must be seeded from the same reader as the real writer.
     */
    @Test
    fun `op-dispatch - too-large detection uses the real constant pool (ldc_w sizing)`() {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "a/b/h", null, "java/lang/Object", null)
        // Filler: pushes the pool past 256 entries so later constants need ldc_w.
        val f = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "f", "()V", null, null)
        f.visitCode()
        repeat(300) { f.visitLdcInsn("filler$it"); f.visitInsn(Opcodes.POP) }
        f.visitInsn(Opcodes.RETURN)
        f.visitMaxs(0, 0)
        f.visitEnd()
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "a", "(Ljava/net/URL;)V", null, null)
        mv.visitCode()
        repeat(100) { mv.visitLdcInsn("hi$it"); mv.visitInsn(Opcodes.POP) }   // 100 x (ldc_w + pop) = 400 bytes
        repeat(65524 - 400) { mv.visitInsn(Opcodes.NOP) }
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/URL", "openStream", "()Ljava/io/InputStream;", false)
        mv.visitInsn(Opcodes.POP)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        val original = cw.toByteArray()

        try {
            opDispatchPlain(original, "a.b.h")
            fail("baseline: expected MethodTooLargeException")
        } catch (expected: org.objectweb.asm.MethodTooLargeException) {
        }

        val out = opDispatchWithFallback(original, "a.b.h")

        assertFalse(String(out, Charsets.ISO_8859_1).contains("com/bugsee"))
        assertEquals(signature(insns(original, "a")), signature(insns(out, "a")))
        assertEquals(1, reports.size)
    }

    /**
     * Regression (review P3): the real pool also holds what the lane added to EARLIER methods. When
     * that crosses 256, a constant the lane adds later costs `ldc_w` (3 bytes) in the real writer but
     * `ldc` (2) in a trial whose pool is only the INPUT pool. Needs an input pool just below 256, an
     * earlier instrumented method, and a near-limit method after it.
     */
    @Test
    fun `op-dispatch - too-large detection accounts for constants added to earlier methods`() {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "a/b/k", null, "java/lang/Object", null)
        val f = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "f", "()V", null, null)
        f.visitCode()
        repeat(110) { f.visitLdcInsn("filler$it"); f.visitInsn(Opcodes.POP) }
        f.visitInsn(Opcodes.RETURN)
        f.visitMaxs(0, 0)
        f.visitEnd()
        // Earlier method the lane instruments: adds pool entries (dispatcher class + refs + strings).
        val b = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "b", "(Lokhttp3/Call;)V", null, null)
        b.visitCode()
        b.visitVarInsn(Opcodes.ALOAD, 0)
        b.visitMethodInsn(Opcodes.INVOKEINTERFACE, "okhttp3/Call", "execute", "()Lokhttp3/Response;", true)
        b.visitInsn(Opcodes.POP)
        b.visitInsn(Opcodes.RETURN)
        b.visitMaxs(0, 0)
        b.visitEnd()
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "a", "(Ljava/net/URL;)V", null, null)
        mv.visitCode()
        repeat(65505) { mv.visitInsn(Opcodes.NOP) }
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/URL", "openStream", "()Ljava/io/InputStream;", false)
        mv.visitInsn(Opcodes.POP)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        val original = cw.toByteArray()

        val out = opDispatchWithFallback(original, "a.b.k")

        assertEquals(signature(insns(original, "a")), signature(insns(out, "a")))
        // The sibling stayed instrumented.
        assertTrue(String(out, Charsets.ISO_8859_1).contains("BugseeOperationDispatcher"))
    }

    /**
     * The pool-overflow case of the known limits: a method whose instrumented form would push the
     * constant pool past 65,535 is reverted by the padded trial (`ClassTooLargeException` comes from
     * the trial's `toByteArray`), not left to fail the real writer. Calls the lane installer directly,
     * i.e. below the whole-class skip that `MinifiedClassSkip.shouldSkip` applies in a real build.
     */
    @Test
    fun `op-dispatch - a method that would overflow the constant pool is left unmodified`() {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "a/b/p", null, "java/lang/Object", null)
        for (m in 0 until 4) {
            val f = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "f$m", "()V", null, null)
            f.visitCode()
            repeat(7_900) { f.visitLdcInsn("s${m}_$it"); f.visitInsn(Opcodes.POP) }
            f.visitInsn(Opcodes.RETURN)
            f.visitMaxs(0, 0)
            f.visitEnd()
        }
        // Earlier method the lane instruments (Network): it adds the dispatcher's constants to the REAL pool.
        val b = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "b", "(Lokhttp3/Call;)V", null, null)
        b.visitCode()
        b.visitVarInsn(Opcodes.ALOAD, 0)
        b.visitMethodInsn(Opcodes.INVOKEINTERFACE, "okhttp3/Call", "execute", "()Lokhttp3/Response;", true)
        b.visitInsn(Opcodes.POP)
        b.visitInsn(Opcodes.RETURN)
        b.visitMaxs(0, 0)
        b.visitEnd()
        // Later method (Prefs): needs constants the earlier one did not add.
        val mv = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "a", "(Landroid/content/SharedPreferences\$Editor;)V", null, null,
        )
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "android/content/SharedPreferences\$Editor", "commit", "()Z", true)
        mv.visitInsn(Opcodes.POP)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        // Top up the INPUT pool so that `a` fits on top of it alone, but not on top of what the lane
        // already added for `b`. Only the trial's padding to the real pool's size sees the difference.
        var i = 0
        while (cw.newUTF8("pad$i") < POOL_TARGET) i++
        cw.visitEnd()
        val original = cw.toByteArray()

        try {
            opDispatchPlain(original, "a.b.p")
            fail("baseline: expected ClassTooLargeException")
        } catch (expected: org.objectweb.asm.ClassTooLargeException) {
        }

        val out = opDispatchWithFallback(original, "a.b.p")

        assertEquals(signature(insns(original, "a")), signature(insns(out, "a")))
        assertEquals(1, reports.size)
        assertTrue(reports[0], reports[0].contains("ClassTooLargeException"))
        // The earlier method stayed instrumented.
        assertTrue(String(out, Charsets.ISO_8859_1).contains("BugseeOperationDispatcher"))
    }

    private companion object {
        const val POOL_TARGET = 65_515
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

        // ARTIFICIAL (not a production shape): the first hierarchy lookup fails on purpose (the
        // trial's), later ones succeed. It exercises the startup-lane wiring only, and models a failure
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

    // ── class-file-version cases: what AGP does with JSR/RET ─────────────────────────

    /** `m(URL)`: a guarded call (instrumented by op-dispatch) followed by a JSR/RET subroutine. */
    private fun jsrClass(version: Int): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(version, Opcodes.ACC_PUBLIC, "fixtures/Old", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "m", "(Ljava/net/URL;)V", null, null)
        val sub = Label()
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/URL", "openStream", "()Ljava/io/InputStream;", false)
        mv.visitInsn(Opcodes.POP)
        mv.visitJumpInsn(Opcodes.JSR, sub)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitLabel(sub)
        mv.visitVarInsn(Opcodes.ASTORE, 1)
        mv.visitVarInsn(Opcodes.RET, 1)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun usesDispatcher(bytes: ByteArray) =
        String(bytes, Charsets.ISO_8859_1).contains("BugseeOperationDispatcher")

    /**
     * AGP writes class files older than Java 6 with COMPUTE_MAXS, so JSR/RET is legal there and
     * main instruments the method. The trial must use the same mode, not COMPUTE_FRAMES.
     */
    @Test
    fun `version-48 JSR class is still instrumented (trial mirrors AGP's COMPUTE_MAXS)`() {
        val original = jsrClass(Opcodes.V1_4)

        val out = AsmTestHarness.transform(original, computeFrames = false) { writer ->
            FrameFallbackLane.install(writer, "fixtures.Old") { next -> OperationDispatchClassVisitor(next, "fixtures.Old") }
        }

        assertTrue("method must be instrumented", usesDispatcher(out))
        assertTrue("no false warning: $reports", reports.isEmpty())
        // Loads and verifies (initialize=true forces linking/verification).
        Class.forName(
            "fixtures.Old", true,
            com.bugsee.android.gradle.instrumentation.fixtures.InMemoryClassLoader(mapOf("fixtures.Old" to out)),
        )
    }

    /**
     * Version 50: AGP first writes with COMPUTE_FRAMES and retries the whole class with
     * COMPUTE_MAXS iff it sees the BARE `IllegalArgumentException("JSR/RET are not supported
     * with computeFrames option")`. It must neither be wrapped nor swallowed-and-reported.
     */
    @Test
    fun `version-50 JSR class - the retry signal is re-thrown bare, and the retry instruments it`() {
        val original = jsrClass(Opcodes.V1_6)

        try {
            opDispatchWithFallback(original, "fixtures.Old")
            fail("first attempt (COMPUTE_FRAMES) must raise the retry signal")
        } catch (e: IllegalArgumentException) {
            assertEquals("JSR/RET are not supported with computeFrames option", e.message)
        }
        assertTrue("no 'left uninstrumented' report before AGP's retry: $reports", reports.isEmpty())

        // AGP's retry: same class, COMPUTE_MAXS writer.
        val out = AsmTestHarness.transform(original, computeFrames = false) { writer ->
            FrameFallbackLane.install(writer, "fixtures.Old") { next -> OperationDispatchClassVisitor(next, "fixtures.Old") }
        }
        assertTrue(usesDispatcher(out))
        assertTrue(reports.isEmpty())
        Class.forName(
            "fixtures.Old", true,
            com.bugsee.android.gradle.instrumentation.fixtures.InMemoryClassLoader(mapOf("fixtures.Old" to out)),
        )
    }

    @Test
    fun `report distinguishes a lane failure from a write failure`() {
        FrameFallbackContext.report("a/b/C", "m", "()V", IllegalStateException("boom"), laneThrew = true)
        FrameFallbackContext.report("C", "m", "()V", IllegalStateException("boom"), laneThrew = false)
        assertTrue(reports[0], reports[0].contains("the instrumentation itself failed on it"))
        assertTrue(reports[0], reports[0].indexOf("excludes.add(\"a.b.C\")") < reports[0].indexOf("excludes.add(\"a.b\")"))
        assertTrue(reports[0], reports[0].contains("disables ALL Bugsee instrumentation"))
        assertTrue(reports[1], reports[1].contains("could not be written"))
        assertFalse("default package has no distinct prefix form", reports[1].contains("whole package"))
    }
}
