package com.bugsee.android.gradle.instrumentation.operation_dispatch

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
        return OperationDispatchMethodVisitor(mv)
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
 * - Injects dispatcher start/end calls around database, network, and prefs operations
 */
private class OperationDispatchMethodVisitor(
    methodVisitor: MethodVisitor
) : MethodVisitor(Opcodes.ASM9, methodVisitor) {

    /**
     * Intercept NEW instructions to remap FileInputStream/FileOutputStream types.
     */
    override fun visitTypeInsn(opcode: Int, type: String?) {
        if (opcode == Opcodes.NEW) {
            val remapped = remapType(type)
            if (remapped != null) {
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
        if (name == "<init>" && owner != null) {
            val remapped = remapType(owner)
            if (remapped != null) {
                // Remap the constructor owner — the wrapper has identical constructors
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
