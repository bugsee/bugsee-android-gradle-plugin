package com.bugsee.android.compose.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End-to-end proof that [BugseeComposeIrExtension] survives Compose's
 * default-argument lowering having run FIRST — the production ordering that
 * crashed hosts on plugin 4.0.5 with
 * `NullPointerException: Parameter specified as non-null is null: ... bugseeTag, parameter <this>`.
 *
 * Mechanism: a real `K2JVMCompiler` subprocess compiles a Compose-stub fixture
 * with TWO plugins, in order:
 *
 *  1. [ComposeDefaultLoweringSimulatorRegistrar] — fills every omitted
 *     `Modifier` argument with the byte-exact `IrComposite(DEFAULT_VALUE,
 *     [null-const])` shape Compose's `ComposerParamTransformer` produces
 *     (verified by decompiling the 2.1.0/2.2.10/2.3.0/2.4.0 compose-compiler
 *     artifacts).
 *  2. The real Bugsee compose plugin (this module's `main` output = the
 *     legacyIr/k21 variant).
 *
 * The compiled fixture is then EXECUTED. The three possible outcomes
 * discriminate cleanly:
 *
 *  - **runtime NPE "parameter <this>"** — the transform chained onto the
 *    composite: the pre-fix guard (bare `IrConst` check) reproduces the 4.0.5
 *    customer crash here.
 *  - **"TAG:" printed for the omitted-modifier site** — either Bugsee ran
 *    before the simulator (companion injection on an absent argument) or the
 *    simulator failed to load; the run proves nothing and MUST fail.
 *  - **omitted-modifier site prints untagged, explicit-modifier site prints
 *    tagged** — the guard skipped the defaulted slot and only that slot. This
 *    is the asserted outcome.
 *
 * **Scope of the proof.** This runs the k21 (legacyIr) build against Kotlin
 * 2.1.0 only — the test source set compiles against that compiler line. The
 * modernIr variant carries the identical guard logic; its behaviour on 2.2+ is
 * covered by compilation in the variant matrix plus the decompilation evidence
 * that all four compose-compiler versions emit the same shape, NOT by an
 * executed repro on those lines (the matrix harness has no Compose compiler
 * and cannot perform real default lowering).
 */
class DefaultValueLoweringInteropTest {

    @Test
    fun `guard skips the Compose default-lowering composite instead of chaining onto null`() {
        val run = compileAndRun(ComposeDefaultLoweringSimulatorExtension.MODE_DEFAULT_VALUE)

        assertEquals(
            "compiled fixture crashed — with a 'Parameter specified as non-null is null ... " +
                "parameter <this>' NPE this is the 4.0.5 crash: the transform chained " +
                "bugseeTag onto Compose's DEFAULT_VALUE composite instead of skipping it. " +
                "Output: ${run.printed}",
            0, run.exit,
        )
        assertEquals(
            "unexpected program output — a TAG: line for the omitted-modifier sites means " +
                "either the Bugsee extension ran BEFORE the simulator (repro order lost) or " +
                "the guard injected into a defaulted slot; a missing TAG: for the " +
                "explicit-modifier site means the new guard is over-broad",
            listOf(
                // Child(Modifier): explicit modifier — must still be tagged.
                "TAG:Screen", "Child(enabled=true)",
                // Child(): modifier omitted; simulator fills the DEFAULT_VALUE composite;
                // the guard must skip the site — untagged, but alive.
                "Child(enabled=true)",
                // Child(enabled = false): same, via named-argument shape.
                "Child(enabled=false)",
            ),
            run.printed,
        )
    }

    /**
     * Pins the guard's ORIGIN-AGNOSTIC composite-of-null fallback — the second
     * half of the `IrComposite` branch, which the production shape can never
     * reach because `origin == DEFAULT_VALUE` always matches first.
     *
     * The simulator emits `IrComposite(origin = null, [null-const])` here: the
     * same runtime value (`null`) in the same slot, minus the origin marker.
     * The comment on that fallback calls it insurance against a future compiler
     * dropping or renaming `DEFAULT_VALUE`; this executes that scenario. With
     * the fallback deleted the transform chains `bugseeTag` onto a runtime
     * `null` and the fixture dies with the exact 4.0.5 NPE — so the assertion
     * that discriminates is the process exit code plus the untagged output.
     */
    @Test
    fun `guard also skips an origin-less composite wrapping null`() {
        val run = compileAndRun(ComposeDefaultLoweringSimulatorExtension.MODE_ORIGINLESS)

        assertEquals(
            "compiled fixture crashed — the guard's origin-agnostic composite-of-null " +
                "fallback is what keeps a DEFAULT_VALUE-less composite from being chained " +
                "onto; without it this is the 4.0.5 'parameter <this>' NPE. Output: ${run.printed}",
            0, run.exit,
        )
        assertEquals(
            "a composite wrapping a null constant must be skipped even without the " +
                "DEFAULT_VALUE origin",
            listOf(
                "TAG:Screen", "Child(enabled=true)",
                "Child(enabled=true)",
                "Child(enabled=false)",
            ),
            run.printed,
        )
    }

    /**
     * Pins that the guard is NARROW — the opposite failure from the one the fix
     * addressed, and the one nothing else covers: every `Modifier` argument in
     * both fixtures is absent, a bare `null`, or the bare companion, so a guard
     * widened to `if (this is IrComposite) return true` passes the whole suite
     * while silently dropping tags in any build whose modifier expression
     * happens to lower to a composite.
     *
     * This run puts BOTH shapes in one compilation: omitted slots get the real
     * `IrComposite(DEFAULT_VALUE, [null])` (must be skipped) while the explicit
     * `Child(Modifier)` argument is wrapped in `IrComposite(origin = null,
     * [Modifier])` — a composite over a REAL expression, which must still be
     * chained onto and tagged.
     */
    @Test
    fun `guard still tags a composite wrapping a real modifier expression`() {
        val run = compileAndRun(ComposeDefaultLoweringSimulatorExtension.MODE_WRAP_EXPLICIT)

        assertEquals(
            "compiled fixture crashed. Output: ${run.printed}", 0, run.exit,
        )
        assertEquals(
            "the explicit-modifier site is wrapped in an origin-less composite over a REAL " +
                "expression — not a defaulted slot — so it must still be tagged; losing its " +
                "TAG: line means the guard treats any IrComposite as defaulted and silently " +
                "drops tags",
            listOf(
                // Child(IrComposite(null, [Modifier])): a real modifier, still tagged.
                "TAG:Screen", "Child(enabled=true)",
                // The two omitted slots still carry the DEFAULT_VALUE shape — still skipped.
                "Child(enabled=true)",
                "Child(enabled=false)",
            ),
            run.printed,
        )
    }

    private class RunResult(val exit: Int, val printed: List<String>)

    /**
     * Compiles the fixture with the simulator plugin (in [mode]) ahead of the
     * real Bugsee compose plugin, then executes the result.
     *
     * Plugin ORDER is load-bearing: the simulator must lower defaults before
     * the Bugsee extension runs, mirroring a build where Compose's own
     * lowering is ahead of us in the extension list.
     */
    private fun compileAndRun(mode: String): RunResult {
        val work = Files.createTempDirectory("bugsee-default-lowering-").toFile()
        val fixtureDir = File(work, "src").apply { mkdirs() }
        writeFixture(fixtureDir)

        val simulatorJar = jarOfClassTree(
            File(work, "simulator.jar"),
            classesDirOf(ComposeDefaultLoweringSimulatorRegistrar::class.java),
            registrarService = ComposeDefaultLoweringSimulatorRegistrar::class.java.name,
        )
        val bugseeJar = jarOfClassTree(
            File(work, "bugsee-plugin.jar"),
            classesDirOf(BugseeComposeCompilerPluginRegistrar::class.java),
            registrarService = BugseeComposeCompilerPluginRegistrar::class.java.name,
            commandLineProcessorService = BugseeComposeCommandLineProcessor::class.java.name,
        )

        val classpath = System.getProperty("java.class.path")
        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val outDir = File(work, "out").apply { mkdirs() }

        val compile = ProcessBuilder(
            java,
            "-D${ComposeDefaultLoweringSimulatorExtension.MODE_PROPERTY}=$mode",
            "-cp", classpath,
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib",
            "-classpath", classpath,
            "-Xplugin=${simulatorJar.absolutePath}",
            "-Xplugin=${bugseeJar.absolutePath}",
            "-P", "plugin:${BugseeComposeCommandLineProcessor.PLUGIN_ID}:${BugseeComposeCommandLineProcessor.OPTION_ENABLED}=true",
            "-d", outDir.absolutePath,
            fixtureDir.absolutePath,
        ).redirectErrorStream(true).start()
        val compileLog = compile.inputStream.bufferedReader().readText()
        assertEquals("fixture compilation failed (mode=$mode):\n$compileLog", 0, compile.waitFor())

        val run = ProcessBuilder(
            java, "-cp", outDir.absolutePath + File.pathSeparator + classpath,
            "com.example.app.AppKt",
        ).redirectErrorStream(true).start()
        val printed = run.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }
        return RunResult(run.waitFor(), printed)
    }

    /**
     * A minimal Compose-stub consumer. `Modifier` is nullable in `Child` so
     * that the composite's runtime `null` is legal for the CALLEE — in a real
     * Compose build the `$default` mask replaces it; the stub fixture has no
     * such mask, and the point of the test is the injected `bugseeTag`
     * RECEIVER, which is non-null in both worlds.
     */
    private fun writeFixture(dir: File) {
        File(dir, "Composable.kt").writeText(
            """
            package androidx.compose.runtime
            @Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
            annotation class Composable
            """.trimIndent()
        )
        File(dir, "Modifier.kt").writeText(
            """
            package androidx.compose.ui
            interface Modifier { companion object : Modifier }
            """.trimIndent()
        )
        File(dir, "Bugsee.kt").writeText(
            """
            package com.bugsee.library.compose
            import androidx.compose.ui.Modifier
            fun Modifier.bugseeTag(tag: String): Modifier { println("TAG:" + tag); return this }
            fun Modifier.bugseeSecure(): Modifier { println("SECURE"); return this }
            """.trimIndent()
        )
        File(dir, "App.kt").writeText(
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

    /** The classes directory (Gradle: `build/classes/kotlin/<sourceSet>`) that defines [cls]. */
    private fun classesDirOf(cls: Class<*>): File {
        val location = cls.protectionDomain.codeSource?.location
            ?: error("no code source for ${cls.name}")
        val dir = File(location.toURI())
        assertTrue("expected a classes directory for ${cls.name}, got $dir", dir.isDirectory)
        return dir
    }

    /**
     * Packages [classesDir] into a compiler-plugin jar at [target], adding the
     * `META-INF/services` entries the Kotlin driver's service loader needs
     * (they live in a separate resources dir in a Gradle build, so the classes
     * tree alone is not loadable as a plugin).
     */
    private fun jarOfClassTree(
        target: File,
        classesDir: File,
        registrarService: String,
        commandLineProcessorService: String? = null,
    ): File {
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            classesDir.walkTopDown().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(classesDir).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(
                ZipEntry("META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar")
            )
            zip.write(registrarService.toByteArray())
            zip.closeEntry()
            if (commandLineProcessorService != null) {
                zip.putNextEntry(
                    ZipEntry("META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor")
                )
                zip.write(commandLineProcessorService.toByteArray())
                zip.closeEntry()
            }
        }
        return target
    }
}
