package com.bugsee.android.gradle.instrumentation.operation_dispatch

import com.bugsee.android.gradle.instrumentation.util.CatchingMethodVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

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
            OperationDispatchMethodVisitor(mv),
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
 *   at `new` allocation sites only. `super(...)` calls in user
 *   subclasses are left untouched (see [pendingRemaps] for the
 *   correctness argument).
 * - Injects dispatcher start/end calls around database, network, and prefs operations
 */
private class OperationDispatchMethodVisitor(
    methodVisitor: MethodVisitor
) : MethodVisitor(Opcodes.ASM9, methodVisitor) {

    /**
     * Stack of remap targets pushed by `NEW` instructions and popped
     * by the matching `<init>` `INVOKESPECIAL`. The remap MUST be
     * gated on a paired NEW because, per JVMS §4.10.1.9, an
     * `INVOKESPECIAL` on `<init>` is only verifier-legal when the
     * target type is either (a) the type of the uninitialized
     * reference produced by the preceding NEW, OR (b) the immediate
     * superclass of the current class (the `super(...)` case in a
     * `<init>` method body).
     *
     * Case (a) is what we WANT to remap — `new FileInputStream(f)`
     * → emit `NEW BugseeFileInputStream` paired with
     * `INVOKESPECIAL BugseeFileInputStream.<init>`.
     *
     * Case (b) is what we MUST NOT remap — a user subclass
     * `class MyFis extends FileInputStream { MyFis(File f) { super(f); } }`
     * emits `INVOKESPECIAL java/io/FileInputStream.<init>` with no
     * paired NEW. Remapping the owner would produce an INVOKESPECIAL
     * whose target is neither (a) nor (b), which Android ART rejects
     * at class load with a `VerifyError`.
     *
     * The stack is LIFO and only ever grows / shrinks by one entry
     * per NEW / `<init>` pair, matching the JVM's own invariant that
     * `<init>` calls always pop the most-recent matching NEW.
     */
    private val pendingRemaps = ArrayDeque<String>()

    /**
     * Intercept NEW instructions to remap FileInputStream/FileOutputStream
     * types. The remap target is also pushed onto [pendingRemaps] so
     * the paired `<init>` `INVOKESPECIAL` can be remapped in lockstep.
     */
    override fun visitTypeInsn(opcode: Int, type: String?) {
        if (opcode == Opcodes.NEW) {
            val remapped = remapType(type)
            if (remapped != null) {
                pendingRemaps.addLast(remapped)
                super.visitTypeInsn(opcode, remapped)
                return
            }
        }
        super.visitTypeInsn(opcode, type)
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        // --- File I/O type remapping for <init> calls ---
        //
        // ONLY remap when this <init> is the paired call to a NEW we
        // already remapped (LIFO match against `pendingRemaps`). The
        // mismatched case (no pending remap target, or the top entry
        // doesn't match) is a `super(...)` call from a user subclass
        // of FileInputStream/FileOutputStream — leave its owner alone
        // so the resulting bytecode passes the JVM/ART verifier.
        if (name == "<init>" && owner != null) {
            val remapped = remapType(owner)
            if (remapped != null
                    && pendingRemaps.isNotEmpty()
                    && pendingRemaps.last() == remapped) {
                pendingRemaps.removeLast()
                super.visitMethodInsn(opcode, remapped, name, descriptor, isInterface)
                return
            }
        }

        // --- Dispatch injection for database, network, prefs ---
        val info = resolveDispatchInfo(owner, name, descriptor)
        if (info != null) {
            mv.visitLdcInsn(info.operation)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                DISPATCHER_CLASS,
                "on${info.category}OperationStart",
                DISPATCH_DESCRIPTOR,
                false
            )
        }

        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)

        if (info != null) {
            mv.visitLdcInsn(info.operation)
            mv.visitInsn(Opcodes.ACONST_NULL)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                DISPATCHER_CLASS,
                "on${info.category}OperationEnd",
                DISPATCH_DESCRIPTOR,
                false
            )
        }
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
