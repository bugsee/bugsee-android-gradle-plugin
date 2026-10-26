package com.bugsee.android.gradle.instrumentation.operation_dispatch

import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import com.bugsee.android.gradle.instrumentation.fixtures.InMemoryClassLoader
import com.bugsee.android.gradle.instrumentation.fixtures.JavaSourceCompiler
import com.bugsee.android.gradle.instrumentation.util.NewInitPairing
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #42: NEW / `<init>` must be paired by data flow, not instruction order.
 *
 * ASM's CheckClassAdapter/SimpleVerifier does not model uninitialized types, so it
 * ACCEPTS a mispaired rewrite. These tests therefore define the transformed class on a
 * real JVM class loader (the JVM verifier runs for every non-boot class) against stub
 * wrapper classes, and also assert which owners ended up where.
 */
class OperationDispatchNewInitPairingTest {

    private val fis = "java/io/FileInputStream"
    private val fos = "java/io/FileOutputStream"
    private val wFis = "com/bugsee/library/adapters/BugseeFileInputStream"
    private val wFos = "com/bugsee/library/adapters/BugseeFileOutputStream"
    private val obj = "java/lang/Object"

    private val stubs: Map<String, ByteArray> by lazy {
        fun stub(simple: String, base: String) = """
            package com.bugsee.library.adapters;
            public class $simple extends java.io.$base {
                public $simple(java.io.File f) throws java.io.FileNotFoundException { super(f); }
                public $simple(java.lang.String s) throws java.io.FileNotFoundException { super(s); }
                public $simple(java.io.FileDescriptor fd) { super(fd); }
            }
        """.trimIndent()
        JavaSourceCompiler.compileAll(
            mapOf(
                "BugseeFileInputStream.java" to stub("BugseeFileInputStream", "FileInputStream"),
                "BugseeFileOutputStream.java" to stub("BugseeFileOutputStream", "FileOutputStream"),
            )
        )
    }

    // ---- bytecode synthesis -------------------------------------------------

    private class Gen : ClassWriter(COMPUTE_FRAMES or COMPUTE_MAXS) {
        override fun getCommonSuperClass(type1: String, type2: String) = "java/lang/Object"
    }

    private fun genClass(vararg methods: Pair<String, (MethodVisitor) -> Unit>): ByteArray {
        val cw = Gen()
        cw.visit(V11, ACC_PUBLIC, "fx/Gen", null, obj, null)
        for ((name, body) in methods) {
            val mv = cw.visitMethod(
                ACC_PUBLIC or ACC_STATIC, name, "(Ljava/io/File;Ljava/io/File;)Ljava/lang/Object;",
                null, arrayOf("java/lang/Exception"),
            )
            mv.visitCode()
            body(mv)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun MethodVisitor.initFile(owner: String) =
        visitMethodInsn(INVOKESPECIAL, owner, "<init>", "(Ljava/io/File;)V", false)

    private fun transform(bytes: ByteArray) =
        AsmTestHarness.transform(bytes) { OperationDispatchClassVisitor(it, "fx.Gen") }

    /** Defines the class on a real loader: this runs the JVM bytecode verifier. */
    private fun load(bytes: ByteArray): Class<*> =
        Class.forName("fx.Gen", true, InMemoryClassLoader(stubs + ("fx.Gen" to bytes), javaClass.classLoader))

    private fun call(c: Class<*>, method: String, a: File, b: File): Any? = try {
        c.getMethod(method, File::class.java, File::class.java).invoke(null, a, b)
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    private fun insns(bytes: ByteArray, method: String) =
        ClassNode().also { ClassReader(bytes).accept(it, 0) }.methods
            .first { it.name == method }.instructions.toList()

    private fun news(bytes: ByteArray, m: String) =
        insns(bytes, m).filterIsInstance<TypeInsnNode>().filter { it.opcode == NEW }.map { it.desc }

    private fun inits(bytes: ByteArray, m: String) =
        insns(bytes, m).filterIsInstance<MethodInsnNode>().filter { it.name == "<init>" }.map { it.owner }

    private fun tmp(): Pair<File, File> {
        val i = File.createTempFile("i42", ".in").apply { writeText("x"); deleteOnExit() }
        val o = File.createTempFile("i42", ".out").apply { deleteOnExit() }
        return i to o
    }

    private val reordered = "reordered" to { mv: MethodVisitor ->
        // NEW FIS; astore 2; NEW FOS; astore 3; aload 2; aload 0; <init FIS>; aload 3; aload 1; <init FOS>
        mv.visitTypeInsn(NEW, fis); mv.visitVarInsn(ASTORE, 2)
        mv.visitTypeInsn(NEW, fos); mv.visitVarInsn(ASTORE, 3)
        mv.visitVarInsn(ALOAD, 2); mv.visitVarInsn(ALOAD, 0); mv.initFile(fis)
        mv.visitVarInsn(ALOAD, 3); mv.visitVarInsn(ALOAD, 1); mv.initFile(fos)
        mv.visitInsn(ICONST_2); mv.visitTypeInsn(ANEWARRAY, obj)
        mv.visitInsn(DUP); mv.visitInsn(ICONST_0); mv.visitVarInsn(ALOAD, 2); mv.visitInsn(AASTORE)
        mv.visitInsn(DUP); mv.visitInsn(ICONST_1); mv.visitVarInsn(ALOAD, 3); mv.visitInsn(AASTORE)
        mv.visitInsn(ARETURN)
    }

    @Test
    fun `reordered NEW and init (issue 42) loads on a real JVM and is remapped consistently`() {
        val original = genClass(reordered)
        load(original) // sanity: the synthesized input is valid
        val out = transform(original)

        val cls = load(out) // JVM verifier: "Call to wrong <init> method" on unfixed main
        val (i, o) = tmp()
        val r = call(cls, "reordered", i, o) as Array<*>
        assertEquals("com.bugsee.library.adapters.BugseeFileInputStream", r[0]!!.javaClass.name)
        assertEquals("com.bugsee.library.adapters.BugseeFileOutputStream", r[1]!!.javaClass.name)
        assertEquals(listOf(wFis, wFos), news(out, "reordered"))
        assertEquals(listOf(wFis, wFos), inits(out, "reordered"))
    }

    @Test
    fun `reordered inits of the same type are remapped consistently`() {
        val gen = genClass("sameType" to { mv: MethodVisitor ->
            mv.visitTypeInsn(NEW, fis); mv.visitVarInsn(ASTORE, 2)
            mv.visitTypeInsn(NEW, fis); mv.visitVarInsn(ASTORE, 3)
            mv.visitVarInsn(ALOAD, 3); mv.visitVarInsn(ALOAD, 0); mv.initFile(fis)
            mv.visitVarInsn(ALOAD, 2); mv.visitVarInsn(ALOAD, 0); mv.initFile(fis)
            mv.visitVarInsn(ALOAD, 2); mv.visitInsn(ARETURN)
        })
        val out = transform(gen)
        val (i, o) = tmp()
        assertEquals("com.bugsee.library.adapters.BugseeFileInputStream", call(load(out), "sameType", i, o)!!.javaClass.name)
        assertEquals(listOf(wFis, wFis), inits(out, "sameType"))
    }

    @Test
    fun `javac shaped NEW DUP args init is remapped and loads`() {
        val gen = genClass("normal" to { mv: MethodVisitor ->
            mv.visitTypeInsn(NEW, fis); mv.visitInsn(DUP); mv.visitVarInsn(ALOAD, 0); mv.initFile(fis)
            mv.visitInsn(ARETURN)
        })
        val out = transform(gen)
        val (i, o) = tmp()
        assertEquals("com.bugsee.library.adapters.BugseeFileInputStream", call(load(out), "normal", i, o)!!.javaClass.name)
        assertEquals(listOf(wFis), news(out, "normal"))
        assertEquals(listOf(wFis), inits(out, "normal"))
    }

    @Test
    fun `NEW DUP stored to a local before init`() {
        val gen = genClass("dupLocal" to { mv: MethodVisitor ->
            mv.visitTypeInsn(NEW, fos); mv.visitInsn(DUP); mv.visitVarInsn(ASTORE, 2)
            mv.visitVarInsn(ALOAD, 1); mv.initFile(fos)
            mv.visitVarInsn(ALOAD, 2); mv.visitInsn(ARETURN)
        })
        val out = transform(gen)
        val (i, o) = tmp()
        assertEquals("com.bugsee.library.adapters.BugseeFileOutputStream", call(load(out), "dupLocal", i, o)!!.javaClass.name)
        assertEquals(listOf(wFos), inits(out, "dupLocal"))
    }

    @Test
    fun `DUP_X1 and DUP_X2 forms`() {
        val gen = genClass(
            "dupX1" to { mv: MethodVisitor ->
                mv.visitLdcInsn("tag")
                mv.visitTypeInsn(NEW, fis); mv.visitInsn(DUP_X1) // [u, tag, u]
                mv.visitVarInsn(ALOAD, 0); mv.initFile(fis)      // [u, tag]
                mv.visitInsn(POP); mv.visitInsn(ARETURN)
            },
            "dupX2" to { mv: MethodVisitor ->
                mv.visitLdcInsn("a"); mv.visitLdcInsn("b")
                mv.visitTypeInsn(NEW, fos); mv.visitInsn(DUP_X2) // [u, a, b, u]
                mv.visitVarInsn(ALOAD, 1); mv.initFile(fos)      // [u, a, b]
                mv.visitInsn(POP2); mv.visitInsn(ARETURN)
            },
        )
        val out = transform(gen)
        val (i, o) = tmp()
        val c = load(out)
        assertEquals("com.bugsee.library.adapters.BugseeFileInputStream", call(c, "dupX1", i, o)!!.javaClass.name)
        assertEquals("com.bugsee.library.adapters.BugseeFileOutputStream", call(c, "dupX2", i, o)!!.javaClass.name)
        assertEquals(listOf(wFis), inits(out, "dupX1"))
        assertEquals(listOf(wFos), inits(out, "dupX2"))
    }

    @Test
    fun `branch between NEW and init (ternary argument)`() {
        val gen = genClass("ternary" to { mv: MethodVisitor ->
            val other = Label()
            val join = Label()
            mv.visitTypeInsn(NEW, fis); mv.visitInsn(DUP)
            mv.visitVarInsn(ALOAD, 1); mv.visitJumpInsn(IFNULL, other)
            mv.visitVarInsn(ALOAD, 0); mv.visitJumpInsn(GOTO, join)
            mv.visitLabel(other); mv.visitVarInsn(ALOAD, 0)
            mv.visitLabel(join); mv.initFile(fis)
            mv.visitInsn(ARETURN)
        })
        val out = transform(gen)
        val (i, o) = tmp()
        assertEquals("com.bugsee.library.adapters.BugseeFileInputStream", call(load(out), "ternary", i, o)!!.javaClass.name)
        assertEquals(listOf(wFis), inits(out, "ternary"))
    }

    @Test
    fun `init inside a try catch block`() {
        val gen = genClass("tryInit" to { mv: MethodVisitor ->
            val s = Label()
            val e = Label()
            val h = Label()
            mv.visitTryCatchBlock(s, e, h, "java/io/IOException")
            mv.visitLabel(s)
            mv.visitTypeInsn(NEW, fis); mv.visitInsn(DUP); mv.visitVarInsn(ALOAD, 0); mv.initFile(fis)
            mv.visitLabel(e); mv.visitInsn(ARETURN)
            mv.visitLabel(h); mv.visitInsn(POP); mv.visitInsn(ACONST_NULL); mv.visitInsn(ARETURN)
        })
        val out = transform(gen)
        val (i, o) = tmp()
        assertEquals("com.bugsee.library.adapters.BugseeFileInputStream", call(load(out), "tryInit", i, o)!!.javaClass.name)
        assertEquals(listOf(wFis), news(out, "tryInit"))
    }

    @Test
    fun `nested allocation inside the arguments of another`() {
        // new FileOutputStream(new FileInputStream(f).getFD()) with the outer NEW kept in a local.
        val gen = genClass("nested" to { mv: MethodVisitor ->
            mv.visitTypeInsn(NEW, fos); mv.visitVarInsn(ASTORE, 2)
            mv.visitVarInsn(ALOAD, 2)
            mv.visitTypeInsn(NEW, fis); mv.visitInsn(DUP); mv.visitVarInsn(ALOAD, 0); mv.initFile(fis)
            mv.visitMethodInsn(INVOKEVIRTUAL, fis, "getFD", "()Ljava/io/FileDescriptor;", false)
            mv.visitMethodInsn(INVOKESPECIAL, fos, "<init>", "(Ljava/io/FileDescriptor;)V", false)
            mv.visitVarInsn(ALOAD, 2); mv.visitInsn(ARETURN)
        })
        val out = transform(gen)
        val (i, o) = tmp()
        assertEquals("com.bugsee.library.adapters.BugseeFileOutputStream", call(load(out), "nested", i, o)!!.javaClass.name)
        assertEquals(listOf(wFos, wFis), news(out, "nested"))
        assertEquals(listOf(wFis, wFos), inits(out, "nested"))
    }

    @Test
    fun `this and super calls on uninitializedThis are untouched and the class loads`() {
        val src = """
            import java.io.*;
            public class P extends FileInputStream {
                public P(File f) throws IOException { super(f); }
                public P(String s) throws IOException { this(new File(s)); }
            }
        """.trimIndent()
        val bytes = JavaSourceCompiler.compile("P.java", src).getValue("P")
        val out = AsmTestHarness.transform(bytes) { OperationDispatchClassVisitor(it, "P") }
        AsmTestHarness.verify(out).assertOk()
        val f = File.createTempFile("i42", ".p").apply { writeText("x"); deleteOnExit() }
        val c = Class.forName("P", true, InMemoryClassLoader(mapOf("P" to out), javaClass.classLoader))
        (c.getConstructor(String::class.java).newInstance(f.path) as java.io.Closeable).close()
        assertTrue(inits(out, "<init>").all { it == fis || it == "P" }, "no wrapper owner may appear in a ctor chain")
    }

    @Test
    fun `mismatched init owner leaves the NEW untouched`() {
        // NEW FIS whose value is initialized by <init FOS>: invalid for the JVM, but the pairing
        // must never rewrite one side only.
        val mn = MethodNode(ASM9, ACC_STATIC, "m", "(Ljava/io/File;)V", null, null)
        mn.visitCode()
        mn.visitTypeInsn(NEW, fis); mn.visitInsn(DUP); mn.visitVarInsn(ALOAD, 0)
        mn.visitMethodInsn(INVOKESPECIAL, fos, "<init>", "(Ljava/io/File;)V", false)
        mn.visitInsn(POP); mn.visitInsn(RETURN)
        mn.visitMaxs(4, 1)
        NewInitPairing.rewrite("fx/Gen", mn) { if (it == fis) wFis else if (it == fos) wFos else null }
        assertEquals(listOf(fis), mn.instructions.toList().filterIsInstance<TypeInsnNode>().map { it.desc })
    }

    @Test
    fun `unprovable method (init in dead code) is left entirely unrewritten`() {
        val mn = MethodNode(ASM9, ACC_STATIC, "m", "(Ljava/io/File;)V", null, null)
        mn.visitCode()
        mn.visitTypeInsn(NEW, fis); mn.visitInsn(DUP); mn.visitVarInsn(ALOAD, 0)
        mn.visitMethodInsn(INVOKESPECIAL, fis, "<init>", "(Ljava/io/File;)V", false)
        mn.visitInsn(POP); mn.visitInsn(RETURN)
        // dead code
        mn.visitVarInsn(ALOAD, 0)
        mn.visitMethodInsn(INVOKESPECIAL, fis, "<init>", "(Ljava/io/File;)V", false)
        mn.visitInsn(RETURN)
        mn.visitMaxs(4, 1)
        val n = NewInitPairing.rewrite("fx/Gen", mn) { if (it == fis) wFis else null }
        assertEquals(0, n)
        assertTrue(mn.instructions.toList().filterIsInstance<TypeInsnNode>().all { it.desc == fis })
        assertTrue(mn.instructions.toList().filterIsInstance<MethodInsnNode>().all { it.owner == fis })
    }
}
