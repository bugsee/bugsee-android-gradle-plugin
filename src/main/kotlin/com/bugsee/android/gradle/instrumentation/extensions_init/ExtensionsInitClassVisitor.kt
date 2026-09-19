package com.bugsee.android.gradle.instrumentation.extensions_init

import com.bugsee.android.gradle.instrumentation.util.CatchingMethodVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Rewrites the body of `com.bugsee.library.BugseeInitProvider#initializeExtensions()`
 * so that every detected extension's `register<Name>Extension()` static
 * method is invoked there.
 *
 * The SDK ships an empty `initializeExtensions()` method (annotated
 * `@Keep`) specifically as the injection target for this visitor. With
 * extension `<provider>` elements stripped from the merged manifest by
 * [com.bugsee.android.gradle.manifest.BugseeManifestTask], inlining the
 * registration calls here is what keeps every extension working —
 * otherwise nothing would ever register them at runtime.
 *
 * Each call is wrapped in a `try { ... } catch (Throwable) { }` block so
 * a malformed extension cannot cascade-break the others. The catch
 * intentionally swallows the throwable (no logger call) to keep the
 * injected bytecode self-contained — pulling in `BugseeLogger` would
 * require shipping its descriptor here and would couple the visitor to
 * an SDK-internal class whose name may move.
 */
internal class ExtensionsInitClassVisitor(
    private val apiVersion: Int,
    nextClassVisitor: ClassVisitor,
    private val extensionSpecs: List<ExtensionSpec>,
    private val className: String,
) : ClassVisitor(apiVersion, nextClassVisitor) {

    private var injected = false

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
        if (extensionSpecs.isEmpty()) return mv
        if (name == INITIALIZE_EXTENSIONS_METHOD && descriptor == "()V") {
            injected = true
            // Wrap so a failure in our transform (or AGP's frame
            // recomputation) is attributed to this class+method and
            // re-thrown, never swallowed into corrupt bytecode. See
            // CatchingMethodVisitor. The non-instrumented pass-through
            // returns the raw delegate, unwrapped.
            return CatchingMethodVisitor(
                apiVersion,
                InitializeExtensionsMethodVisitor(mv, extensionSpecs),
                className,
                name,
                descriptor,
            )
        }
        return mv
    }

    /**
     * The providers in [extensionSpecs] are already gone from the merged manifest, so
     * a class without the hook would ship every one of them unregistered. Fail the
     * build naming them rather than let that pass green.
     */
    override fun visitEnd() {
        if (extensionSpecs.isNotEmpty() && !injected) {
            throw IllegalStateException(
                "Bugsee: $className has no $INITIALIZE_EXTENSIONS_METHOD()V, so the extension " +
                    "providers stripped from the merged manifest would never register: " +
                    extensionSpecs.joinToString { it.initProviderFqn } +
                    ". Use a matching Bugsee SDK, or turn off optimizeExtensionsLoading in the bugsee {} block."
            )
        }
        super.visitEnd()
    }

    companion object {
        const val INITIALIZE_EXTENSIONS_METHOD = "initializeExtensions"
    }
}

/**
 * MethodVisitor that injects each detected extension's register call
 * before every `RETURN` opcode of `initializeExtensions ()V`.
 *
 * The SDK's hand-written body (a debug log + implicit return) is
 * preserved — we only append calls at the tail, so any future SDK-side
 * logic in the method survives intact.
 */
private class InitializeExtensionsMethodVisitor(
    methodVisitor: MethodVisitor,
    private val specs: List<ExtensionSpec>,
) : MethodVisitor(Opcodes.ASM9, methodVisitor) {

    override fun visitInsn(opcode: Int) {
        if (opcode == Opcodes.RETURN) {
            emitRegistrations()
        }
        super.visitInsn(opcode)
    }

    /**
     * Emits, for every detected extension:
     * ```
     *   try {
     *       <facade>.register<Name>Extension();
     *   } catch (Throwable ignored) {}
     * ```
     * One independent try/catch per extension so a failure in one does
     * not skip the rest.
     */
    private fun emitRegistrations() {
        for (spec in specs) {
            val tryStart = Label()
            val tryEnd = Label()
            val handler = Label()
            val after = Label()

            mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable")

            mv.visitLabel(tryStart)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                spec.facadeInternalName,
                spec.registerMethodName,
                "()V",
                false,
            )
            mv.visitLabel(tryEnd)
            mv.visitJumpInsn(Opcodes.GOTO, after)

            mv.visitLabel(handler)
            // Stack on entry to handler: [Throwable] — discard it.
            mv.visitInsn(Opcodes.POP)

            mv.visitLabel(after)
        }
    }
}
