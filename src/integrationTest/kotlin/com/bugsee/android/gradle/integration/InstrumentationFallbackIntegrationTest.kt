package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end proof (follow-up to #74, issue #97) that a third-party library class whose method
 * only becomes too large once instrumented does NOT fail the app build: the method is left
 * uninstrumented with a warning, while a sibling class in the same jar is still instrumented.
 *
 * Before #74 this build failed with `MethodTooLargeException: Method too large: a/b/e.a`.
 */
class InstrumentationFallbackIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * `a/b/e.a(URL)`: 65,530 bytes of code with one guarded call (`URL.openStream`).
     * `a/b/ok.a(Editor)`: tiny, with a guarded `SharedPreferences.Editor.commit` (a "Prefs" dispatch,
     * which nothing else in the fixture's dependencies, kotlin-stdlib included, emits).
     */
    private fun libraryJar(): File {
        fun cls(name: String, nops: Int, prefs: Boolean): ByteArray {
            val editor = "android/content/SharedPreferences\$Editor"
            val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
            val desc = if (prefs) "(L$editor;)V" else "(Ljava/net/URL;)V"
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "a", desc, null, null)
            mv.visitCode()
            repeat(nops) { mv.visitInsn(Opcodes.NOP) }
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            if (prefs) {
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, editor, "commit", "()Z", true)
            } else {
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/URL", "openStream", "()Ljava/io/InputStream;", false)
            }
            mv.visitInsn(Opcodes.POP)
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }

        // `a/b/big`: a constant pool within the headroom of the 65,535 limit (the ClassTooLarge case).
        fun bigPoolClass(): ByteArray {
            val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "a/b/big", null, "java/lang/Object", null)
            for (m in 0 until 4) {
                val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "f$m", "()V", null, null)
                mv.visitCode()
                repeat(7_750) { mv.visitLdcInsn("s${m}_$it"); mv.visitInsn(Opcodes.POP) }
                mv.visitInsn(Opcodes.RETURN)
                mv.visitMaxs(0, 0)
                mv.visitEnd()
            }
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "a", "(Ljava/net/URL;)V", null, null)
            mv.visitCode()
            mv.visitVarInsn(Opcodes.ALOAD, 0)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/URL", "openStream", "()Ljava/io/InputStream;", false)
            mv.visitInsn(Opcodes.POP)
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }

        val jar = temp.newFile("lib.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("a/b/big.class"))
            zip.write(bigPoolClass())
            zip.closeEntry()
            for ((name, nops, prefs) in listOf(Triple("a/b/e", 65_524, false), Triple("a/b/ok", 0, true))) {
                zip.putNextEntry(ZipEntry("$name.class"))
                zip.write(cls(name, nops, prefs))
                zip.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun tooLargeLibraryMethodIsLeftUninstrumentedAndTheBuildSucceeds() {
        val fixture = FixtureProject.materialize("app-startup-tracing", temp.newFolder("fallback"))

        val result = fixture.buildTasks(
            listOf(":app:assembleDebug"),
            "STANDARD",
            "-PbugseeFixtureLibJar=${libraryJar().absolutePath}",
        )

        assertTrue(
            "the warning must name the class: ${result.output}",
            result.output.contains("a.b.e.a(Ljava/net/URL;)V was left UNINSTRUMENTED"),
        )

        assertTrue(
            "the near-full constant pool class must be skipped whole: ${result.output}",
            result.output.contains("a.b.big was left UNINSTRUMENTED: its constant pool"),
        )

        // File dependencies are only kept as dex (no transformed .class survives), so inspect the
        // merged external dex. Both classes made it in, and the sibling's Prefs dispatch (emitted
        // by no other dependency) proves the lane still ran on its class.
        val dexDir = File(fixture.projectDir, "app/build/intermediates/dex/debug/mergeExtDexDebug")
        val text = dexDir.walkTopDown().filter { it.isFile && it.name.endsWith(".dex") }.toList()
            .joinToString("\n") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertTrue("merged external dex not found in $dexDir", text.isNotEmpty())
        assertTrue("the too-large class must be in the dex", text.contains("La/b/e;"))
        assertTrue("the skipped class must be in the dex", text.contains("La/b/big;"))
        assertTrue("the sibling class must be in the dex", text.contains("La/b/ok;"))
        assertTrue("the sibling must still be instrumented", text.contains("onPrefsOperationStart"))
    }
}
