package com.bugsee.android.gradle.instrumentation.extensions_init

import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationContext
import com.bugsee.android.gradle.instrumentation.util.MinifiedClassSkip
import org.gradle.api.provider.Property
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * Drives [ExtensionsInitClassVisitorFactory.createClassVisitor] the way AGP does —
 * a `ClassWriter(reader, flags)` downstream of the factory's visitor — over a
 * `BugseeInitProvider` shaped like the PUBLISHED SDK's.
 *
 * The SDK repo's own sample app compiles `:library` from source, so it never feeds
 * the plugin an R8-processed class; these tests are the only place that does.
 */
class ExtensionsInitClassVisitorFactoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Regression (plugin 4.0.6): the published SDK is self-minified, so its
     * `BugseeInitProvider` carries R8's `~~R8` marker. The lane consulted
     * MinifiedClassSkip, matched it, and left `initializeExtensions()` empty while the
     * manifest task still stripped the providers — NDK, feedback, compose… never
     * registered, with a green build.
     */
    @Test
    fun `injects into the R8-processed BugseeInitProvider the published SDK ships`() {
        val input = initProvider(r8Marker = true, withHook = true)
        check(MinifiedClassSkip.shouldSkip(ClassWriter(ClassReader(input), 0))) {
            "fixture must look R8-processed, or this test proves nothing"
        }

        val output = transform(input, detected = listOf("com.bugsee.library.BugseeNdkInitProvider"))

        assertEquals(
            listOf("com/bugsee/library/BugseeNdk.registerNdkExtension()V"),
            staticCallsIn(output, "initializeExtensions"),
        )
    }

    /**
     * Stripped providers without a hook to register them in must fail the build by
     * name, not ship silently dead.
     */
    @Test
    fun `fails the build naming the providers when initializeExtensions is missing`() {
        val input = initProvider(r8Marker = true, withHook = false)
        try {
            transform(input, detected = listOf(
                "com.bugsee.library.BugseeNdkInitProvider",
                "com.bugsee.library.BugseeFeedbackInitProvider",
            ))
            fail("expected the transform to fail")
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            assertTrue(message, "com.bugsee.library.BugseeNdkInitProvider" in message)
            assertTrue(message, "com.bugsee.library.BugseeFeedbackInitProvider" in message)
            assertTrue(message, "turn off optimizeExtensionsLoading" in message)
        }
    }

    /** Nothing was stripped, so a class without the hook (an old SDK) is fine. */
    @Test
    fun `leaves a class without the hook alone when nothing was stripped`() {
        val input = initProvider(r8Marker = true, withHook = false)
        transform(input, detected = emptyList())
    }

    /** Unknown lines — a stale file, or a provider the table does not own — inject nothing. */
    @Test
    fun `ignores detection entries that are not known extensions`() {
        val input = initProvider(r8Marker = false, withHook = true)

        val output = transform(input, detected = listOf("com.acme.probe.BugseeFooInitProvider"))

        assertEquals(emptyList<String>(), staticCallsIn(output, "initializeExtensions"))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun transform(input: ByteArray, detected: List<String>): ByteArray {
        val detectedFile = tempFolder.newFile().apply { writeText(detected.joinToString("\n")) }
        val reader = ClassReader(input)
        val writer = ClassWriter(reader, 0)
        val visitor = factory(detectedFile).createClassVisitor(
            FakeClassContext(TARGET.replace('/', '.')),
            writer,
        )
        reader.accept(visitor, 0)
        return writer.toByteArray()
    }

    /**
     * `BugseeInitProvider` with an instance `initializeExtensions()V` (as the SDK
     * ships it), optionally carrying R8's marker at the head of the constant pool —
     * where R8 writes it (`javap -v` on bugsee-android 7.2.0: #1 Utf8 `~~R8{…}`,
     * #2 String).
     */
    private fun initProvider(r8Marker: Boolean, withHook: Boolean): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        if (r8Marker) cw.newConst("~~R8{\"backend\":\"cf\",\"compilation-mode\":\"release\"}")
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null)
        if (withHook) {
            val mv = cw.visitMethod(0, "initializeExtensions", "()V", null, null)
            mv.visitCode()
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun staticCallsIn(bytes: ByteArray, method: String): List<String> {
        val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }
        val target = node.methods.single { it.name == method }
        return target.instructions.toArray()
            .filterIsInstance<MethodInsnNode>()
            .filter { it.opcode == Opcodes.INVOKESTATIC }
            .map { "${it.owner}.${it.name}${it.desc}" }
    }

    private fun factory(detectedFile: java.io.File): ExtensionsInitClassVisitorFactory {
        val objects = ProjectBuilder.builder().build().objects
        val params = objects.newInstance(ExtensionsInitParameters::class.java)
        params.detectedExtensionsFile.set(detectedFile)
        params.excludes.set(emptySet<String>())
        return TestFactory(objects.property(ExtensionsInitParameters::class.java).value(params))
    }

    private class TestFactory(
        override val parameters: Property<ExtensionsInitParameters>,
    ) : ExtensionsInitClassVisitorFactory() {
        override val instrumentationContext: InstrumentationContext
            get() = throw UnsupportedOperationException("not used by createClassVisitor")
    }

    private class FakeClassContext(name: String) : ClassContext {
        override val currentClassData: ClassData = object : ClassData {
            override val className = name
            override val classAnnotations = emptyList<String>()
            override val interfaces = emptyList<String>()
            override val superClasses = emptyList<String>()
        }

        override fun loadClassData(className: String): ClassData? = null
    }

    private companion object {
        const val TARGET = "com/bugsee/library/BugseeInitProvider"
    }
}
