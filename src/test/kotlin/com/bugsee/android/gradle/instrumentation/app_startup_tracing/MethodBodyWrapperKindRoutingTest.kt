package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.bugsee.android.gradle.instrumentation.fixtures.JavaSourceCompiler
import org.junit.Assert.assertEquals
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

/**
 * Direct unit tests for [MethodBodyWrapper.wrap]'s new optional
 * `startMethodName` / `endMethodName` parameters (Issue 2).
 *
 * These tests bypass [AppStartupTracingClassVisitor] entirely — they
 * compile a tiny class with `JavaSourceCompiler`, surface the target
 * method as a [MethodNode], call `MethodBodyWrapper.wrap(...)` directly
 * with the desired entry-point names, and scan the resulting bytecode
 * for INVOKESTATIC instructions targeting the dispatcher.
 *
 * The point is to lock in the parameterization: the wrapper MUST honor
 * the names passed in. A regression where wrap() ignores the optional
 * parameters and hard-codes "onMethodStart"/"onMethodEnd" would surface
 * here directly without dragging the class visitor's routing logic into
 * the test surface.
 */
class MethodBodyWrapperKindRoutingTest {

    private val dispatcherInternal = "com/bugsee/test/fixtures/RecordingStartupDispatcher"

    /**
     * Compiles a tiny holder class with a `void` method whose body is a
     * trivial expression (single implicit RETURN). The resulting
     * MethodNode is what the production transform would buffer.
     */
    private fun compileSingleReturnVoidMethod(): MethodNode {
        val classes = JavaSourceCompiler.compile(
            "fixtures/KindRoutingFixture.java",
            """
            package fixtures;
            public class KindRoutingFixture {
                public static void target() {
                    int x = 1 + 2;
                }
            }
            """.trimIndent(),
        )
        val bytes = classes["fixtures.KindRoutingFixture"]!!
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        return node.methods.first { it.name == "target" && it.desc == "()V" }
    }

    /**
     * Returns the list of `name`s of every INVOKESTATIC instruction
     * targeting the [dispatcherInternal] owner. The bytecode wrapper
     * emits exactly these — one start (prefix) + N ends (one per return
     * + one in the catch-any handler).
     */
    private fun dispatcherInvokeNames(method: MethodNode): List<String> {
        return method.instructions
            .toArray()
            .filterIsInstance<MethodInsnNode>()
            .filter { it.owner == dispatcherInternal }
            .map { it.name }
    }

    // Catches a mutation that ignores startMethodName/endMethodName and
    // hard-codes "onMethodStart"/"onMethodEnd" — wrap with the APPLICATION
    // pair, observe whether the bytecode actually carries the kind-specific
    // names.
    @Test fun `wrap with onApplicationStart and onApplicationEnd emits APPLICATION pair`() {
        val method = compileSingleReturnVoidMethod()
        MethodBodyWrapper.wrap(
            method,
            "fixtures.KindRoutingFixture#target",
            dispatcherInternal,
            "onApplicationStart",
            "onApplicationEnd",
        )
        val names = dispatcherInvokeNames(method)
        // 1 start (prefix) + 1 end (per implicit RETURN) + 1 end (catch-any
        // handler) = 1 onApplicationStart + 2 onApplicationEnd.
        assertEquals(1, names.count { it == "onApplicationStart" })
        assertEquals(2, names.count { it == "onApplicationEnd" })
        // No foreign dispatcher target slipped in.
        assertEquals(
            "no foreign dispatcher method emitted",
            emptyList<String>(),
            names.filter { it !in setOf("onApplicationStart", "onApplicationEnd") },
        )
    }

    // Catches a mutation that hard-codes the wrong entry-point pair for
    // ContentProvider — wrap with onProviderStart/End, verify the bytecode
    // carries that exact pair.
    @Test fun `wrap with onProviderStart and onProviderEnd emits PROVIDER pair`() {
        val method = compileSingleReturnVoidMethod()
        MethodBodyWrapper.wrap(
            method,
            "fixtures.KindRoutingFixture#target",
            dispatcherInternal,
            "onProviderStart",
            "onProviderEnd",
        )
        val names = dispatcherInvokeNames(method)
        assertEquals(1, names.count { it == "onProviderStart" })
        assertEquals(2, names.count { it == "onProviderEnd" })
        assertEquals(
            "no foreign dispatcher method emitted",
            emptyList<String>(),
            names.filter { it !in setOf("onProviderStart", "onProviderEnd") },
        )
    }

    // Catches a mutation that hard-codes the wrong entry-point pair for the
    // FULL-tier @BugseeTrace path — wrap with onAnnotatedStart/End.
    @Test fun `wrap with onAnnotatedStart and onAnnotatedEnd emits ANNOTATED pair`() {
        val method = compileSingleReturnVoidMethod()
        MethodBodyWrapper.wrap(
            method,
            "fixtures.KindRoutingFixture#target",
            dispatcherInternal,
            "onAnnotatedStart",
            "onAnnotatedEnd",
        )
        val names = dispatcherInvokeNames(method)
        assertEquals(1, names.count { it == "onAnnotatedStart" })
        assertEquals(2, names.count { it == "onAnnotatedEnd" })
        assertEquals(
            "no foreign dispatcher method emitted",
            emptyList<String>(),
            names.filter { it !in setOf("onAnnotatedStart", "onAnnotatedEnd") },
        )
    }

    // Catches a mutation that flips the default values for the optional
    // start/end parameters (e.g. default = "onApplicationStart"). The
    // pre-Issue-2 callers omit the names and rely on the
    // onMethodStart/onMethodEnd defaults.
    @Test fun `wrap with no explicit names defaults to onMethodStart and onMethodEnd`() {
        val method = compileSingleReturnVoidMethod()
        // Note: no startMethodName / endMethodName arguments — exercises
        // the default-value branch.
        MethodBodyWrapper.wrap(
            method,
            "fixtures.KindRoutingFixture#target",
            dispatcherInternal,
        )
        val names = dispatcherInvokeNames(method)
        assertEquals(1, names.count { it == "onMethodStart" })
        assertEquals(2, names.count { it == "onMethodEnd" })
        assertEquals(
            "no foreign dispatcher method emitted on default-names wrap",
            emptyList<String>(),
            names.filter { it !in setOf("onMethodStart", "onMethodEnd") },
        )
    }

    // Catches a mutation that swaps start and end roles (e.g. emits the
    // `endMethodName` in the prefix and the `startMethodName` in the
    // suffix). Verifies that wrap() respects the START vs END ordering by
    // passing intentionally-asymmetric labels and asserting the START
    // shows up once (prefix) and END shows up twice (per-return + catch).
    @Test fun `wrap respects start vs end role assignment`() {
        val method = compileSingleReturnVoidMethod()
        MethodBodyWrapper.wrap(
            method,
            "fixtures.KindRoutingFixture#target",
            dispatcherInternal,
            "onApplicationStart",
            "onApplicationEnd",
        )
        val names = dispatcherInvokeNames(method)
        // Find the position of the first dispatcher INVOKESTATIC and the
        // last one — the first MUST be the start; the last MUST be the
        // end (catch-any handler ATHROW path).
        val ordered = names
        assertEquals("onApplicationStart", ordered.first())
        assertEquals("onApplicationEnd", ordered.last())
    }
}
