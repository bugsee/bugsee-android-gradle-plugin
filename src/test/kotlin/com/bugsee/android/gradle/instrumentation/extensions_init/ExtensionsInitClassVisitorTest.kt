package com.bugsee.android.gradle.instrumentation.extensions_init

import com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness
import com.bugsee.android.gradle.instrumentation.fixtures.JavaSourceCompiler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * End-to-end transform tests for [ExtensionsInitClassVisitor]. We compile
 * a stand-in `BugseeInitProvider` with a static `initializeExtensions()`
 * (static-vs-instance is invisible at the descriptor level the visitor
 * keys on, and static lets the test invoke without instantiating), run
 * the visitor with a list of specs that target [RecordingRegister], then
 * load and invoke the result to check what actually ran.
 */
class ExtensionsInitClassVisitorTest {

    private val recordingRegisterInternal =
        "com/bugsee/android/gradle/instrumentation/extensions_init/RecordingRegister"

    @Before
    fun setUp() {
        RecordingRegister.reset()
    }

    @After
    fun tearDown() {
        RecordingRegister.reset()
    }

    @Test
    fun `injects no calls when spec list is empty — body untouched`() {
        val classes = compileFixture()
        val transformed = applyTransform(classes, specs = emptyList())
        AsmTestHarness.verify(transformed).assertOk()

        // No INVOKESTATICs to RecordingRegister should appear.
        val invokes = dispatcherInvokes(transformed)
        assertTrue("empty spec list must produce zero register calls, got $invokes", invokes.isEmpty())

        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.SampleInitProvider" to transformed),
            "fixtures.SampleInitProvider",
            "initializeExtensions",
        )
        assertEquals(emptyList<String>(), RecordingRegister.calls)
    }

    @Test
    fun `injects one INVOKESTATIC per spec, in order`() {
        val classes = compileFixture()
        val transformed = applyTransform(
            classes,
            specs = listOf(
                specFor("registerFooExtension"),
                specFor("registerBarExtension"),
                specFor("registerBazExtension"),
            ),
        )
        AsmTestHarness.verify(transformed).assertOk()

        // Bytecode-level assertion: exactly three INVOKESTATIC calls to
        // RecordingRegister in the order we passed them.
        assertEquals(
            listOf("registerFooExtension", "registerBarExtension", "registerBazExtension"),
            dispatcherInvokes(transformed),
        )

        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.SampleInitProvider" to transformed),
            "fixtures.SampleInitProvider",
            "initializeExtensions",
        )
        assertEquals(listOf("foo", "bar", "baz"), RecordingRegister.calls)
    }

    @Test
    fun `throwing extension does not abort siblings — independent try-catches`() {
        // Mutates the production-side claim that each register call is
        // independently guarded. If the visitor swallowed throws via one
        // big outer try/catch, the explosion in the middle entry would
        // skip the third entry and only `foo` would run.
        val classes = compileFixture()
        val transformed = applyTransform(
            classes,
            specs = listOf(
                specFor("registerFooExtension"),
                specFor("registerExplodingExtension"),
                specFor("registerBazExtension"),
            ),
        )
        AsmTestHarness.verify(transformed).assertOk()

        AsmTestHarness.loadAndInvokeStatic(
            mapOf("fixtures.SampleInitProvider" to transformed),
            "fixtures.SampleInitProvider",
            "initializeExtensions",
        )
        // `exploding-entered` is recorded BEFORE the throw, so we expect
        // 4 entries total: foo, the exploding-entered marker, and baz.
        // If the try/catch swallow is broken, baz would be missing.
        assertEquals(
            listOf("foo", "exploding-entered", "baz"),
            RecordingRegister.calls,
        )
    }

    @Test
    fun `non-target methods pass through unchanged`() {
        // A method named differently (e.g. `onCreate`) must not be touched
        // even when specs are present. Catches a mutation that loosens
        // the method-name filter inside the visitor.
        val classes = JavaSourceCompiler.compile(
            "fixtures/Bystander.java",
            """
            package fixtures;
            public class Bystander {
                public static void onCreate() {
                    // no-op
                }
                public static void initializeExtensions() {
                    // target — must be wrapped
                }
            }
            """.trimIndent(),
        )
        val original = classes["fixtures.Bystander"]!!
        val transformed = AsmTestHarness.transform(original) { writer ->
            ExtensionsInitClassVisitor(
                Opcodes.ASM9,
                writer,
                listOf(specFor("registerFooExtension")),
            )
        }
        AsmTestHarness.verify(transformed).assertOk()

        // `onCreate` must have ZERO dispatcher invokes; `initializeExtensions`
        // must have exactly one.
        val onCreateInvokes = methodInvokes(transformed, "onCreate", "()V")
        val initInvokes = methodInvokes(transformed, "initializeExtensions", "()V")
        assertEquals(
            "onCreate body must not be instrumented",
            emptyList<String>(),
            onCreateInvokes,
        )
        assertEquals(
            "initializeExtensions body must contain the registered call",
            listOf("registerFooExtension"),
            initInvokes,
        )
    }

    @Test
    fun `injection happens before the existing RETURN — original body preserved`() {
        // The SDK's `initializeExtensions()` body is not empty (today it
        // emits a debug log); the visitor must append calls BEFORE the
        // RETURN, never replace the body. Sentinel pattern: the fixture
        // bumps a counter that we read after invocation.
        val classes = JavaSourceCompiler.compile(
            "fixtures/PreservingInitProvider.java",
            """
            package fixtures;
            public class PreservingInitProvider {
                public static int sentinel = 0;
                public static void initializeExtensions() {
                    sentinel = 7;
                }
            }
            """.trimIndent(),
        )
        val original = classes["fixtures.PreservingInitProvider"]!!
        val transformed = AsmTestHarness.transform(original) { writer ->
            ExtensionsInitClassVisitor(
                Opcodes.ASM9,
                writer,
                listOf(specFor("registerFooExtension")),
            )
        }
        AsmTestHarness.verify(transformed).assertOk()

        val loader = com.bugsee.android.gradle.instrumentation.fixtures.InMemoryClassLoader(
            mapOf("fixtures.PreservingInitProvider" to transformed)
        )
        val klass = loader.loadClass("fixtures.PreservingInitProvider")
        klass.getDeclaredMethod("initializeExtensions").invoke(null)

        // Both effects must happen: original body ran (sentinel set) AND
        // register call fired (RecordingRegister has foo).
        val sentinel = klass.getDeclaredField("sentinel").get(null) as Int
        assertEquals(7, sentinel)
        assertEquals(listOf("foo"), RecordingRegister.calls)
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun compileFixture(): Map<String, ByteArray> = JavaSourceCompiler.compile(
        "fixtures/SampleInitProvider.java",
        """
        package fixtures;
        public class SampleInitProvider {
            public static void initializeExtensions() {
                // empty body — pristine target for injection
            }
        }
        """.trimIndent(),
    )

    private fun applyTransform(
        classes: Map<String, ByteArray>,
        specs: List<ExtensionSpec>,
    ): ByteArray {
        val original = classes["fixtures.SampleInitProvider"]!!
        return AsmTestHarness.transform(original) { writer ->
            ExtensionsInitClassVisitor(Opcodes.ASM9, writer, specs)
        }
    }

    private fun specFor(registerMethodName: String): ExtensionSpec = ExtensionSpec(
        facadeInternalName = recordingRegisterInternal,
        registerMethodName = registerMethodName,
        initProviderFqn = "test.fake.$registerMethodName.Provider",
    )

    /** Collects names of all INVOKESTATIC calls to the recording register inside `initializeExtensions()`. */
    private fun dispatcherInvokes(classBytes: ByteArray): List<String> =
        methodInvokes(classBytes, "initializeExtensions", "()V")

    /** Collects names of all INVOKESTATIC calls to the recording register inside the named method. */
    private fun methodInvokes(classBytes: ByteArray, methodName: String, descriptor: String): List<String> {
        val node = ClassNode()
        ClassReader(classBytes).accept(node, 0)
        val method = node.methods.first { it.name == methodName && it.desc == descriptor }
        return method.instructions
            .toArray()
            .filterIsInstance<MethodInsnNode>()
            .filter { it.opcode == Opcodes.INVOKESTATIC && it.owner == recordingRegisterInternal }
            .map { it.name }
    }
}
