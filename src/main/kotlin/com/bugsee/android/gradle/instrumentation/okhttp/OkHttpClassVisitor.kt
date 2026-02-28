package com.bugsee.android.gradle.instrumentation.okhttp

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * ClassVisitor that intercepts all methods and delegates to [OkHttpMethodVisitor]
 * to inject BugseeOkHttpInterceptor before OkHttpClient.Builder.build() calls.
 */
internal class OkHttpClassVisitor(
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
        return OkHttpMethodVisitor(mv)
    }
}

/**
 * MethodVisitor that looks for calls to `OkHttpClient$Builder.build()` and injects
 * `BugseeOkHttpInterceptor` as an interceptor immediately before the build() call.
 *
 * Target instruction:
 *   INVOKEVIRTUAL okhttp3/OkHttpClient$Builder.build ()Lokhttp3/OkHttpClient;
 *
 * Injected before it:
 *   NEW    com/bugsee/library/okhttp/BugseeOkHttpInterceptor
 *   DUP
 *   INVOKESPECIAL com/bugsee/library/okhttp/BugseeOkHttpInterceptor.<init> ()V
 *   INVOKEVIRTUAL okhttp3/OkHttpClient$Builder.addInterceptor (Lokhttp3/Interceptor;)Lokhttp3/OkHttpClient$Builder;
 *
 * Stack analysis: Before the build() call, the Builder is on the stack.
 * addInterceptor() returns the Builder (fluent API), so after our injection
 * the stack is exactly the same as before — Builder on top — ready for build().
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
            // Inject: new BugseeOkHttpInterceptor(), then addInterceptor()
            mv.visitTypeInsn(Opcodes.NEW, INTERCEPTOR_CLASS)
            mv.visitInsn(Opcodes.DUP)
            mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                INTERCEPTOR_CLASS,
                "<init>",
                "()V",
                false
            )
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "okhttp3/OkHttpClient\$Builder",
                "addInterceptor",
                "(Lokhttp3/Interceptor;)Lokhttp3/OkHttpClient\$Builder;",
                false
            )
        }

        // Always emit the original instruction
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    companion object {
        private const val INTERCEPTOR_CLASS = "com/bugsee/library/okhttp/BugseeOkHttpInterceptor"
    }
}
