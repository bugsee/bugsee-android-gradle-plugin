package com.bugsee.android.compose.compiler

import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The [DefaultValueLoweringInteropTest] repro, EXECUTED on the modern Kotlin
 * lines.
 *
 * ### Why this exists
 *
 * `BugseeComposeIrExtension` is duplicated into a `legacyIr` and a `modernIr`
 * source set, and the two carry the *same* defaulted-argument guard written
 * against different IR APIs. `DefaultValueLoweringInteropTest` proves that
 * guard against Compose's real `IrComposite(origin = DEFAULT_VALUE, [null])`
 * shape — but it compiles the module's `main` output, which IS the k21 /
 * legacyIr build. So the guard that every Kotlin 2.2–2.4 consumer actually
 * runs had no executed repro at all: deleting its whole `IrComposite` branch
 * (the pre-fix, 4.0.5-crashing state) left the entire suite green, because the
 * only modernIr coverage was the matrix fixture's `NullableChild(null, true)`
 * row — a bare `IrConst`, which the first, still-broken fix already handled.
 *
 * This suite closes that hole: the `k22` and `k24` plugin jars are run through
 * their own Kotlin lines with a per-line build of the default-lowering
 * simulator loaded AHEAD of them, and the compiled fixture is EXECUTED. A
 * chained-onto-null guard shows up as the customer's
 * `NullPointerException: Parameter specified as non-null is null: …
 * bugseeTag, parameter <this>`, i.e. a non-zero exit.
 *
 * Each variant is exercised in three simulator modes, mirroring
 * [DefaultValueLoweringInteropTest]:
 *  - `defaultValue` — the production shape; omitted slots must be skipped.
 *  - `originless` — composite-of-null without the origin; pins the fallback.
 *  - `wrapExplicit` — a composite over a REAL modifier expression; pins that
 *    the guard is narrow and still tags that site.
 */
class ModernIrDefaultLoweringInteropTest {

    @Test
    fun `k22 skips the Compose default-lowering composite on Kotlin 2_2`() {
        assertPipeline("k22", "2.2.0", MODE_DEFAULT_VALUE, DEFAULTED_SITES_SKIPPED)
    }

    @Test
    fun `k22 skips an origin-less composite wrapping null on Kotlin 2_2`() {
        assertPipeline("k22", "2.2.0", MODE_ORIGINLESS, DEFAULTED_SITES_SKIPPED)
    }

    @Test
    fun `k22 still tags a composite wrapping a real modifier expression on Kotlin 2_2`() {
        assertPipeline("k22", "2.2.0", MODE_WRAP_EXPLICIT, DEFAULTED_SITES_SKIPPED)
    }

    @Test
    fun `k24 skips the Compose default-lowering composite on Kotlin 2_4`() {
        assertPipeline("k24", "2.4.0", MODE_DEFAULT_VALUE, DEFAULTED_SITES_SKIPPED)
    }

    @Test
    fun `k24 skips an origin-less composite wrapping null on Kotlin 2_4`() {
        assertPipeline("k24", "2.4.0", MODE_ORIGINLESS, DEFAULTED_SITES_SKIPPED)
    }

    @Test
    fun `k24 still tags a composite wrapping a real modifier expression on Kotlin 2_4`() {
        assertPipeline("k24", "2.4.0", MODE_WRAP_EXPLICIT, DEFAULTED_SITES_SKIPPED)
    }

    private fun assertPipeline(
        variant: String,
        kotlinVersion: String,
        mode: String,
        expected: List<String>,
    ) {
        val result = compileAndRun(variant, kotlinVersion, mode)
        assertEquals(
            "compiled fixture crashed ($variant on Kotlin $kotlinVersion, simulator mode=$mode). " +
                "A 'Parameter specified as non-null is null: … bugseeTag, parameter <this>' NPE " +
                "means the modernIr guard chained onto a slot that is null at runtime — the 4.0.5 " +
                "host-app crash. Output: ${result.printed}",
            0, result.exit,
        )
        assertEquals(
            "unexpected output for $variant on Kotlin $kotlinVersion (simulator mode=$mode). " +
                "A TAG: line for an omitted-modifier site means the guard failed to recognise " +
                "the defaulted slot; a MISSING TAG: for the explicit-modifier site means the " +
                "guard is over-broad and silently dropped a tag.",
            expected, result.printed,
        )
    }

    private class RunResult(val exit: Int, val printed: List<String>)

    /**
     * Compiles [FIXTURE] with the per-line simulator plugin loaded BEFORE the
     * [variant] Bugsee plugin jar, using the real compiler of [kotlinVersion],
     * then executes the result.
     *
     * Plugin order is load-bearing: the simulator stands in for Compose's own
     * lowering, which in production runs ahead of this transform.
     */
    private fun compileAndRun(variant: String, kotlinVersion: String, mode: String): RunResult {
        val pluginJar = requireProperty("bugsee.variantJar.$variant")
        val simulatorJar = requireProperty("bugsee.simulatorJar.$variant")
        val compilerClasspath = requireProperty("bugsee.kotlinClasspath.$kotlinVersion")
        val outDir = Files.createTempDirectory("bugsee-lowering-$variant-$kotlinVersion-").toFile()

        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val compile = ProcessBuilder(
            java,
            "-D$MODE_PROPERTY=$mode",
            "-cp", compilerClasspath,
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib",
            "-classpath", compilerClasspath,
            "-Xplugin=$simulatorJar",
            "-Xplugin=$pluginJar",
            "-P", "plugin:${BugseeComposeCommandLineProcessor.PLUGIN_ID}:${BugseeComposeCommandLineProcessor.OPTION_ENABLED}=true",
            "-d", outDir.absolutePath,
            fixtureDir.absolutePath,
        ).redirectErrorStream(true).start()
        val compileLog = compile.inputStream.bufferedReader().readText()
        assertEquals(
            "fixture compilation failed ($variant on Kotlin $kotlinVersion, mode=$mode):\n$compileLog",
            0, compile.waitFor(),
        )

        val run = ProcessBuilder(
            java, "-cp", outDir.absolutePath + File.pathSeparator + compilerClasspath,
            "com.example.app.AppKt",
        ).redirectErrorStream(true).start()
        val printed = run.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }
        return RunResult(run.waitFor(), printed)
    }

    private fun requireProperty(key: String): String =
        System.getProperty(key) ?: error(
            "System property '$key' is not set. This test is driven by the composeVariantMatrix " +
                "Gradle task, which resolves the variant jars, the per-line default-lowering " +
                "simulator jars and the per-line compiler classpaths."
        )

    companion object {
        /** Read by the simulator plugin inside the compiler process. */
        private const val MODE_PROPERTY = "bugsee.simulator.mode"
        private const val MODE_DEFAULT_VALUE = "defaultValue"
        private const val MODE_ORIGINLESS = "originless"
        private const val MODE_WRAP_EXPLICIT = "wrapExplicit"

        /**
         * What a correct build prints for [FIXTURE] in every simulator mode: the
         * explicit-modifier site is tagged, the two defaulted sites are not — and
         * all three still run.
         *
         * In `wrapExplicit` mode the explicit site's argument is additionally
         * wrapped in an origin-less composite over the real `Modifier` expression,
         * so the identical expectation there is what pins the guard as NARROW:
         * treating any `IrComposite` as defaulted drops that first `TAG:Screen`.
         */
        private val DEFAULTED_SITES_SKIPPED = listOf(
            "TAG:Screen", "Child(enabled=true)",
            "Child(enabled=true)",
            "Child(enabled=false)",
        )

        private lateinit var fixtureDir: File

        /**
         * The same minimal Compose-stub consumer [DefaultValueLoweringInteropTest]
         * uses. `Modifier` is nullable in `Child` so the composite's runtime `null`
         * is legal for the CALLEE — in a real Compose build the `$default` mask
         * replaces it; the stub has no such mask, and the subject of the test is
         * the injected `bugseeTag` RECEIVER, which is non-null in both worlds.
         */
        @BeforeClass
        @JvmStatic
        fun writeFixture() {
            fixtureDir = Files.createTempDirectory("bugsee-lowering-fixture-").toFile()
            File(fixtureDir, "Composable.kt").writeText(
                """
                package androidx.compose.runtime
                @Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
                annotation class Composable
                """.trimIndent()
            )
            File(fixtureDir, "Modifier.kt").writeText(
                """
                package androidx.compose.ui
                interface Modifier { companion object : Modifier }
                """.trimIndent()
            )
            File(fixtureDir, "Bugsee.kt").writeText(
                """
                package com.bugsee.library.compose
                import androidx.compose.ui.Modifier
                fun Modifier.bugseeTag(tag: String): Modifier { println("TAG:" + tag); return this }
                fun Modifier.bugseeSecure(): Modifier { println("SECURE"); return this }
                """.trimIndent()
            )
            File(fixtureDir, FIXTURE).writeText(
                """
                package com.example.app
                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Modifier

                @Composable fun Child(modifier: Modifier? = null, enabled: Boolean = true) {
                    println("Child(enabled=" + enabled + ")")
                }

                @Composable fun Screen() {
                    Child(Modifier)
                    Child()
                    Child(enabled = false)
                }

                fun main() { Screen() }
                """.trimIndent()
            )
        }

        private const val FIXTURE = "App.kt"
    }
}
