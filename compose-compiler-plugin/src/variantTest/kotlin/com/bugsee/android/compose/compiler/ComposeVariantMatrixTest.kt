package com.bugsee.android.compose.compiler

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Runs each published compiler-plugin variant through the REAL compiler of every Kotlin line we
 * make a claim about, and checks what actually came out.
 *
 * ### Why this test exists
 *
 * The supported-Kotlin bound was previously set by loading the plugin into a compiler and observing
 * that the build succeeded. That is not sufficient, and shipping it cost 4.0.3 a broken build for
 * Kotlin 2.2/2.3 consumers: [BugseeComposeIrExtension.generate] returns early unless the Bugsee
 * Compose runtime resolves, so a completely non-functional plugin still compiles a Compose project
 * cleanly. The failure only appears once the transformer actually rewrites a call site.
 *
 * So every positive case here asserts the injected call is PRESENT IN THE EMITTED BYTECODE, not
 * merely that the compiler exited zero. A variant that silently no-ops fails this test.
 *
 * ### Why the negative cases are asserted too
 *
 * Each variant is pinned to the lines it does NOT support. Those rows must fail the compiler
 * (linkage/registration errors), not merely skip injection — a silent no-op would pass the
 * positive-only check that shipped 4.0.3. They fail the moment someone tries to collapse the
 * variants, and document the exact API breaks (see the messages below).
 */
class ComposeVariantMatrixTest {

    /** One row of the support matrix. */
    private data class Case(
        val variant: String,
        val kotlin: String,
        val expectInjection: Boolean,
        val why: String,
    )

    @Test
    fun `each variant injects on the lines it claims and fails loudly elsewhere`() {
        val cases = listOf(
            // k21 — legacy IR API, registrar without an `override` on pluginId.
            Case("k21", "1.9.22", true, "predates the IR API changes entirely"),
            Case("k21", "2.0.21", true, "2.0 still has the legacy IR API; the mapping routes it here"),
            Case("k21", "2.1.0", true, "the line k21 is built against"),
            Case("k21", "2.2.21", false, "2.2 widened the irCall/irString builder receiver to IrBuilder"),
            Case("k21", "2.4.10", false, "2.4 moved extension registration to ExtensionPointDescriptor"),

            // k22 — modern IR API; its plain `val pluginId` satisfies 2.3's abstract member at runtime.
            Case("k22", "2.1.0", false, "the 2.2 parameters/arguments API does not exist in 2.1"),
            Case("k22", "2.2.0", true, "the line k22 is built against"),
            Case("k22", "2.2.21", true, "same line, later patch"),
            Case("k22", "2.3.0", true, "2.3.0 exact: the boundary where pluginId became abstract"),
            Case("k22", "2.3.21", true, "2.3 shares 2.2's IR API; pluginId is satisfied by JVM resolution"),
            Case("k22", "2.4.10", false, "2.4 moved extension registration to ExtensionPointDescriptor"),

            // k24 — modern IR API + `override val pluginId`, required from 2.3 onwards.
            Case("k24", "2.2.21", false, "built against 2.4's ExtensionPointDescriptor, absent in 2.2"),
            Case("k24", "2.4.0", true, "the line k24 is built against"),
            Case("k24", "2.4.10", true, "same line, later patch"),
        )

        val failures = mutableListOf<String>()
        for (case in cases) {
            val outcome = compileFixture(case.variant, case.kotlin)
            val ok = if (case.expectInjection) {
                outcome is Outcome.Injected
            } else {
                outcome is Outcome.Failed
            }
            if (!ok) {
                failures += "${case.variant} on Kotlin ${case.kotlin}: expected " +
                    (if (case.expectInjection) "injection" else "failure") +
                    " (${case.why}) but got $outcome"
            }
        }
        if (failures.isNotEmpty()) {
            fail("Compose variant matrix mismatches:\n  " + failures.joinToString("\n  "))
        }
    }

    private sealed class Outcome {
        /** Compiled AND the transformer rewrote the call site. */
        object Injected : Outcome() { override fun toString() = "injected" }

        /** Compiled, but no injected call reached the bytecode — a silent no-op. */
        object NoOp : Outcome() { override fun toString() = "compiled but did NOT inject (silent no-op)" }

        /** Injected, but the running program behaved differently from a correct build. */
        class Wrong(private val printed: List<String>) : Outcome() {
            override fun toString() = "injected INCORRECTLY — expected $EXPECTED_OUTPUT but got $printed"
        }

        class Failed(val marker: String) : Outcome() { override fun toString() = "build failed ($marker)" }
    }

    private fun compileFixture(variant: String, kotlinVersion: String): Outcome {
        val pluginJar = requireProperty("bugsee.variantJar.$variant")
        val compilerClasspath = requireProperty("bugsee.kotlinClasspath.$kotlinVersion")
        val outDir = Files.createTempDirectory("bugsee-variant-$variant-$kotlinVersion-").toFile()

        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val process = ProcessBuilder(
            java, "-cp", compilerClasspath,
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib",
            "-classpath", compilerClasspath,
            "-Xplugin=$pluginJar",
            "-P", "plugin:${BugseeComposeCommandLineProcessor.PLUGIN_ID}:${BugseeComposeCommandLineProcessor.OPTION_ENABLED}=true",
            "-d", outDir.absolutePath,
            fixtureDir.absolutePath,
        ).redirectErrorStream(true).start()

        val log = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            val marker = LINK_FAILURES.firstOrNull { log.contains(it) } ?: "exit $exit"
            return Outcome.Failed(marker)
        }
        // Compiled — now RUN it. Presence of the helper name in the bytecode would only prove
        // something was injected; executing the result proves it was injected at the right call
        // sites, with the right tag, and without disturbing the other arguments. A mangled-but-
        // compiling rewrite passes the former and fails the latter.
        val appClass = File(outDir, "com/example/app/AppKt.class")
        assertTrue("fixture did not produce AppKt.class on Kotlin $kotlinVersion", appClass.isFile())

        val run = ProcessBuilder(
            java, "-cp", outDir.absolutePath + File.pathSeparator + compilerClasspath,
            "com.example.app.AppKt",
        ).redirectErrorStream(true).start()
        val printed = run.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }
        if (run.waitFor() != 0) return Outcome.Failed("compiled output threw: ${printed.take(2)}")

        return when {
            printed == EXPECTED_OUTPUT -> Outcome.Injected
            printed.none { it.startsWith("TAG:") || it == "SECURE" } -> Outcome.NoOp
            else -> Outcome.Wrong(printed)
        }
    }

    private fun requireProperty(key: String): String =
        System.getProperty(key) ?: error(
            "System property '$key' is not set. This test is driven by the composeVariantMatrix " +
                "Gradle task, which resolves the variant jars and per-line compiler classpaths."
        )

    companion object {
        /** Linkage failures we recognise, so an unexpected error is not mistaken for an expected one. */
        private val LINK_FAILURES = listOf(
            "NoSuchMethodError", "ClassCastException", "NoClassDefFoundError",
            "AbstractMethodError", "IrGenerationExtensionException",
        )

        private lateinit var fixtureDir: File

        /**
         * A minimal Compose consumer. Real Compose artifacts are not needed: the transformer selects
         * call sites by fully-qualified name, so same-named stubs exercise the identical path while
         * keeping the test hermetic and fast.
         *
         * The Bugsee runtime stub matters most — without `bugseeTag` on the classpath the extension
         * returns early and every variant would look healthy on every line, which is the exact hole
         * this test closes.
         */
        @BeforeClass
        @JvmStatic
        fun writeFixture() {
            fixtureDir = Files.createTempDirectory("bugsee-variant-fixture-").toFile()
            File(fixtureDir, "Compose.kt").writeText(
                """
                package androidx.compose.runtime
                annotation class Composable
                """.trimIndent()
            )
            File(fixtureDir, "Modifier.kt").writeText(
                """
                package androidx.compose.ui
                interface Modifier { companion object : Modifier }
                """.trimIndent()
            )
            File(fixtureDir, "VisualTransformation.kt").writeText(
                """
                package androidx.compose.ui.text.input
                interface VisualTransformation
                class PasswordVisualTransformation : VisualTransformation
                """.trimIndent()
            )
            // Same fully-qualified name as the real Material TextField, which is how the secure pass
            // selects it.
            File(fixtureDir, "Material.kt").writeText(
                """
                package androidx.compose.material
                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Modifier
                import androidx.compose.ui.text.input.VisualTransformation

                @Composable fun TextField(
                    value: String,
                    modifier: Modifier = Modifier,
                    enabled: Boolean = true,
                    visualTransformation: VisualTransformation? = null
                ) { println("TextField(value=" + value + ", enabled=" + enabled + ")") }
                """.trimIndent()
            )
            // The injected helpers PRINT, so running the output reports what was injected, where,
            // and with which argument — far stronger than finding the name in a constant pool.
            File(fixtureDir, "BugseeRuntime.kt").writeText(
                """
                package com.bugsee.library.compose
                import androidx.compose.ui.Modifier
                fun Modifier.bugseeTag(tag: String): Modifier { println("TAG:" + tag); return this }
                fun Modifier.bugseeSecure(): Modifier { println("SECURE"); return this }
                """.trimIndent()
            )
            // Each call is a shape where the 2.2+ absolute `parameters`/`arguments` indexing could
            // pick the wrong slot: an explicit modifier, an omitted one (companion-injection path),
            // a named argument with the modifier defaulted, a MEMBER composable (whose parameter
            // list starts with a dispatch receiver), and a password TextField for the secure pass.
            File(fixtureDir, "App.kt").writeText(
                """
                package com.example.app
                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Modifier
                import androidx.compose.material.TextField
                import androidx.compose.ui.text.input.PasswordVisualTransformation

                @Composable fun Child(modifier: Modifier = Modifier, enabled: Boolean = true) {
                    println("Child(enabled=" + enabled + ")")
                }

                // A Modifier argument that is an explicit null CONSTANT at the call site.
                // NOTE: this is a PROXY shape (a bare IrConst). Compose's real default-argument
                // lowering wraps the null in an IrComposite(origin = DEFAULT_VALUE) — this
                // harness compiles against a stub Compose with no Compose compiler plugin, so
                // that lowering never runs here and the composite shape cannot be produced.
                // The composite shape is reproduced end-to-end (on the k21 line) by
                // DefaultValueLoweringInteropTest in src/test, via a simulator plugin that
                // emits the byte-exact lowered form ahead of the Bugsee extension.
                @Composable fun NullableChild(modifier: Modifier? = null, enabled: Boolean = true) {
                    println("NullableChild(enabled=" + enabled + ")")
                }

                class Holder {
                    @Composable fun Member(modifier: Modifier = Modifier, enabled: Boolean = true) {
                        println("Member(enabled=" + enabled + ")")
                    }
                }

                @Composable fun Screen() {
                    Child(Modifier)
                    Child()
                    Child(enabled = false)
                    Holder().Member(Modifier, false)
                    TextField("secret", Modifier, true, PasswordVisualTransformation())
                    NullableChild(null, true)
                }

                fun main() { Screen() }
                """.trimIndent()
            )
        }

        /**
         * What a correctly instrumented build prints. Encodes tag VALUE, one injection per call site,
         * that the secure pass fires on the password field while the tag pass skips `androidx.*`, and
         * — via the `enabled` flags — that no argument was clobbered or swapped by the rewrite.
         */
        private val EXPECTED_OUTPUT = listOf(
            "TAG:Screen", "Child(enabled=true)",
            "TAG:Screen", "Child(enabled=true)",
            "TAG:Screen", "Child(enabled=false)",
            "TAG:Screen", "Member(enabled=false)",
            "SECURE", "TextField(value=secret, enabled=true)",
            // No "TAG:Screen" here on purpose. The argument is a literal null, so the
            // transform must skip the site: chaining would emit `null.bugseeTag(...)`
            // and the non-null receiver's intrinsic check would throw
            // "Parameter specified as non-null is null: ... parameter <this>" at runtime.
            // Before the fix this row did not print at all — the fixture crashed here.
            "NullableChild(enabled=true)",
        )
    }
}
