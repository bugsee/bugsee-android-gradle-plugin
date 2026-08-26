package com.bugsee.android.gradle.instrumentation.thread

import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import com.bugsee.android.gradle.instrumentation.fixtures.InMemoryClassLoader
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the bytecode shape produced by [ThreadClassVisitor]:
 *
 *  - `registerThread()` injected as the FIRST instruction of an existing
 *    `run()V`;
 *  - a synthetic `run()V` generated for DIRECT `android.os.HandlerThread`
 *    subclasses that don't override `run()`;
 *  - NO synthetic `run()V` for INDIRECT HandlerThread subclasses. Before
 *    this gate, generating one could override a `final void run()` declared
 *    by the intermediate class — a load-time `VerifyError` in the host app
 *    (proven below with a real classloader).
 *
 * android.os.HandlerThread is not on the test classpath, so classes are
 * hand-rolled with ASM (same pattern as OkHttpClassVisitorTest); the
 * final-override crash proof uses a plain `java.lang.Thread`-rooted
 * hierarchy, which exercises exactly the same JVM final-override rule.
 */
class ThreadClassVisitorTest {

    private val adapterClass = "com/bugsee/library/adapters/BugseeThreadAdapter"

    /** Builds a class [name] extending [superName], optionally with a run()V whose body just returns. */
    private fun classBytes(
        name: String,
        superName: String,
        withRun: Boolean,
        runAccess: Int = Opcodes.ACC_PUBLIC,
        superHasNoArgCtor: Boolean = true,
    ): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, superName, null)
        // <init> calling super()
        if (superHasNoArgCtor) {
            val ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
            ctor.visitCode()
            ctor.visitVarInsn(Opcodes.ALOAD, 0)
            ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
            ctor.visitInsn(Opcodes.RETURN)
            ctor.visitMaxs(1, 1)
            ctor.visitEnd()
        }
        if (withRun) {
            val mv = cw.visitMethod(runAccess, "run", "()V", null, null)
            mv.visitCode()
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 1)
            mv.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun transform(bytes: ByteArray, extendsHandlerThread: Boolean): ByteArray =
        AsmTestHarness.transform(bytes) { writer ->
            ThreadClassVisitor(writer, extendsHandlerThread, "fixtures.Test")
        }

    private fun findMethod(bytes: ByteArray, name: String, desc: String): MethodNode? {
        val cn = ClassNode()
        ClassReader(bytes).accept(cn, 0)
        return cn.methods.firstOrNull { it.name == name && it.desc == desc }
    }

    @Test
    fun `existing run() gets registerThread injected as first instruction`() {
        val bytes = classBytes("Worker", "java/lang/Thread", withRun = true)
        val out = transform(bytes, extendsHandlerThread = false)

        val run = findMethod(out, "run", "()V")
        assertNotNull(run)
        val firstReal = run.instructions.toArray().first { it.opcode >= 0 }
        val call = firstReal as? MethodInsnNode
            ?: fail("first instruction of run() is not a method call: $firstReal")
        assertEquals(Opcodes.INVOKESTATIC, call.opcode)
        assertEquals(adapterClass, call.owner)
        assertEquals("registerThread", call.name)
        assertEquals("()V", call.desc)
        AsmTestHarness.verify(out).assertOk()
    }

    @Test
    fun `direct HandlerThread subclass without run() gets a synthetic run()`() {
        val bytes = classBytes(
            "MyHandlerThread", "android/os/HandlerThread",
            withRun = false, superHasNoArgCtor = false,
        )
        val out = transform(bytes, extendsHandlerThread = true)

        val run = findMethod(out, "run", "()V")
        assertNotNull(run, "synthetic run() must be generated for a direct HandlerThread subclass")
        val calls = run.instructions.toArray().filterIsInstance<MethodInsnNode>()
        assertEquals(2, calls.size)
        assertEquals("registerThread", calls[0].name)
        assertEquals(adapterClass, calls[0].owner)
        assertEquals(Opcodes.INVOKESPECIAL, calls[1].opcode)
        assertEquals("android/os/HandlerThread", calls[1].owner)
        assertEquals("run", calls[1].name)
    }

    /**
     * The `!hasRunMethod` half of the generation gate.
     *
     * A DIRECT `android.os.HandlerThread` subclass that ALREADY declares
     * `run()V` satisfies both other conjuncts (`extendsHandlerThread` and the
     * immediate-super check), so only the "no run() of its own" condition
     * stops a second `run()V` from being emitted. Two `run()V` members with
     * the same name+descriptor is a malformed class file: ART/the JVM reject
     * it at load time (`ClassFormatError: Duplicate method name`), and AGP's
     * frame recomputation can blow up on it first — a build- or launch-time
     * failure for every host app with such a class.
     *
     * Asserted at the strongest available level: EXACTLY one `run()V`, and it
     * is the real one (its original `RETURN` body is intact) with exactly one
     * `registerThread()` call injected at the front.
     */
    @Test
    fun `direct HandlerThread subclass WITH run() keeps exactly one run() and is not duplicated`() {
        val bytes = classBytes(
            "MyHandlerThread", "android/os/HandlerThread",
            withRun = true, superHasNoArgCtor = false,
        )
        val out = transform(bytes, extendsHandlerThread = true)

        val cn = ClassNode()
        ClassReader(out).accept(cn, 0)
        val runs = cn.methods.filter { it.name == "run" && it.desc == "()V" }
        assertEquals(
            1, runs.size,
            "a synthetic run() must NOT be added when the class already declares one — " +
                "a duplicate run()V is a load-time ClassFormatError in the host app",
        )

        // The surviving run() is the class's own, with registerThread() injected
        // at the entry — not a synthetic super.run() forwarder that replaced it.
        val run = runs.single()
        val calls = run.instructions.toArray().filterIsInstance<MethodInsnNode>()
        assertEquals(
            1, calls.size,
            "expected exactly one injected call in the existing run(); got " +
                calls.map { it.owner + "." + it.name },
        )
        assertEquals(Opcodes.INVOKESTATIC, calls[0].opcode)
        assertEquals(adapterClass, calls[0].owner)
        assertEquals("registerThread", calls[0].name)
        assertEquals("()V", calls[0].desc)
        val firstReal = run.instructions.toArray().first { it.opcode >= 0 }
        assertEquals(
            calls[0] as org.objectweb.asm.tree.AbstractInsnNode, firstReal,
            "registerThread() must be the FIRST instruction of the existing run()",
        )
        AsmTestHarness.verify(out).assertOk()
    }

    @Test
    fun `indirect HandlerThread subclass without run() gets NO synthetic run()`() {
        // class Base extends HandlerThread {}  (instrumented separately, gets its own run())
        // class Child extends Base {}          (must NOT get a synthetic run())
        val bytes = classBytes(
            "ChildHandlerThread", "com/example/BaseHandlerThread",
            withRun = false, superHasNoArgCtor = false,
        )
        val out = transform(bytes, extendsHandlerThread = true)

        assertNull(
            findMethod(out, "run", "()V"),
            "no synthetic run() may be generated when the immediate superclass is not " +
                "android.os.HandlerThread — the intermediate class may declare run() final",
        )
    }

    /**
     * The crash the indirect-subclass gate prevents, demonstrated with a
     * real classloader: a subclass of a class declaring `final void run()`
     * that receives a synthetic `run()` fails CLASS LOADING with a
     * [VerifyError] — the host app dies even if the thread is never started.
     *
     * Uses a Thread-rooted hierarchy (HandlerThread is not on the test
     * classpath); the JVM's final-override rule is identical.
     */
    @Test
    fun `synthetic run over a final run() is a load-time VerifyError - the hazard the gate removes`() {
        val base = classBytes(
            "fixtures/FinalRunBase", "java/lang/Thread",
            withRun = true, runAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
        )
        val childRaw = classBytes("fixtures/FinalRunChild", "fixtures/FinalRunBase", withRun = false)

        // Simulate the PRE-FIX behavior by generating the synthetic run()
        // unconditionally (what the visitor used to do for any transitive
        // HandlerThread subclass): registerThread() + super.run().
        val cw = ClassWriter(0)
        val reader = ClassReader(childRaw)
        reader.accept(object : org.objectweb.asm.ClassVisitor(Opcodes.ASM9, cw) {
            override fun visitEnd() {
                val mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null)
                mv.visitCode()
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, adapterClass, "registerThread", "()V", false)
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "fixtures/FinalRunBase", "run", "()V", false)
                mv.visitInsn(Opcodes.RETURN)
                mv.visitMaxs(1, 1)
                mv.visitEnd()
                super.visitEnd()
            }
        }, 0)
        val childWithSyntheticRun = cw.toByteArray()

        val brokenLoader = InMemoryClassLoader(
            mapOf(
                "fixtures.FinalRunBase" to base,
                "fixtures.FinalRunChild" to childWithSyntheticRun,
            )
        )
        try {
            Class.forName("fixtures.FinalRunChild", true, brokenLoader)
            fail("expected a VerifyError: synthetic run() overrides a final method")
        } catch (expected: VerifyError) {
            // This is the production crash mode the gate prevents.
        }

        // And the FIXED visitor output loads fine: no synthetic run() emitted.
        val fixedChild = transform(childRaw, extendsHandlerThread = true)
        val fixedLoader = InMemoryClassLoader(
            mapOf(
                "fixtures.FinalRunBase" to base,
                "fixtures.FinalRunChild" to fixedChild,
            )
        )
        val loaded = Class.forName("fixtures.FinalRunChild", true, fixedLoader)
        assertTrue(Thread::class.java.isAssignableFrom(loaded))
    }
}
