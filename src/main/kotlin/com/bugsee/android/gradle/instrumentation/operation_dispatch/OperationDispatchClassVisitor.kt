package com.bugsee.android.gradle.instrumentation.operation_dispatch

import com.bugsee.android.gradle.instrumentation.util.CatchingMethodVisitor
import com.bugsee.android.gradle.instrumentation.util.NewInitPairing
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TryCatchBlockNode

/**
 * ClassVisitor that performs two types of bytecode transformation:
 *
 * 1. **Type remapping** for `FileInputStream`/`FileOutputStream` — replaces
 *    `new FileInputStream(...)` with `new BugseeFileInputStream(...)` (and likewise
 *    for output). The wrapper classes handle span creation, byte counting, and
 *    file path capture internally.
 *
 * 2. **Dispatch injection** for database, network, and SharedPreferences operations —
 *    injects `BugseeOperationDispatcher.onXxxOperationStart/End()` around guarded calls.
 */
internal class OperationDispatchClassVisitor(
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
        return CatchingMethodVisitor(
            Opcodes.ASM9,
            OperationDispatchMethodVisitor(access, name, descriptor, signature, exceptions, mv, className),
            className,
            name,
            descriptor,
        )
    }
}

/**
 * Resolved dispatch info for a guarded call site.
 */
private data class DispatchInfo(
    val category: String,
    val operation: String
)

/**
 * MethodVisitor that:
 * - Remaps `FileInputStream`/`FileOutputStream` types to Bugsee wrappers
 *   at `new` allocation sites only, pairing each NEW with its `<init>` by data
 *   flow (see [NewInitPairing]). `super(...)`/`this(...)` calls have no origin NEW
 *   and are left untouched; methods whose pairing cannot be proven are left
 *   un-remapped.
 * - Injects dispatcher start/end calls around database, network, and prefs operations
 */
private class OperationDispatchMethodVisitor(
    access: Int,
    name: String?,
    descriptor: String?,
    signature: String?,
    exceptions: Array<out String>?,
    private val delegate: MethodVisitor,
    private val ownerClassName: String,
) : MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions?.toList()?.toTypedArray()) {

    /**
     * One guarded call site: the region to protect, and what to report for it.
     * Collected while streaming, applied in [visitEnd].
     */
    private class GuardedCall(
        val start: LabelNode,
        val end: LabelNode,
        val handler: LabelNode,
        val info: DispatchInfo,
    )

    private val guardedCalls = mutableListOf<GuardedCall>()

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        // --- Dispatch injection for database, network, prefs ---
        val info = resolveDispatchInfo(owner, name, descriptor)
        if (info == null) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            return
        }

        visitLdcInsn(info.operation)
        visitInsn(Opcodes.ACONST_NULL)
        super.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            DISPATCHER_CLASS,
            "on${info.category}OperationStart",
            DISPATCH_DESCRIPTOR,
            false
        )

        // Layout, mirroring how javac compiles try/finally:
        //
        //   L0:  <guarded call>
        //   L1:  End                (normal path)
        //        GOTO Lafter
        //   Lh:  End ; ATHROW       (exception path)
        //   Lafter:
        //
        // The handler block is emitted INLINE here rather than appended at the end of
        // the method, and that placement is load-bearing. Our handler rethrows, and the
        // rethrow must still be caught by whatever user try/catch encloses this call. An
        // exception table range is a contiguous region, so a handler parked at the end
        // of the method sits OUTSIDE the user's range: their catch would stop catching,
        // and we would silently break their error handling while fixing our own leak.
        // Emitted here, the handler falls inside any user range that already covered
        // the call.
        val tryStart = Label()
        val tryEnd = Label()
        val handlerLabel = Label()
        val afterLabel = Label()

        visitLabel(tryStart)
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        visitLabel(tryEnd)

        visitLdcInsn(info.operation)
        visitInsn(Opcodes.ACONST_NULL)
        super.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            DISPATCHER_CLASS,
            "on${info.category}OperationEnd",
            DISPATCH_DESCRIPTOR,
            false
        )
        visitJumpInsn(Opcodes.GOTO, afterLabel)

        // Stack on entry: [throwable]. Report, then rethrow untouched — the exception's
        // identity and propagation are unchanged. No local slot is used.
        visitLabel(handlerLabel)
        visitLdcInsn(info.operation)
        visitInsn(Opcodes.ACONST_NULL)
        super.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            DISPATCHER_CLASS,
            "on${info.category}OperationEnd",
            DISPATCH_DESCRIPTOR,
            false
        )
        visitInsn(Opcodes.ATHROW)
        visitLabel(afterLabel)

        guardedCalls += GuardedCall(
            getLabelNode(tryStart),
            getLabelNode(tryEnd),
            getLabelNode(handlerLabel),
            info,
        )
    }

    /**
     * Attaches the exception path.
     *
     * Previously Start and End were emitted in a straight line, so a guarded call that
     * THREW left the method over the top of its End. The SDK's providers push a span on
     * Start and pop it on End, so every such throw stranded an unfinished span on a
     * ThreadLocal deque that was never released.
     *
     * Two details carry the correctness here:
     *
     * 1. **Handler ordering.** Our catch-any is inserted at index 0 of the exception
     *    table. The JVM scans that table IN ORDER and takes the first entry whose range
     *    and type match, so an enclosing user `catch` that appears earlier would run
     *    INSTEAD of ours and the End would still be lost. Controlling that order is the
     *    reason this visitor buffers into a MethodNode at all — a streaming visitor can
     *    only append, and the reader has already emitted the user's entries by then.
     *
     * 2. **Range width.** Each handler covers only its own call instruction, so a throw
     *    from surrounding user code is never intercepted, and nested guarded calls each
     *    get their own handler rather than one swallowing another.
     *
     * The handler reports End and rethrows, leaving the exception's own propagation and
     * any user handler untouched. It reads the throwable off the stack without storing
     * it, so no local slot is added.
     */
    override fun visitEnd() {
        for (call in guardedCalls) {
            // Index 0: the JVM scans the exception table IN ORDER and runs the first
            // entry whose range and type match. An enclosing user catch registered
            // earlier would otherwise run INSTEAD of ours and the End would still be
            // lost — with nothing escaping to indicate it. Controlling that order is the
            // only reason this visitor buffers into a MethodNode: a streaming visitor
            // can only append, and the reader has already emitted the user's entries.
            tryCatchBlocks.add(0, TryCatchBlockNode(call.start, call.end, call.handler, null))
        }

        // File I/O remap runs LAST, over the final instruction list and exception table,
        // so the data-flow analysis sees exactly what will be emitted.
        NewInitPairing.rewrite(ownerClassName, this) { remapType(it) }

        super.visitEnd()
        accept(delegate)
    }

    companion object {

        /**
         * Returns the remapped internal name for file stream types,
         * or null if no remapping is needed.
         */
        private fun remapType(type: String?): String? = when (type) {
            "java/io/FileInputStream" -> BUGSEE_FILE_INPUT_STREAM
            "java/io/FileOutputStream" -> BUGSEE_FILE_OUTPUT_STREAM
            else -> null
        }

        /**
         * Resolves dispatch info for non-file-I/O guarded call sites.
         * File I/O is handled by type remapping, not dispatch injection.
         */
        private fun resolveDispatchInfo(
            owner: String?,
            name: String?,
            descriptor: String?
        ): DispatchInfo? {
            if (owner == null || name == null) return null

            // RandomAccessFile — still uses dispatch (no wrapper class yet)
            if (owner == "java/io/RandomAccessFile" && name == "<init>") {
                if (descriptor == "(Ljava/io/File;Ljava/lang/String;)V" ||
                    descriptor == "(Ljava/lang/String;Ljava/lang/String;)V"
                ) {
                    return DispatchInfo("FileRead", "read")
                }
            }

            // Network — URL
            if (owner == "java/net/URL") {
                if (name == "openConnection" || name == "openStream") {
                    return DispatchInfo("Network", "connect")
                }
            }

            // Network — Socket
            if (owner == "java/net/Socket") {
                if (name == "<init>") {
                    if (descriptor == "(Ljava/lang/String;I)V" ||
                        descriptor == "(Ljava/net/InetAddress;I)V"
                    ) {
                        return DispatchInfo("Network", "connect")
                    }
                }
                if (name == "connect") {
                    if (descriptor == "(Ljava/net/SocketAddress;)V" ||
                        descriptor == "(Ljava/net/SocketAddress;I)V"
                    ) {
                        return DispatchInfo("Network", "connect")
                    }
                }
            }

            // Network — OkHttp synchronous execute
            if (owner == "okhttp3/Call" && name == "execute" &&
                descriptor == "()Lokhttp3/Response;"
            ) {
                return DispatchInfo("Network", "execute")
            }

            // Database — match by owner + name only (all overloads)
            if (owner == "android/database/sqlite/SQLiteDatabase") {
                if (name == "query" || name == "rawQuery" ||
                    name == "insert" || name == "insertOrThrow" ||
                    name == "insertWithOnConflict" ||
                    name == "update" || name == "updateWithOnConflict" ||
                    name == "delete" || name == "execSQL"
                ) {
                    return DispatchInfo("Database", name)
                }
            }

            // SharedPreferences — commit
            if (owner == "android/content/SharedPreferences\$Editor" && name == "commit") {
                return DispatchInfo("Prefs", "commit")
            }

            return null
        }
    }
}

private const val DISPATCHER_CLASS =
    "com/bugsee/library/adapters/BugseeOperationDispatcher"

private const val DISPATCH_DESCRIPTOR =
    "(Ljava/lang/String;Ljava/lang/String;)V"

private const val BUGSEE_FILE_INPUT_STREAM =
    "com/bugsee/library/adapters/BugseeFileInputStream"

private const val BUGSEE_FILE_OUTPUT_STREAM =
    "com/bugsee/library/adapters/BugseeFileOutputStream"
