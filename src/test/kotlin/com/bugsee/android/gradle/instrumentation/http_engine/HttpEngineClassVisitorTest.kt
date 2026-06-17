package com.bugsee.android.gradle.instrumentation.http_engine

import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the HttpEngine instrumentation transform.
 *
 * The production bug this pins (LAST-BETA Blocking-2): the AGP
 * `setAsmFramesComputationMode` was `COPY_FRAMES`, which copies the
 * original method's `max_stack` VERBATIM. [HttpEngineMethodVisitor]
 * injects `DUP` / `DUP2` / `DUP_X2` / `DUP2_X1` at the wrapped call
 * sites, GROWING the operand stack — so a copied (un-recomputed)
 * `max_stack` is too small and ART/JVM rejects the class with a
 * `VerifyError` at load. The fix flips the mode to
 * `COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS`, which recomputes
 * max_stack/max_locals (and frames) for the instrumented method.
 *
 * Why this file hand-rolls bytecode and asserts `max_stack` directly
 * rather than reusing the shared AsmTestHarness verify(): the injected
 * calls reference the `android.net.http` API and
 * `com/bugsee/library/adapters/BugseeHttpEngineAdapter`, none of which
 * are on the gradle-plugin's test classpath — so ASM's `SimpleVerifier`
 * (which reflectively loads referenced types) cannot run here. And,
 * crucially, any harness that recomputes frames with `COMPUTE_FRAMES`
 * would MASK the very bug we're testing (it would silently fix the
 * max_stack). So we contrast the two computation modes head-on:
 *
 *  - [transformCopyFrames]  — `ClassWriter(0)`: preserves the original
 *    method's declared `max_stack` verbatim, modelling the BROKEN
 *    COPY_FRAMES build. For a call site whose injection grows the stack,
 *    this leaves `max_stack` too small (the VerifyError shape).
 *  - [transformComputeMaxs] — `ClassWriter(COMPUTE_MAXS)`: recomputes
 *    `max_stack` from the (instrumented) instruction list, modelling the
 *    FIXED COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS build.
 *
 * For each injected call site we assert (a) COMPUTE_MAXS yields a
 * max_stack >= the simulated peak depth — the fix is sound — and (b)
 * COPY_FRAMES yields a max_stack strictly LESS than the peak — proving
 * the bug is real and the test is not vacuous.
 */
class HttpEngineClassVisitorTest {

    private val adapter = "com/bugsee/library/adapters/BugseeHttpEngineAdapter"
    private val builderClass = "android/net/http/UrlRequest\$Builder"

    /**
     * Build a class `Probe` with a single `void body()` whose body is
     * supplied by [build]. [declaredMaxStack] is the operand-stack depth
     * the ORIGINAL (pre-instrumentation) method needs — a COPY_FRAMES
     * build carries exactly this onto the instrumented method.
     */
    private fun probeBytes(
        declaredMaxStack: Int,
        build: (MethodVisitor) -> Unit,
    ): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Probe", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "body", "()V", null, null)
        mv.visitCode()
        build(mv)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(declaredMaxStack, 4)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** Models the BROKEN COPY_FRAMES mode: declared max_stack survives verbatim. */
    private fun transformCopyFrames(bytes: ByteArray): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(0)
        reader.accept(HttpEngineClassVisitor(writer, "Probe"), 0)
        return writer.toByteArray()
    }

    /** Models the FIXED mode: max_stack recomputed from the instrumented body. */
    private fun transformComputeMaxs(bytes: ByteArray): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        reader.accept(HttpEngineClassVisitor(writer, "Probe"), 0)
        return writer.toByteArray()
    }

    private fun bodyMethod(bytes: ByteArray): MethodNode {
        val cn = ClassNode()
        ClassReader(bytes).accept(cn, 0)
        return cn.methods.first { it.name == "body" } as MethodNode
    }

    /**
     * Simulate the operand-stack depth across [method]'s instruction list
     * and return the peak. The probe bodies are straight-line (no
     * branches), so a linear scan is exact — and it accounts for the
     * injected DUP-family / SWAP instructions because it runs over the
     * TRANSFORMED instruction list.
     */
    private fun peakStackDepth(method: MethodNode): Int {
        var depth = 0
        var peak = 0
        for (insn in method.instructions.toArray()) {
            depth += stackDelta(insn)
            require(depth >= 0) { "stack underflow near opcode ${insn.opcode}" }
            if (depth > peak) peak = depth
        }
        return peak
    }

    private fun stackDelta(insn: AbstractInsnNode): Int = when (insn) {
        is MethodInsnNode -> methodCallDelta(insn)
        else -> when (insn.opcode) {
            Opcodes.ACONST_NULL, Opcodes.DUP, Opcodes.DUP_X1, Opcodes.DUP_X2 -> 1
            Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2 -> 2
            Opcodes.POP -> -1
            Opcodes.POP2 -> -2
            else -> 0 // SWAP, RETURN, labels/frames/line nodes (opcode -1)
        }
    }

    private fun methodCallDelta(insn: MethodInsnNode): Int {
        val argSlots = org.objectweb.asm.Type.getArgumentTypes(insn.desc).sumOf { it.size }
        val retSlots = org.objectweb.asm.Type.getReturnType(insn.desc).size // void → 0
        val receiver = if (insn.opcode == Opcodes.INVOKESTATIC) 0 else 1
        return retSlots - argSlots - receiver
    }

    private fun assertAdapterInjected(method: MethodNode) {
        val injected = method.instructions.toArray().any {
            it is MethodInsnNode && it.owner == adapter
        }
        assertTrue(injected, "expected a $adapter INVOKESTATIC to be injected")
    }

    /**
     * Core assertion shared by every stack-growing call site:
     *  - injection happened,
     *  - FIXED mode (COMPUTE_MAXS) declares a sufficient max_stack,
     *  - BROKEN mode (COPY_FRAMES) declares an INSUFFICIENT max_stack
     *    (proving the bug is real and the fix is load-bearing).
     */
    private fun assertGrowsStackAndFixRecomputes(probe: ByteArray) {
        val fixed = bodyMethod(transformComputeMaxs(probe))
        assertAdapterInjected(fixed)
        val peak = peakStackDepth(fixed)
        assertTrue(
            fixed.maxStack >= peak,
            "FIXED (COMPUTE_MAXS) must declare max_stack >= peak $peak; got ${fixed.maxStack}",
        )

        val broken = bodyMethod(transformCopyFrames(probe))
        val brokenPeak = peakStackDepth(broken)
        assertTrue(
            broken.maxStack < brokenPeak,
            "control: BROKEN (COPY_FRAMES) must leave max_stack (${broken.maxStack}) BELOW the " +
                "injected peak ($brokenPeak) — if this ever holds, the injection no longer grows " +
                "the stack and the test stops guarding the VerifyError",
        )
    }

    // ── one case per injected call site ──────────────────────────────

    @Test
    fun `setHttpMethod injection - fix recomputes max_stack, copy-frames would underflow`() {
        // ..., builder, method (depth 2); visitor injects DUP +
        // onSetHttpMethod(String)V → transient depth 3.
        val bytes = probeBytes(declaredMaxStack = 2) { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, builderClass, "setHttpMethod",
                "(Ljava/lang/String;)L$builderClass;", false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        assertGrowsStackAndFixRecomputes(bytes)
    }

    @Test
    fun `addHeader injection - fix recomputes max_stack, copy-frames would underflow`() {
        // ..., builder, name, value (depth 3); visitor injects DUP2 +
        // onAddHeader(String,String)V → transient depth 5.
        val bytes = probeBytes(declaredMaxStack = 3) { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, builderClass, "addHeader",
                "(Ljava/lang/String;Ljava/lang/String;)L$builderClass;", false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        assertGrowsStackAndFixRecomputes(bytes)
    }

    @Test
    fun `newUrlRequestBuilder injection - fix recomputes max_stack, copy-frames would underflow`() {
        // The heaviest case — DUP_X2 / POP / DUP2_X1 / POP reshuffle the
        // (engine, url, executor, callback) quartet; this is exactly the
        // sequence whose stale max_stack tripped the original VerifyError.
        val bytes = probeBytes(declaredMaxStack = 4) { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL) // engine
            mv.visitInsn(Opcodes.ACONST_NULL) // url
            mv.visitInsn(Opcodes.ACONST_NULL) // executor
            mv.visitInsn(Opcodes.ACONST_NULL) // callback
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "android/net/http/HttpEngine", "newUrlRequestBuilder",
                "(Ljava/lang/String;Ljava/util/concurrent/Executor;" +
                    "Landroid/net/http/UrlRequest\$Callback;)Landroid/net/http/UrlRequest\$Builder;",
                false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        assertGrowsStackAndFixRecomputes(bytes)
    }

    @Test
    fun `setUploadDataProvider injection - fix yields a sound max_stack`() {
        // ..., builder, provider, executor (depth 3); visitor injects
        // SWAP + wrapUploadDataProvider(...) + SWAP. SWAP doesn't grow the
        // stack, but the fixed mode must still produce a sound max_stack
        // and the adapter call must be injected.
        val bytes = probeBytes(declaredMaxStack = 3) { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, builderClass, "setUploadDataProvider",
                "(Landroid/net/http/UploadDataProvider;Ljava/util/concurrent/Executor;)L$builderClass;",
                false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val fixed = bodyMethod(transformComputeMaxs(bytes))
        assertAdapterInjected(fixed)
        val peak = peakStackDepth(fixed)
        assertTrue(
            fixed.maxStack >= peak,
            "setUploadDataProvider: max_stack ${fixed.maxStack} must cover peak $peak",
        )
    }

    @Test
    fun `start injection - fix yields a sound max_stack`() {
        val bytes = probeBytes(declaredMaxStack = 1) { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "android/net/http/UrlRequest", "start", "()V", false,
            )
        }
        val fixed = bodyMethod(transformComputeMaxs(bytes))
        assertAdapterInjected(fixed)
        val peak = peakStackDepth(fixed)
        assertTrue(fixed.maxStack >= peak, "start: max_stack ${fixed.maxStack} must cover peak $peak")
    }

    // ── sanity: the visitor is a no-op on non-HttpEngine calls ───────

    @Test
    fun `non-HttpEngine calls are passed through with no adapter injection`() {
        val bytes = probeBytes(declaredMaxStack = 1) { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val method = bodyMethod(transformComputeMaxs(bytes))
        val adapterCalls = method.instructions.toArray().count {
            it is MethodInsnNode && it.owner == adapter
        }
        assertEquals(0, adapterCalls, "no adapter call must be injected for unrelated INVOKEs")
    }
}
