package com.bugsee.android.compose.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Kotlin 1.9 with the REAL Compose compiler plugin (issue #119).
 *
 * [ComposeVariantMatrixTest] cannot see this line's behaviour: its fixture uses stub Compose
 * artifacts, so no Compose lowering ever runs and every call site reaches the transform in its
 * source form. On Kotlin 1.9 the real androidx Compose compiler (1.5.x) runs, and two facts only
 * show up with it:
 *
 * 1. **The transform must not abort the build.** The default-value check read
 *    `IrStatementOrigin.DEFAULT_VALUE`, which on a 1.9 compiler is not reachable through the
 *    `IrStatementOrigin.Companion` field the k21 jar was compiled against, so the first call site
 *    Compose had lowered threw `NoSuchFieldError` and aborted the consumer's compilation.
 *    The check is now by origin name.
 *
 * 2. **Only explicit-`modifier` call sites are tagged.** Compose 1.5.x registers through the legacy
 *    `ComponentRegistrar` service, which this compiler runs AHEAD of any `CompilerPluginRegistrar`
 *    such as Bugsee's, whatever the `-Xplugin` order. Compose's default-argument lowering has
 *    therefore already rewritten every omitted `modifier` into a `DEFAULT_VALUE` composite by the
 *    time Bugsee sees it, and the transform skips those on purpose (the callee's `$default` fixup
 *    overwrites the slot, so a tag injected there would be dead code). From Kotlin 2.0 the Compose
 *    plugin is registered the modern way and Bugsee's relative position decides. The result is a
 *    deliberate, documented limitation, pinned here: a composable that omits `modifier` is not
 *    tagged on Kotlin 1.9.
 */
class Kotlin19ComposePipelineTest {

    @Test
    fun `Kotlin 1_9 compiles with the real Compose plugin and tags only explicit-modifier sites`() {
        // Both -Xplugin orders: the result must not depend on it, which is the point of fact 2.
        for (bugseeFirst in listOf(true, false)) {
            val order = if (bugseeFirst) "bugsee-first" else "compose-first"
            assertEquals(
                "Kotlin $KOTLIN ($order): exactly the explicit-modifier call site is tagged " +
                    "(ProdExplicit); the omitted-modifier sites (ProdDefault) are Compose " +
                    "DEFAULT_VALUE composites by the time Bugsee runs and are skipped.",
                mapOf("ProdDefault" to 0, "ProdExplicit" to 1),
                compileAndCountTags(bugseeFirst),
            )
        }
    }

    /** `bugseeTag` call count per top-level composable of the fixture, from the emitted bytecode. */
    private fun compileAndCountTags(bugseeFirst: Boolean): Map<String, Int> {
        val bugseeJar = requireProperty("bugsee.variantJar.k21")
        val composeCompilerJar = requireProperty("bugsee.compose19.compilerPlugin")
        val composeRuntimeJar = requireProperty("bugsee.compose19.runtime")
        val compilerClasspath = requireProperty("bugsee.kotlinClasspath.$KOTLIN")
        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val work = Files.createTempDirectory("bugsee-k19-compose-").toFile()

        // Compiled as its own module: a `bugseeTag` declared in the module being compiled is a
        // source declaration, not the library function a consumer resolves, and codegen rejects
        // calling it ("Unhandled intrinsic").
        val libSrc = File(work, "lib-src").apply { mkdirs() }
        File(libSrc, "Modifier.kt").writeText(
            """
            package androidx.compose.ui
            interface Modifier { companion object : Modifier }
            """.trimIndent()
        )
        File(libSrc, "BugseeRuntime.kt").writeText(
            """
            package com.bugsee.library.compose
            import androidx.compose.ui.Modifier
            fun Modifier.bugseeTag(tag: String): Modifier = this
            """.trimIndent()
        )
        val libOut = File(work, "lib-out").apply { mkdirs() }
        compile(java, compilerClasspath, listOf(), libSrc, libOut, extraClasspath = emptyList())

        val appSrc = File(work, "app-src").apply { mkdirs() }
        File(appSrc, "Prod.kt").writeText(
            """
            package com.example.app
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier

            @Composable fun Child(modifier: Modifier = Modifier, enabled: Boolean = true) {}
            @Composable fun ProdDefault() { Child(); Child(enabled = false) }
            @Composable fun ProdExplicit() { Child(Modifier) }
            """.trimIndent()
        )
        val appOut = File(work, "app-out").apply { mkdirs() }
        val plugins = if (bugseeFirst) listOf(bugseeJar, composeCompilerJar) else listOf(composeCompilerJar, bugseeJar)
        compile(
            java, compilerClasspath,
            plugins.map { "-Xplugin=$it" } + listOf(
                "-P", "plugin:androidx.compose.compiler.plugins.kotlin:suppressKotlinVersionCompatibilityCheck=true",
                "-P", "plugin:${BugseeComposeCommandLineProcessor.PLUGIN_ID}:${BugseeComposeCommandLineProcessor.OPTION_ENABLED}=true",
            ),
            appSrc, appOut,
            extraClasspath = listOf(composeRuntimeJar, libOut.absolutePath),
        )

        val javap = File(File(System.getProperty("java.home"), "bin"), "javap").absolutePath
        val dump = ProcessBuilder(javap, "-c", "-p", File(appOut, "com/example/app/ProdKt.class").absolutePath)
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        val counts = linkedMapOf("ProdDefault" to 0, "ProdExplicit" to 0)
        var current: String? = null
        for (line in dump.lines()) {
            val declared = METHOD_HEADER.find(line)
            if (declared != null) current = declared.groupValues[1]
            if (current in counts && line.contains("invokestatic") && line.contains(".bugseeTag:")) {
                counts[current!!] = counts.getValue(current) + 1
            }
        }
        return counts
    }

    private fun compile(
        java: String,
        compilerClasspath: String,
        pluginArgs: List<String>,
        source: File,
        out: File,
        extraClasspath: List<String>,
    ) {
        val classpath = (listOf(compilerClasspath) + extraClasspath).joinToString(File.pathSeparator)
        val process = ProcessBuilder(
            listOf(
                java, "-cp", compilerClasspath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib", "-nowarn",
                "-classpath", classpath,
            ) + pluginArgs + listOf("-d", out.absolutePath, source.absolutePath)
        ).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        assertTrue(
            "Kotlin $KOTLIN compilation failed (exit $exit). A NoSuchFieldError / " +
                "IrStatementOrigin here is the #119 abort:\n${log.take(4000)}",
            exit == 0,
        )
    }

    private fun requireProperty(key: String): String =
        System.getProperty(key) ?: error(
            "System property '$key' is not set. This test is driven by the composeVariantMatrix " +
                "Gradle task, which resolves the variant jars, the Compose 1.5 plugin and runtime, " +
                "and the per-line compiler classpaths."
        )

    private companion object {
        /** The release the matching Compose compiler 1.5.15 is built for. */
        const val KOTLIN = "1.9.25"

        /** A javap method header, e.g. `  public static final void ProdDefault(...);`. */
        val METHOD_HEADER = Regex("""^\s{2}(?:\w+\s+)*?(\w+)\(.*\)\s*(?:throws .*)?;$""")
    }
}
