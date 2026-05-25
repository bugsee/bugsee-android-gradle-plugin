package com.bugsee.android.gradle.instrumentation.operation_dispatch

import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pin the **dispatch-injection** half of [OperationDispatchClassVisitor].
 *
 * The companion [OperationDispatchClassVisitorTest] covers the
 * type-remapping half (FileInputStream / FileOutputStream → wrapper
 * types). Until this file existed, the dispatch-injection branches
 * (RandomAccessFile, URL, Socket, OkHttp Call.execute, SQLiteDatabase,
 * SharedPreferences\$Editor) had **zero** coverage. A mutation that
 * stubbed `resolveDispatchInfo` to always return null would have
 * passed the prior 5-test suite — silently disabling APM
 * instrumentation for every guarded operation except File I/O.
 *
 * Class references like `android/database/sqlite/SQLiteDatabase` and
 * `okhttp3/Call` aren't on the gradle-plugin's test classpath, so we
 * can't compile Java sources that exercise these calls through
 * [com.bugsee.android.gradle.instrumentation.fixtures.JavaSourceCompiler].
 * Instead we hand-roll the bytecode via ASM: build a tiny class with
 * a single static method containing the call instruction we want to
 * test, push it through the visitor, then inspect the resulting
 * instruction list for the injected dispatcher Start/End pair.
 *
 * The inspection grain is "INVOKESTATIC `BugseeOperationDispatcher.
 * onXxxOperationStart(String, String)` appears immediately before the
 * original call AND `onXxxOperationEnd` immediately after." Both the
 * **category** (encoded in the dispatcher method name) and the
 * **operation** (encoded as the first LDC arg) are pinned per call.
 */
class OperationDispatchInjectionTest {

    private val dispatcher = "com/bugsee/library/adapters/BugseeOperationDispatcher"

    /**
     * Build a class `Probe` with a single static method `body()V` whose
     * body is supplied by [build]. The visitor accepts ANY owner FQN
     * (it doesn't load referenced types), which is exactly why we can
     * reference SQLiteDatabase / okhttp3.Call etc. that aren't on the
     * test classpath.
     */
    private fun probeBytes(build: (org.objectweb.asm.MethodVisitor) -> Unit): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Probe", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "body", "()V", null, null)
        mv.visitCode()
        build(mv)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun transformOpDispatch(bytes: ByteArray): ByteArray =
        AsmTestHarness.transform(bytes) { writer ->
            OperationDispatchClassVisitor(writer)
        }

    private fun methodInsns(bytes: ByteArray, methodName: String): List<Any> {
        val cn = ClassNode()
        ClassReader(bytes).accept(cn, 0)
        val m = cn.methods.first { it.name == methodName } as MethodNode
        return m.instructions.toList()
    }

    /**
     * Locate the index of the `INVOKE*` instruction matching the
     * supplied owner+name pair. Returns -1 if not found.
     */
    private fun indexOfCall(insns: List<Any>, owner: String, name: String): Int =
        insns.indexOfFirst { it is MethodInsnNode && it.owner == owner && it.name == name }

    /**
     * Assert dispatcher Start/End wrap exists around the call at
     * `callIdx` with the given category (encoded in the dispatcher
     * method name as `on<category>OperationStart/End`) and operation
     * string (the first LDC arg).
     */
    private fun assertDispatcherWrap(
        insns: List<Any>,
        callIdx: Int,
        category: String,
        operation: String,
    ) {
        require(callIdx > 0) { "call must have a predecessor (the dispatcher start sequence)" }

        // The injected Start sequence is: LDC <operation>; ACONST_NULL;
        // INVOKESTATIC <dispatcher>.on<Category>OperationStart. That's
        // 3 instructions immediately BEFORE the call.
        val startInvokeIdx = callIdx - 1
        val startAconstIdx = callIdx - 2
        val startLdcIdx = callIdx - 3
        val startInvoke = insns[startInvokeIdx] as? MethodInsnNode
        assertEquals(dispatcher, startInvoke?.owner, "Start INVOKESTATIC owner")
        assertEquals(
            "on${category}OperationStart",
            startInvoke?.name,
            "Start INVOKESTATIC method (category mismatch)",
        )
        assertEquals(
            "(Ljava/lang/String;Ljava/lang/String;)V",
            startInvoke?.desc,
            "Start INVOKESTATIC descriptor",
        )

        val startLdc = insns[startLdcIdx] as? LdcInsnNode
        assertEquals(
            operation,
            startLdc?.cst,
            "Start operation string (first LDC arg)",
        )

        // The injected End sequence: LDC <operation>; ACONST_NULL;
        // INVOKESTATIC <dispatcher>.on<Category>OperationEnd. That's
        // 3 instructions immediately AFTER the call.
        val endLdcIdx = callIdx + 1
        val endInvokeIdx = callIdx + 3
        val endInvoke = insns[endInvokeIdx] as? MethodInsnNode
        assertEquals(dispatcher, endInvoke?.owner, "End INVOKESTATIC owner")
        assertEquals(
            "on${category}OperationEnd",
            endInvoke?.name,
            "End INVOKESTATIC method (category mismatch)",
        )
        val endLdc = insns[endLdcIdx] as? LdcInsnNode
        assertEquals(
            operation,
            endLdc?.cst,
            "End operation string (first LDC arg)",
        )
    }

    /**
     * Assert NO dispatcher INVOKESTATIC was injected anywhere in the
     * method — used by negative tests (descriptor not on the
     * dispatcher's accept list, or non-guarded methods on a guarded
     * owner).
     */
    private fun assertNoDispatcherWrap(insns: List<Any>) {
        val dispatcherCount = insns.count {
            it is MethodInsnNode && it.owner == dispatcher
        }
        assertEquals(
            0, dispatcherCount,
            "Expected no dispatcher INVOKESTATIC calls; got $dispatcherCount",
        )
    }

    // ── RandomAccessFile ─────────────────────────────────────────────

    @Test fun `new RandomAccessFile(File, String) is wrapped with FileRead read dispatch`() {
        val bytes = probeBytes { mv ->
            mv.visitTypeInsn(Opcodes.NEW, "java/io/RandomAccessFile")
            mv.visitInsn(Opcodes.DUP)
            mv.visitInsn(Opcodes.ACONST_NULL)  // File
            mv.visitInsn(Opcodes.ACONST_NULL)  // String mode
            mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/io/RandomAccessFile",
                "<init>",
                "(Ljava/io/File;Ljava/lang/String;)V",
                false,
            )
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        val callIdx = indexOfCall(insns, "java/io/RandomAccessFile", "<init>")
        assertTrue(callIdx > 0, "RandomAccessFile.<init> must still be present")
        assertDispatcherWrap(insns, callIdx, category = "FileRead", operation = "read")
    }

    @Test fun `new RandomAccessFile(String, String) is wrapped with FileRead read dispatch`() {
        val bytes = probeBytes { mv ->
            mv.visitTypeInsn(Opcodes.NEW, "java/io/RandomAccessFile")
            mv.visitInsn(Opcodes.DUP)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/io/RandomAccessFile",
                "<init>",
                "(Ljava/lang/String;Ljava/lang/String;)V",
                false,
            )
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        val callIdx = indexOfCall(insns, "java/io/RandomAccessFile", "<init>")
        assertDispatcherWrap(insns, callIdx, category = "FileRead", operation = "read")
    }

    @Test fun `RandomAccessFile constructor with unrecognized descriptor is NOT wrapped`() {
        // The dispatch table only matches two specific descriptors.
        // Other descriptors (e.g. the InputStream-taking variant on
        // hypothetical future JDK versions, or a user-defined
        // overload via reflection ASM stubs) must NOT be wrapped —
        // pin the descriptor-discrimination behavior.
        val bytes = probeBytes { mv ->
            mv.visitTypeInsn(Opcodes.NEW, "java/io/RandomAccessFile")
            mv.visitInsn(Opcodes.DUP)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/io/RandomAccessFile",
                "<init>",
                "(Ljava/io/InputStream;)V",  // not in the dispatch table
                false,
            )
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        assertNoDispatcherWrap(insns)
    }

    // ── URL (openConnection / openStream) ────────────────────────────

    @Test fun `URL openConnection is wrapped with Network connect dispatch`() {
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)  // URL receiver
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/net/URL",
                "openConnection",
                "()Ljava/net/URLConnection;",
                false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        val callIdx = indexOfCall(insns, "java/net/URL", "openConnection")
        assertDispatcherWrap(insns, callIdx, category = "Network", operation = "connect")
    }

    @Test fun `URL openStream is wrapped with Network connect dispatch`() {
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/net/URL",
                "openStream",
                "()Ljava/io/InputStream;",
                false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        val callIdx = indexOfCall(insns, "java/net/URL", "openStream")
        assertDispatcherWrap(insns, callIdx, category = "Network", operation = "connect")
    }

    @Test fun `URL toString is NOT wrapped (non-guarded method on guarded owner)`() {
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/net/URL",
                "toString",  // not on the guard list
                "()Ljava/lang/String;",
                false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        assertNoDispatcherWrap(insns)
    }

    // ── Socket (constructor + connect) ───────────────────────────────

    @Test fun `new Socket(String, int) is wrapped with Network connect dispatch`() {
        val bytes = probeBytes { mv ->
            mv.visitTypeInsn(Opcodes.NEW, "java/net/Socket")
            mv.visitInsn(Opcodes.DUP)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/net/Socket",
                "<init>",
                "(Ljava/lang/String;I)V",
                false,
            )
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        val callIdx = indexOfCall(insns, "java/net/Socket", "<init>")
        assertDispatcherWrap(insns, callIdx, category = "Network", operation = "connect")
    }

    @Test fun `new Socket(InetAddress, int) is wrapped with Network connect dispatch`() {
        val bytes = probeBytes { mv ->
            mv.visitTypeInsn(Opcodes.NEW, "java/net/Socket")
            mv.visitInsn(Opcodes.DUP)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/net/Socket",
                "<init>",
                "(Ljava/net/InetAddress;I)V",
                false,
            )
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        val callIdx = indexOfCall(insns, "java/net/Socket", "<init>")
        assertDispatcherWrap(insns, callIdx, category = "Network", operation = "connect")
    }

    @Test fun `Socket connect(SocketAddress) is wrapped with Network connect dispatch`() {
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)  // Socket
            mv.visitInsn(Opcodes.ACONST_NULL)  // SocketAddress
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/net/Socket",
                "connect",
                "(Ljava/net/SocketAddress;)V",
                false,
            )
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        val callIdx = indexOfCall(insns, "java/net/Socket", "connect")
        assertDispatcherWrap(insns, callIdx, category = "Network", operation = "connect")
    }

    @Test fun `Socket connect(SocketAddress, int timeout) is wrapped with Network connect dispatch`() {
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ICONST_0)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/net/Socket",
                "connect",
                "(Ljava/net/SocketAddress;I)V",
                false,
            )
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        val callIdx = indexOfCall(insns, "java/net/Socket", "connect")
        assertDispatcherWrap(insns, callIdx, category = "Network", operation = "connect")
    }

    @Test fun `Socket no-arg constructor is NOT wrapped (unguarded descriptor)`() {
        // The no-arg constructor doesn't actually connect — guard list
        // is descriptor-specific, only host+port variants count.
        val bytes = probeBytes { mv ->
            mv.visitTypeInsn(Opcodes.NEW, "java/net/Socket")
            mv.visitInsn(Opcodes.DUP)
            mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/net/Socket",
                "<init>",
                "()V",
                false,
            )
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        assertNoDispatcherWrap(insns)
    }

    // ── OkHttp Call.execute ──────────────────────────────────────────

    @Test fun `okhttp3 Call execute() is wrapped with Network execute dispatch`() {
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "okhttp3/Call",
                "execute",
                "()Lokhttp3/Response;",
                true,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        val callIdx = indexOfCall(insns, "okhttp3/Call", "execute")
        assertDispatcherWrap(insns, callIdx, category = "Network", operation = "execute")
    }

    @Test fun `okhttp3 Call enqueue is NOT wrapped (only sync execute is guarded)`() {
        // The dispatch contract is sync-only: enqueue's async
        // callback model can't be wrapped with a synchronous
        // start/end pair around the INVOKE. Pin that intent.
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "okhttp3/Call",
                "enqueue",
                "(Lokhttp3/Callback;)V",
                true,
            )
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        assertNoDispatcherWrap(insns)
    }

    // ── SQLiteDatabase (every guarded method) ────────────────────────

    @Test fun `SQLiteDatabase guarded methods each get a Database dispatch with method-name operation`() {
        // The dispatch contract for SQLiteDatabase is: the operation
        // string equals the method name verbatim (unlike e.g.
        // RandomAccessFile which uses a fixed "read" string). Pin
        // that per-method mapping for every guarded method so a
        // future refactor that consolidates the operation string
        // breaks loudly.
        val guardedMethods = listOf(
            "query", "rawQuery",
            "insert", "insertOrThrow", "insertWithOnConflict",
            "update", "updateWithOnConflict",
            "delete", "execSQL",
        )

        for (methodName in guardedMethods) {
            val bytes = probeBytes { mv ->
                mv.visitInsn(Opcodes.ACONST_NULL)
                mv.visitInsn(Opcodes.ACONST_NULL)
                mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "android/database/sqlite/SQLiteDatabase",
                    methodName,
                    "(Ljava/lang/String;)V",  // descriptor doesn't matter — visitor matches by owner+name only
                    false,
                )
            }
            val transformed = transformOpDispatch(bytes)
            val insns = methodInsns(transformed, "body")
            val callIdx = indexOfCall(insns, "android/database/sqlite/SQLiteDatabase", methodName)
            assertTrue(callIdx > 0, "SQLiteDatabase.$methodName must be preserved")
            assertDispatcherWrap(insns, callIdx, category = "Database", operation = methodName)
        }
    }

    @Test fun `SQLiteDatabase getVersion is NOT wrapped (read-only, non-guarded)`() {
        // Pin that the dispatch list is closed — non-mutation methods
        // like getVersion / getPath / isOpen should NOT be wrapped
        // (they don't represent the I/O class APM is interested in).
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "android/database/sqlite/SQLiteDatabase",
                "getVersion",
                "()I",
                false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        assertNoDispatcherWrap(insns)
    }

    // ── SharedPreferences\$Editor ─────────────────────────────────────

    @Test fun `SharedPreferences Editor commit() is wrapped with Prefs commit dispatch`() {
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "android/content/SharedPreferences\$Editor",
                "commit",
                "()Z",
                true,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        val callIdx = indexOfCall(insns, "android/content/SharedPreferences\$Editor", "commit")
        assertDispatcherWrap(insns, callIdx, category = "Prefs", operation = "commit")
    }

    @Test fun `SharedPreferences Editor apply() is NOT wrapped (async, fire-and-forget)`() {
        // `apply()` is the async sibling of `commit()` — the contract
        // is to never block the caller. Wrapping it with a synchronous
        // start/end pair would create misleading APM spans that
        // don't reflect the actual write latency. Pin that omission.
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "android/content/SharedPreferences\$Editor",
                "apply",
                "()V",
                true,
            )
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        assertNoDispatcherWrap(insns)
    }

    // ── Non-guarded owners ───────────────────────────────────────────

    @Test fun `unrelated INVOKE calls are passed through unchanged`() {
        // The visitor is opt-in by owner+name+descriptor — anything
        // not on the dispatch table must be untouched. Pin this so a
        // mutation that defaults `resolveDispatchInfo` to a non-null
        // value (e.g. fall-through that injects a generic dispatch
        // for every call) would break a sane number of tests.
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "length",
                "()I",
                false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val transformed = transformOpDispatch(bytes)
        val insns = methodInsns(transformed, "body")
        assertNoDispatcherWrap(insns)
    }
}
