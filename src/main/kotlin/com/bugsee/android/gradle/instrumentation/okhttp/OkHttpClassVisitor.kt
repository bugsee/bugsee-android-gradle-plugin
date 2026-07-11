package com.bugsee.android.gradle.instrumentation.okhttp

import com.bugsee.android.gradle.instrumentation.util.CatchingMethodVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * ClassVisitor that intercepts all methods and delegates to [OkHttpMethodVisitor]
 * to inject BugseeOkHttpInterceptor before OkHttpClient.Builder.build() calls.
 */
internal class OkHttpClassVisitor(
    nextClassVisitor: ClassVisitor,
    private val className: String,
) : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor? {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
        // Wrap so a failure in our transform (or AGP's frame recomputation)
        // is attributed to this class+method and re-thrown, never swallowed
        // into corrupt bytecode. See CatchingMethodVisitor.
        return CatchingMethodVisitor(Opcodes.ASM9, OkHttpMethodVisitor(mv), className, name, descriptor)
    }
}

/**
 * MethodVisitor that looks for calls to `OkHttpClient$Builder.build()` and
 * injects an idempotent call to
 * `BugseeOkHttpInterceptor.addIfAbsent(builder)` immediately before
 * the build() call.
 *
 * Target instruction:
 *   INVOKEVIRTUAL okhttp3/OkHttpClient$Builder.build ()Lokhttp3/OkHttpClient;
 *
 * Injected before it (replaces the older direct
 * `new BugseeOkHttpInterceptor() + addInterceptor()` shape):
 *   INVOKESTATIC  com/bugsee/library/okhttp/BugseeOkHttpInterceptor.addIfAbsent
 *                 (Lokhttp3/OkHttpClient$Builder;)Lokhttp3/OkHttpClient$Builder;
 *
 * `addIfAbsent` consumes the Builder on top of the stack, scans its
 * existing `interceptors()` for an already-present
 * `BugseeOkHttpInterceptor`, adds one only if absent, and returns
 * the same Builder either way — net stack effect zero, identical to
 * the prior shape, so the immediately-following `build()` is
 * unaffected.
 *
 * **Why this matters.** OkHttp's `OkHttpClient.newBuilder()` copies
 * the existing client's interceptor list into the new builder.
 * Without idempotency, a chain like
 * `client.newBuilder().build().newBuilder().build()` accumulated
 * one fresh `BugseeOkHttpInterceptor` per `build()` call —
 * resulting in 2x, 3x, … duplicate `RequestStarted`/
 * `RequestCompleted` events per HTTP request (each duplicate with
 * a different `requestId` minted by `UUID.randomUUID()`), corrupting
 * downstream request counts and latency math. The idempotency check
 * lives in [BugseeOkHttpInterceptor.addIfAbsent] (runtime side) so
 * the bytecode-level injection stays small and the dedup intent is
 * visible in a tiny named method instead of buried in ASM.
 */
private class OkHttpMethodVisitor(
    methodVisitor: MethodVisitor
) : MethodVisitor(Opcodes.ASM9, methodVisitor) {

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        if (opcode == Opcodes.INVOKEVIRTUAL
            && owner == "okhttp3/OkHttpClient\$Builder"
            && name == "build"
            && descriptor == "()Lokhttp3/OkHttpClient;"
        ) {
            // The Builder is on the stack. addIfAbsent consumes it
            // and returns the same Builder (fluent API), so the
            // stack shape is preserved for the subsequent build()
            // call.
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                INTERCEPTOR_CLASS,
                "addIfAbsent",
                "(Lokhttp3/OkHttpClient\$Builder;)Lokhttp3/OkHttpClient\$Builder;",
                false
            )
        }

        // WebSocket capture: WS frames flow through neither the OkHttp Interceptor nor
        // HttpURLConnection, so replace `newWebSocket(request, listener)` call sites with the
        // capturing producer. OkHttpClient implements WebSocket.Factory, so the receiver + 2
        // args already on the stack (factory, request, listener) are assignment-compatible
        // with the static's params — no stack manipulation, identical stack effect (3 refs ->
        // 1), so COPY_FRAMES holds. Covers both the OkHttpClient.newWebSocket call (a
        // virtual on the concrete class) and WebSocket$Factory.newWebSocket (an interface
        // call — notably Ktor's OkHttp engine
        // (io.ktor.client.engine.okhttp.OkHttpWebsocketSession) creates its socket via
        // `webSocketFactory.newWebSocket(...)`, so this branch also captures Ktor-on-OkHttp
        // WebSockets for free; that class is not under com.bugsee.*/okhttp3.* so it is
        // instrumented). We do NOT emit the original.
        if (name == "newWebSocket"
            && descriptor == "(Lokhttp3/Request;Lokhttp3/WebSocketListener;)Lokhttp3/WebSocket;"
            && (
                (opcode == Opcodes.INVOKEVIRTUAL && owner == "okhttp3/OkHttpClient")
                    || (opcode == Opcodes.INVOKEINTERFACE && owner == "okhttp3/WebSocket\$Factory")
                )
        ) {
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                WEBSOCKETS_CLASS,
                "newWebSocket",
                "(Lokhttp3/WebSocket\$Factory;Lokhttp3/Request;Lokhttp3/WebSocketListener;)Lokhttp3/WebSocket;",
                false
            )
            return
        }

        // Always emit the original instruction
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    companion object {
        private const val INTERCEPTOR_CLASS = "com/bugsee/library/okhttp/BugseeOkHttpInterceptor"
        private const val WEBSOCKETS_CLASS = "com/bugsee/library/okhttp/BugseeOkHttpWebSockets"
    }
}
