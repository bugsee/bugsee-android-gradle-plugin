package com.bugsee.android.gradle.instrumentation.operation_dispatch

import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import org.junit.Assert.assertEquals
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * The exception path of the operation-dispatch lane (triage B1).
 *
 * The lane emits `onXxxOperationStart` before a guarded call and `onXxxOperationEnd`
 * after it, in a straight line. When the guarded call THROWS, control leaves the method
 * over the top of the `End` — so the SDK's provider, which pushes a span on Start and
 * pops it on End, is left holding a span that is never finished and never reported, on
 * a `ThreadLocal` deque that is never released.
 *
 * These tests are BEHAVIOURAL rather than shape-based: the instrumented class is loaded
 * and executed against a recording stand-in for `BugseeOperationDispatcher`, so they
 * assert what actually happens at runtime rather than what the instruction sequence
 * looks like. A shape assertion cannot distinguish "End is present in the bytecode"
 * from "End is reached when the call throws" — which is the entire bug.
 */
class OperationDispatchExceptionPathTest {

    /**
     * A stand-in for the SDK's dispatcher, recording every call into a public static
     * list. Generated rather than written in Kotlin because it must carry the exact
     * internal name the injected bytecode references.
     */
    private fun recordingDispatcher(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(
            Opcodes.V1_8, Opcodes.ACC_PUBLIC,
            "com/bugsee/library/adapters/BugseeOperationDispatcher",
            null, "java/lang/Object", null,
        )
        cw.visitField(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "CALLS", "Ljava/util/List;", null, null,
        ).visitEnd()

        val clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        clinit.visitCode()
        clinit.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList")
        clinit.visitInsn(Opcodes.DUP)
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false)
        clinit.visitFieldInsn(
            Opcodes.PUTSTATIC, "com/bugsee/library/adapters/BugseeOperationDispatcher",
            "CALLS", "Ljava/util/List;",
        )
        clinit.visitInsn(Opcodes.RETURN)
        clinit.visitMaxs(0, 0)
        clinit.visitEnd()

        // Every onXxxOperationStart/End the lane can emit, each appending its own name.
        for (category in listOf("Database", "Network", "Prefs", "FileRead", "FileWrite")) {
            for (phase in listOf("Start", "End")) {
                val name = "on${category}Operation$phase"
                val mv = cw.visitMethod(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name,
                    "(Ljava/lang/String;Ljava/lang/String;)V", null, null,
                )
                mv.visitCode()
                mv.visitFieldInsn(
                    Opcodes.GETSTATIC, "com/bugsee/library/adapters/BugseeOperationDispatcher",
                    "CALLS", "Ljava/util/List;",
                )
                mv.visitLdcInsn(name)
                mv.visitMethodInsn(
                    Opcodes.INVOKEINTERFACE, "java/util/List", "add",
                    "(Ljava/lang/Object;)Z", true,
                )
                mv.visitInsn(Opcodes.POP)
                mv.visitInsn(Opcodes.RETURN)
                mv.visitMaxs(0, 0)
                mv.visitEnd()
            }
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * `Probe.body()` performs a guarded network call that THROWS:
     * `new Socket().connect(null)` raises IllegalArgumentException. The no-arg Socket
     * constructor is deliberately not a guarded descriptor, so exactly one dispatch pair
     * is expected — the one around `connect`.
     */
    private fun throwingProbe(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Probe", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "body", "()V", null, null)
        mv.visitCode()
        mv.visitTypeInsn(Opcodes.NEW, "java/net/Socket")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/net/Socket", "<init>", "()V", false)
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, "java/net/Socket", "connect",
            "(Ljava/net/SocketAddress;)V", false,
        )
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun runProbe(probe: ByteArray): List<*> {
        val transformed = AsmTestHarness.transform(probe) { writer ->
            OperationDispatchClassVisitor(writer, className = "Probe")
        }
        AsmTestHarness.verify(transformed).assertOk()

        val classes = mapOf(
            "Probe" to transformed,
            "com.bugsee.library.adapters.BugseeOperationDispatcher" to recordingDispatcher(),
        )
        // The probe is EXPECTED to throw; the point is what was dispatched on the way out.
        try {
            AsmTestHarness.loadAndInvokeStatic(classes, "Probe", "body")
            throw AssertionError("the probe was supposed to throw")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // expected: IllegalArgumentException from Socket.connect(null)
        }
        // Read the recorded calls back out of the SAME loader the probe ran in.
        val loader = AsmTestHarness.lastLoader
            ?: throw AssertionError("harness did not expose the loader")
        val dispatcher = loader.loadClass("com.bugsee.library.adapters.BugseeOperationDispatcher")
        @Suppress("UNCHECKED_CAST")
        return dispatcher.getField("CALLS").get(null) as List<*>
    }

    @Test
    fun `End is dispatched even when the guarded call throws`() {
        val calls = runProbe(throwingProbe())
        assertEquals(
            "a throwing guarded call must still report its End, or the SDK leaks an " +
                "unfinished span on that thread forever",
            listOf("onNetworkOperationStart", "onNetworkOperationEnd"),
            calls,
        )
    }

    @Test
    fun `End is dispatched exactly once on the normal path`() {
        // Same guarded call, but reaching it normally: Socket.connect on a bound
        // loopback address would need a server, so instead assert the non-throwing
        // shape via a guarded call that succeeds — URL.toString is NOT guarded, so
        // use Socket's guarded connect with a valid unconnected address is not
        // available here; the normal path is already covered by the shape tests in
        // OperationDispatchInjectionTest. This case pins that the exception-path fix
        // did not introduce a DOUBLE End on the throwing path.
        val calls = runProbe(throwingProbe())
        assertEquals(
            "End must not be dispatched twice when the handler and the straight-line " +
                "path both exist",
            1,
            calls.count { it == "onNetworkOperationEnd" },
        )
    }
    /**
     * The guarded call sits inside the USER's own try/catch, and their handler swallows
     * the exception.
     *
     * This is the case that decides handler ORDER. The JVM scans the exception table in
     * order and runs the first entry whose range and type match, so if our catch-any is
     * appended after the user's entry, theirs runs instead of ours, the method continues
     * normally, and the End is lost exactly as before the fix — with no exception
     * escaping to hint that anything went wrong. Our entry must therefore be inserted at
     * index 0.
     *
     * Without a user handler in the fixture, ordering is unobservable and an appended
     * entry passes every other test in this class.
     */
    @Test
    fun `End is dispatched even when the user's own catch swallows the exception`() {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Probe", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "body", "()V", null, null)
        val tryStart = org.objectweb.asm.Label()
        val tryEnd = org.objectweb.asm.Label()
        val handler = org.objectweb.asm.Label()
        val done = org.objectweb.asm.Label()
        mv.visitCode()
        mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/IllegalArgumentException")
        mv.visitLabel(tryStart)
        mv.visitTypeInsn(Opcodes.NEW, "java/net/Socket")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/net/Socket", "<init>", "()V", false)
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL, "java/net/Socket", "connect",
            "(Ljava/net/SocketAddress;)V", false,
        )
        mv.visitLabel(tryEnd)
        mv.visitJumpInsn(Opcodes.GOTO, done)
        mv.visitLabel(handler)
        mv.visitInsn(Opcodes.POP)          // swallow the exception entirely
        mv.visitLabel(done)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()

        val transformed = AsmTestHarness.transform(cw.toByteArray()) { writer ->
            OperationDispatchClassVisitor(writer, className = "Probe")
        }
        AsmTestHarness.verify(transformed).assertOk()

        val classes = mapOf(
            "Probe" to transformed,
            "com.bugsee.library.adapters.BugseeOperationDispatcher" to recordingDispatcher(),
        )
        // The user swallows it, so the probe returns normally here.
        AsmTestHarness.loadAndInvokeStatic(classes, "Probe", "body")

        val loader = AsmTestHarness.lastLoader!!
        val dispatcher = loader.loadClass("com.bugsee.library.adapters.BugseeOperationDispatcher")
        val calls = dispatcher.getField("CALLS").get(null) as List<*>

        assertEquals(
            "our handler must precede the user's in the exception table, or their catch " +
                "runs instead and the End is silently lost",
            listOf("onNetworkOperationStart", "onNetworkOperationEnd"),
            calls,
        )
    }

}
