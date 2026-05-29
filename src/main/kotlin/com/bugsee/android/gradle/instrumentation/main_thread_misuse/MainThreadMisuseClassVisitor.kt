package com.bugsee.android.gradle.instrumentation.main_thread_misuse

import com.bugsee.android.gradle.instrumentation.util.CatchingMethodVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * ClassVisitor that injects main-thread guard checks before guarded method calls.
 *
 * For each guarded operation (file I/O, network, database, SharedPreferences commit),
 * a `static void ()` check is injected immediately before the original call.
 * The injected call has zero operand stack effect, making it safe to insert
 * before any method invocation.
 */
internal class MainThreadMisuseClassVisitor(
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
        return CatchingMethodVisitor(Opcodes.ASM9, MainThreadMisuseMethodVisitor(mv), className, name, descriptor)
    }
}

/**
 * MethodVisitor that intercepts calls to guarded methods and injects
 * `BugseeMainThreadGuardAdapter.checkXxx()` before each one.
 *
 * Matching rules:
 * - Disk read/write, network, SharedPreferences: match by owner + name + descriptor
 * - Database: match by owner + name only (all overloads)
 */
private class MainThreadMisuseMethodVisitor(
    methodVisitor: MethodVisitor
) : MethodVisitor(Opcodes.ASM9, methodVisitor) {

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        val checkMethod = resolveCheckMethod(owner, name, descriptor)
        if (checkMethod != null) {
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                GUARD_ADAPTER_CLASS,
                checkMethod,
                "()V",
                false
            )
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    companion object {

        private fun resolveCheckMethod(
            owner: String?,
            name: String?,
            descriptor: String?
        ): String? {
            if (owner == null || name == null) return null

            // Disk read (original and remapped wrapper class)
            if ((owner == "java/io/FileInputStream" ||
                 owner == "com/bugsee/library/adapters/BugseeFileInputStream") && name == "<init>") {
                if (descriptor == "(Ljava/io/File;)V" ||
                    descriptor == "(Ljava/lang/String;)V"
                ) {
                    return "checkDiskRead"
                }
            }
            if (owner == "java/io/RandomAccessFile" && name == "<init>") {
                if (descriptor == "(Ljava/io/File;Ljava/lang/String;)V" ||
                    descriptor == "(Ljava/lang/String;Ljava/lang/String;)V"
                ) {
                    return "checkDiskRead"
                }
            }

            // Disk write (original and remapped wrapper class)
            if ((owner == "java/io/FileOutputStream" ||
                 owner == "com/bugsee/library/adapters/BugseeFileOutputStream") && name == "<init>") {
                if (descriptor == "(Ljava/io/File;)V" ||
                    descriptor == "(Ljava/lang/String;)V" ||
                    descriptor == "(Ljava/io/File;Z)V" ||
                    descriptor == "(Ljava/lang/String;Z)V"
                ) {
                    return "checkDiskWrite"
                }
            }

            // Network
            if (owner == "java/net/URL") {
                if (name == "openConnection" || name == "openStream") {
                    return "checkNetwork"
                }
            }
            if (owner == "java/net/Socket") {
                if (name == "<init>") {
                    if (descriptor == "(Ljava/lang/String;I)V" ||
                        descriptor == "(Ljava/net/InetAddress;I)V"
                    ) {
                        return "checkNetwork"
                    }
                }
                if (name == "connect") {
                    if (descriptor == "(Ljava/net/SocketAddress;)V" ||
                        descriptor == "(Ljava/net/SocketAddress;I)V"
                    ) {
                        return "checkNetwork"
                    }
                }
            }

            // OkHttp — synchronous execute blocks the calling thread
            if (owner == "okhttp3/Call" && name == "execute" &&
                descriptor == "()Lokhttp3/Response;"
            ) {
                return "checkNetwork"
            }

            // Coroutines — runBlocking blocks the calling thread
            if (owner == "kotlinx/coroutines/BuildersKt" && name == "runBlocking") {
                return "checkBlocking"
            }

            // Database — match by owner + name only (all overloads)
            if (owner == "android/database/sqlite/SQLiteDatabase") {
                if (name == "query" || name == "rawQuery" ||
                    name == "insert" || name == "insertOrThrow" ||
                    name == "insertWithOnConflict" ||
                    name == "update" || name == "updateWithOnConflict" ||
                    name == "delete" || name == "execSQL"
                ) {
                    return "checkDatabase"
                }
            }

            // SharedPreferences
            if (owner == "android/content/SharedPreferences\$Editor" && name == "commit") {
                return "checkSharedPrefs"
            }

            return null
        }
    }
}

private const val GUARD_ADAPTER_CLASS =
    "com/bugsee/library/adapters/BugseeMainThreadGuardAdapter"
