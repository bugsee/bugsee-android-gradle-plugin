package com.bugsee.android.gradle.instrumentation.okhttp

import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pin the bytecode-level OkHttp builder injection performed by
 * [OkHttpClassVisitor].
 *
 * **Idempotency contract.** The visitor MUST emit a single
 * `INVOKESTATIC BugseeOkHttpInterceptor.addIfAbsent` immediately
 * before every `OkHttpClient.Builder.build()` call. It MUST NOT emit
 * the older direct-add bytecode shape
 * (`NEW BugseeOkHttpInterceptor + addInterceptor`) — that shape was
 * non-idempotent because `OkHttpClient.newBuilder()` copies the
 * existing interceptor list, leading to N duplicate
 * `BugseeOkHttpInterceptor`s after a chain of N `build()` calls.
 * Each duplicate would emit its own `RequestStarted`/`RequestCompleted`
 * pair with a distinct `requestId`, corrupting request counts and
 * latency math downstream.
 *
 * The OkHttp classes aren't on the gradle-plugin's test classpath,
 * so we hand-roll the bytecode via ASM rather than compiling Java
 * sources — same pattern as
 * [com.bugsee.android.gradle.instrumentation.operation_dispatch.OperationDispatchInjectionTest].
 */
class OkHttpClassVisitorTest {

    private val builderInternalName = "okhttp3/OkHttpClient\$Builder"
    private val interceptorClass = "com/bugsee/library/okhttp/BugseeOkHttpInterceptor"
    private val buildDescriptor = "()Lokhttp3/OkHttpClient;"
    private val addIfAbsentDescriptor = "(Lokhttp3/OkHttpClient\$Builder;)Lokhttp3/OkHttpClient\$Builder;"

    private val webSocketsClass = "com/bugsee/library/okhttp/BugseeOkHttpWebSockets"
    private val newWebSocketDesc = "(Lokhttp3/Request;Lokhttp3/WebSocketListener;)Lokhttp3/WebSocket;"
    private val newWebSocketStaticDesc =
        "(Lokhttp3/WebSocket\$Factory;Lokhttp3/Request;Lokhttp3/WebSocketListener;)Lokhttp3/WebSocket;"

    /**
     * Build a class `Probe` with a single static method `body()V`
     * whose body is supplied by [build]. Same pattern as
     * [com.bugsee.android.gradle.instrumentation.operation_dispatch.OperationDispatchInjectionTest].
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

    // Defaults to WebSocket capture ENABLED so the existing cases keep asserting
    // the full rewrite; the gate itself is covered by its own cases below.
    private fun transformOkHttp(bytes: ByteArray, webSocketCapture: Boolean = true): ByteArray =
        AsmTestHarness.transform(bytes) { writer ->
            OkHttpClassVisitor(writer, className = "fixtures.Test", webSocketCapture = webSocketCapture)
        }

    private fun methodInsns(bytes: ByteArray, methodName: String): List<Any> {
        val cn = ClassNode()
        ClassReader(bytes).accept(cn, 0)
        val m = cn.methods.first { it.name == methodName } as MethodNode
        return m.instructions.toList()
    }

    @Test fun `a single build() call gets addIfAbsent injected immediately before it`() {
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)  // pretend-builder on stack
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                builderInternalName,
                "build",
                buildDescriptor,
                false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val transformed = transformOkHttp(bytes)
        val insns = methodInsns(transformed, "body")

        val callIdx = insns.indexOfFirst {
            it is MethodInsnNode && it.owner == builderInternalName && it.name == "build"
        }
        assertTrue(callIdx > 0, "build() must be present after transform; got $insns")

        // Immediately preceding instruction MUST be the INVOKESTATIC
        // to addIfAbsent. No NEW + INVOKESPECIAL pair.
        val prev = insns[callIdx - 1] as? MethodInsnNode
        assertEquals(interceptorClass, prev?.owner, "wrong INVOKESTATIC owner before build()")
        assertEquals("addIfAbsent", prev?.name)
        assertEquals(addIfAbsentDescriptor, prev?.desc)
        assertEquals(Opcodes.INVOKESTATIC, prev?.opcode)
    }

    @Test fun `transformed bytecode does NOT contain the old direct-add shape`() {
        // The legacy shape was:
        //   NEW BugseeOkHttpInterceptor
        //   DUP
        //   INVOKESPECIAL <init>()V
        //   INVOKEVIRTUAL addInterceptor(Interceptor)
        // After the fix, NONE of those should appear. The
        // idempotency check lives at the SDK side in
        // `addIfAbsent`, so the bytecode never directly NEWs the
        // interceptor.
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                builderInternalName,
                "build",
                buildDescriptor,
                false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val transformed = transformOkHttp(bytes)
        val insns = methodInsns(transformed, "body")

        // No NEW of the interceptor class
        assertTrue(
            insns.none { it is TypeInsnNode && it.opcode == Opcodes.NEW && it.desc == interceptorClass },
            "transformed bytecode must NOT contain NEW $interceptorClass " +
                "(idempotency moved to SDK-side addIfAbsent)",
        )
        // No INVOKEVIRTUAL addInterceptor
        assertTrue(
            insns.none {
                it is MethodInsnNode &&
                    it.opcode == Opcodes.INVOKEVIRTUAL &&
                    it.owner == builderInternalName &&
                    it.name == "addInterceptor"
            },
            "transformed bytecode must NOT contain INVOKEVIRTUAL $builderInternalName.addInterceptor " +
                "(idempotency moved to SDK-side addIfAbsent)",
        )
        // No INVOKESPECIAL <init> on the interceptor class
        assertTrue(
            insns.none {
                it is MethodInsnNode &&
                    it.opcode == Opcodes.INVOKESPECIAL &&
                    it.owner == interceptorClass &&
                    it.name == "<init>"
            },
            "transformed bytecode must NOT instantiate the interceptor directly",
        )
    }

    @Test fun `chained newBuilder build pattern produces ONE addIfAbsent per build call`() {
        // The motivating bug scenario in bytecode: a chain of two
        // build() calls. The visitor must inject one addIfAbsent
        // before EACH build, but the SDK-side check ensures only
        // one interceptor instance ends up in the list. Bytecode-
        // side, we pin "one addIfAbsent per build()".
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                builderInternalName,
                "build",
                buildDescriptor,
                false,
            )
            mv.visitInsn(Opcodes.POP)

            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                builderInternalName,
                "build",
                buildDescriptor,
                false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val transformed = transformOkHttp(bytes)
        val insns = methodInsns(transformed, "body")

        val addIfAbsentCount = insns.count {
            it is MethodInsnNode &&
                it.owner == interceptorClass &&
                it.name == "addIfAbsent" &&
                it.opcode == Opcodes.INVOKESTATIC
        }
        val buildCount = insns.count {
            it is MethodInsnNode &&
                it.owner == builderInternalName &&
                it.name == "build" &&
                it.opcode == Opcodes.INVOKEVIRTUAL
        }
        assertEquals(2, buildCount, "expected 2 build() calls")
        assertEquals(2, addIfAbsentCount, "expected 2 addIfAbsent calls (one per build)")
    }

    @Test fun `build() with a non-matching descriptor is NOT instrumented`() {
        // Pin the descriptor discrimination: a hypothetical overload
        // `build(SomeArg)` would NOT match and must not be wrapped.
        // The current OkHttp API has only the no-arg `build()`,
        // but pin the descriptor specificity so a future overload
        // doesn't trigger spurious instrumentation.
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ACONST_NULL)  // hypothetical arg
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                builderInternalName,
                "build",
                "(Ljava/lang/Object;)Lokhttp3/OkHttpClient;",  // different descriptor
                false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val transformed = transformOkHttp(bytes)
        val insns = methodInsns(transformed, "body")
        assertTrue(
            insns.none {
                it is MethodInsnNode &&
                    it.owner == interceptorClass &&
                    it.name == "addIfAbsent"
            },
            "addIfAbsent must NOT be injected when the build() descriptor doesn't match",
        )
    }

    @Test fun `non-OkHttp Builder build() calls are NOT instrumented`() {
        // A user `MyBuilder.build()` whose owner is not OkHttp's
        // Builder must not trigger injection. Pin the owner-specific
        // gate.
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "com/example/MyBuilder",
                "build",
                "()Lcom/example/MyClient;",
                false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val transformed = transformOkHttp(bytes)
        val insns = methodInsns(transformed, "body")
        assertTrue(
            insns.none {
                it is MethodInsnNode &&
                    it.owner == interceptorClass &&
                    it.name == "addIfAbsent"
            },
            "addIfAbsent must only fire for OkHttpClient.Builder.build()",
        )
    }

    // ---- WebSocket capture (7.0 regression fix) ----

    @Test fun `OkHttpClient newWebSocket is replaced with the capturing static`() {
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL) // client (is-a WebSocket.Factory)
            mv.visitInsn(Opcodes.ACONST_NULL) // request
            mv.visitInsn(Opcodes.ACONST_NULL) // listener
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "okhttp3/OkHttpClient", "newWebSocket", newWebSocketDesc, false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val insns = methodInsns(transformOkHttp(bytes), "body")

        assertTrue(
            insns.none { it is MethodInsnNode && it.name == "newWebSocket" && it.opcode == Opcodes.INVOKEVIRTUAL },
            "the original OkHttpClient.newWebSocket call must be replaced, not emitted",
        )
        val staticCall = insns.filterIsInstance<MethodInsnNode>().single {
            it.opcode == Opcodes.INVOKESTATIC && it.owner == webSocketsClass && it.name == "newWebSocket"
        }
        assertEquals(newWebSocketStaticDesc, staticCall.desc)
    }

    @Test fun `WebSocket Factory newWebSocket interface call is replaced`() {
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL) // factory
            mv.visitInsn(Opcodes.ACONST_NULL) // request
            mv.visitInsn(Opcodes.ACONST_NULL) // listener
            mv.visitMethodInsn(
                Opcodes.INVOKEINTERFACE, "okhttp3/WebSocket\$Factory", "newWebSocket", newWebSocketDesc, true,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val insns = methodInsns(transformOkHttp(bytes), "body")

        assertTrue(
            insns.none { it is MethodInsnNode && it.name == "newWebSocket" && it.opcode == Opcodes.INVOKEINTERFACE },
            "the WebSocket.Factory.newWebSocket interface call must be replaced",
        )
        assertTrue(
            insns.any {
                it is MethodInsnNode && it.opcode == Opcodes.INVOKESTATIC &&
                    it.owner == webSocketsClass && it.name == "newWebSocket"
            },
            "must emit the capturing static",
        )
    }

    @Test fun `newWebSocket with a non-matching descriptor is NOT replaced`() {
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "okhttp3/OkHttpClient", "newWebSocket",
                "(Ljava/lang/String;)Lokhttp3/WebSocket;", false,
            )
            mv.visitInsn(Opcodes.POP)
        }
        val insns = methodInsns(transformOkHttp(bytes), "body")
        assertTrue(
            insns.none { it is MethodInsnNode && it.owner == webSocketsClass },
            "a non-matching newWebSocket descriptor must not be rewritten",
        )
    }

    // ── the WebSocket gate ────────────────────────────────────────────────
    //
    // Plugin and SDK are not released in lockstep, so the plugin must not emit a
    // call to a class the resolved SDK may not carry: the injected INVOKESTATIC
    // sits in the host app's own method, where a missing class is an
    // unrecoverable NoClassDefFoundError at the app's call site.

    @Test fun `newWebSocket is left alone when WebSocket capture is gated off`() {
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "okhttp3/OkHttpClient", "newWebSocket", newWebSocketDesc, false,
            )
            mv.visitInsn(Opcodes.POP)
        }

        val insns = methodInsns(transformOkHttp(bytes, webSocketCapture = false), "body")

        assertTrue(
            insns.none {
                it is MethodInsnNode && it.owner == webSocketsClass && it.name == "newWebSocket"
            },
            "no call to BugseeOkHttpWebSockets may be emitted when the gate is off — " +
                "the SDK on the consumer's classpath may not contain that class",
        )
        assertTrue(
            insns.any {
                it is MethodInsnNode && it.opcode == Opcodes.INVOKEVIRTUAL &&
                    it.owner == "okhttp3/OkHttpClient" && it.name == "newWebSocket"
            },
            "the original call must be preserved verbatim so the app's socket still opens",
        )
    }

    @Test fun `Factory newWebSocket is left alone when WebSocket capture is gated off`() {
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEINTERFACE, "okhttp3/WebSocket\$Factory", "newWebSocket", newWebSocketDesc, true,
            )
            mv.visitInsn(Opcodes.POP)
        }

        val insns = methodInsns(transformOkHttp(bytes, webSocketCapture = false), "body")

        assertTrue(
            insns.none { it is MethodInsnNode && it.owner == webSocketsClass },
            "the interface-call lane must respect the gate too — it is the one Ktor uses",
        )
    }

    /**
     * The reason the gate is per-branch rather than per-lane. Older SDKs lack
     * BugseeOkHttpWebSockets but DO have BugseeOkHttpInterceptor, so disabling the
     * whole lane would trade a missing WebSocket feature for the loss of ordinary
     * HTTP request capture — a far worse regression than the one being avoided.
     */
    @Test fun `interceptor injection still happens when WebSocket capture is gated off`() {
        val bytes = probeBytes { mv ->
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, "okhttp3/OkHttpClient\$Builder", "build",
                "()Lokhttp3/OkHttpClient;", false,
            )
            mv.visitInsn(Opcodes.POP)
        }

        val insns = methodInsns(transformOkHttp(bytes, webSocketCapture = false), "body")

        assertTrue(
            insns.any {
                it is MethodInsnNode && it.opcode == Opcodes.INVOKESTATIC &&
                    it.name == "addIfAbsent"
            },
            "HTTP capture must be unaffected by the WebSocket gate",
        )
    }
}
