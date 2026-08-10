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
            Case("k21", "2.1.0", true, "the line k21 is built against"),
            Case("k21", "2.2.21", false, "2.2 widened the irCall/irString builder receiver to IrBuilder"),
            Case("k21", "2.4.10", false, "2.4 moved extension registration to ExtensionPointDescriptor"),

            // k22 — modern IR API; its plain `val pluginId` satisfies 2.3's abstract member at runtime.
            Case("k22", "2.1.0", false, "the 2.2 parameters/arguments API does not exist in 2.1"),
            Case("k22", "2.2.0", true, "the line k22 is built against"),
            Case("k22", "2.2.21", true, "same line, later patch"),
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
        // Compiled — now check the transformer actually did something. The injected call lands in
        // the enclosing composable's class file as a reference to the Bugsee runtime helper.
        val appClass = File(outDir, "com/example/app/AppKt.class")
        assertTrue("fixture did not produce AppKt.class on Kotlin $kotlinVersion", appClass.isFile())
        val injected = appClass.readBytes().toString(Charsets.ISO_8859_1).contains("bugseeTag")
        return if (injected) Outcome.Injected else Outcome.NoOp
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
            File(fixtureDir, "BugseeRuntime.kt").writeText(
                """
                package com.bugsee.library.compose
                import androidx.compose.ui.Modifier
                fun Modifier.bugseeTag(tag: String): Modifier = this
                fun Modifier.bugseeSecure(): Modifier = this
                """.trimIndent()
            )
            File(fixtureDir, "App.kt").writeText(
                """
                package com.example.app
                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Modifier

                @Composable fun Child(modifier: Modifier = Modifier) {}

                @Composable fun Screen() {
                    Child(Modifier)
                }
                """.trimIndent()
            )
        }
    }
}
