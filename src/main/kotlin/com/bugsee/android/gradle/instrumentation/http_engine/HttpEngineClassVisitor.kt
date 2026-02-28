package com.bugsee.android.gradle.instrumentation.http_engine

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * ClassVisitor that intercepts all methods and delegates to [HttpEngineMethodVisitor]
 * to inject BugseeHttpEngineAdapter calls at HttpEngine API call sites.
 */
internal class HttpEngineClassVisitor(
    nextClassVisitor: ClassVisitor
) : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor? {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
        return HttpEngineMethodVisitor(mv)
    }
}

/**
 * MethodVisitor that intercepts HttpEngine API calls and injects adapter calls for:
 *
 * 1. `HttpEngine.newUrlRequestBuilder(url, executor, callback)` — wraps the callback argument
 * 2. `UrlRequest.Builder.setHttpMethod(method)` — records HTTP method
 * 3. `UrlRequest.Builder.addHeader(name, value)` — records request header
 * 4. `UrlRequest.Builder.setUploadDataProvider(provider, executor)` — wraps the provider
 * 5. `UrlRequest.start()` — emits RequestStarted event
 *
 * All stack manipulation uses only DUP/SWAP/DUP_X2/DUP2_X1 instructions
 * (no local variable allocation required).
 */
private class HttpEngineMethodVisitor(
    methodVisitor: MethodVisitor
) : MethodVisitor(Opcodes.ASM9, methodVisitor) {

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        when {
            // 1. HttpEngine.newUrlRequestBuilder(String, Executor, Callback) -> Builder
            opcode == Opcodes.INVOKEVIRTUAL
                && owner == "android/net/http/HttpEngine"
                && name == "newUrlRequestBuilder"
                && descriptor == NEW_URL_REQUEST_BUILDER_DESC -> {
                instrumentNewUrlRequestBuilder(opcode, owner!!, name!!, descriptor!!, isInterface)
                return
            }

            // 2. UrlRequest.Builder.setHttpMethod(String) -> Builder
            opcode == Opcodes.INVOKEVIRTUAL
                && owner == BUILDER_CLASS
                && name == "setHttpMethod"
                && descriptor == "(Ljava/lang/String;)L$BUILDER_CLASS;" -> {
                // Stack: ..., builder, method
                mv.visitInsn(Opcodes.DUP)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, ADAPTER_CLASS, "onSetHttpMethod",
                    "(Ljava/lang/String;)V", false
                )
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                return
            }

            // 3. UrlRequest.Builder.addHeader(String, String) -> Builder
            opcode == Opcodes.INVOKEVIRTUAL
                && owner == BUILDER_CLASS
                && name == "addHeader"
                && descriptor == "(Ljava/lang/String;Ljava/lang/String;)L$BUILDER_CLASS;" -> {
                // Stack: ..., builder, name, value
                mv.visitInsn(Opcodes.DUP2)
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, ADAPTER_CLASS, "onAddHeader",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false
                )
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                return
            }

            // 4. UrlRequest.Builder.setUploadDataProvider(UploadDataProvider, Executor) -> Builder
            opcode == Opcodes.INVOKEVIRTUAL
                && owner == BUILDER_CLASS
                && name == "setUploadDataProvider"
                && descriptor == SET_UPLOAD_DATA_PROVIDER_DESC -> {
                instrumentSetUploadDataProvider(opcode, owner!!, name!!, descriptor!!, isInterface)
                return
            }

            // 5. UrlRequest.start()
            opcode == Opcodes.INVOKEVIRTUAL
                && owner == "android/net/http/UrlRequest"
                && name == "start"
                && descriptor == "()V" -> {
                mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC, ADAPTER_CLASS, "onStart", "()V", false
                )
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                return
            }
        }

        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    /**
     * Instruments `HttpEngine.newUrlRequestBuilder(url, executor, callback)` to replace
     * the callback argument with a wrapped version that captures network events.
     *
     * Stack manipulation (all values are category-1 references):
     * ```
     * Before:  ..., engine, url, executor, callback
     * DUP_X2:  ..., engine, callback, url, executor, callback
     * POP:     ..., engine, callback, url, executor
     * DUP2_X1: ..., engine, url, executor, callback, url, executor
     * POP:     ..., engine, url, executor, callback, url
     * INVOKE:  ..., engine, url, executor, wrappedCallback  (wrapCallback consumes callback+url)
     * ```
     */
    private fun instrumentNewUrlRequestBuilder(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean
    ) {
        // Stack: ..., engine, url, executor, callback
        mv.visitInsn(Opcodes.DUP_X2)
        // Stack: ..., engine, callback, url, executor, callback
        mv.visitInsn(Opcodes.POP)
        // Stack: ..., engine, callback, url, executor
        mv.visitInsn(Opcodes.DUP2_X1)
        // Stack: ..., engine, url, executor, callback, url, executor
        mv.visitInsn(Opcodes.POP)
        // Stack: ..., engine, url, executor, callback, url
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, ADAPTER_CLASS, "wrapCallback",
            "(Landroid/net/http/UrlRequest\$Callback;Ljava/lang/String;)" +
                "Landroid/net/http/UrlRequest\$Callback;",
            false
        )
        // Stack: ..., engine, url, executor, wrappedCallback

        // Original call
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        // Stack: ..., builder
    }

    /**
     * Instruments `UrlRequest.Builder.setUploadDataProvider(provider, executor)` to replace
     * the provider argument with a wrapped version that captures request body bytes.
     *
     * Stack manipulation:
     * ```
     * Before:  ..., builder, provider, executor
     * SWAP:    ..., builder, executor, provider
     * INVOKE:  ..., builder, executor, wrappedProvider
     * SWAP:    ..., builder, wrappedProvider, executor
     * ```
     */
    private fun instrumentSetUploadDataProvider(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean
    ) {
        // Stack: ..., builder, provider, executor
        mv.visitInsn(Opcodes.SWAP)
        // Stack: ..., builder, executor, provider
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, ADAPTER_CLASS, "wrapUploadDataProvider",
            "(Landroid/net/http/UploadDataProvider;)Landroid/net/http/UploadDataProvider;",
            false
        )
        // Stack: ..., builder, executor, wrappedProvider
        mv.visitInsn(Opcodes.SWAP)
        // Stack: ..., builder, wrappedProvider, executor

        // Original call
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        // Stack: ..., builder
    }

    companion object {
        private const val ADAPTER_CLASS = "com/bugsee/library/adapters/BugseeHttpEngineAdapter"
        private const val BUILDER_CLASS = "android/net/http/UrlRequest\$Builder"
        private const val NEW_URL_REQUEST_BUILDER_DESC =
            "(Ljava/lang/String;Ljava/util/concurrent/Executor;Landroid/net/http/UrlRequest\$Callback;)" +
                "Landroid/net/http/UrlRequest\$Builder;"
        private const val SET_UPLOAD_DATA_PROVIDER_DESC =
            "(Landroid/net/http/UploadDataProvider;Ljava/util/concurrent/Executor;)" +
                "Landroid/net/http/UrlRequest\$Builder;"
    }
}
