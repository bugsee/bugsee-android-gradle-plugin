package com.bugsee.android.compose.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * The `k21` artifact registers through BOTH compiler entry points (issue #138): the modern
 * `CompilerPluginRegistrar` and the legacy `ComponentRegistrar`. What this pins:
 *
 *  - **Packaging.** Only the `k21` jar carries the legacy service file. `k22`/`k24` never run on a
 *    Kotlin 1.9 compiler, and a legacy registrar class they do not ship must not be named in them.
 *  - **Exactly one registration per compiler line.** Every line `k21` serves loads both entry points;
 *    each must leave the IR extension to one of them or call sites are tagged twice. That is pinned by
 *    [ComposeVariantMatrixTest] (its fixture prints one `TAG:` per injected call and the whole output is
 *    compared) and, with the real Compose compiler on Kotlin 1.9, by [Kotlin19ComposePipelineTest].
 *  - **No new compiler noise.** A deprecation or "legacy plugin" warning from the extra registrar
 *    would land in every consumer's build log.
 */
class LegacyRegistrationTest {

    @Test
    fun `only the k21 jar carries the legacy ComponentRegistrar service`() {
        assertEquals(
            listOf("com.bugsee.android.compose.compiler.BugseeComposeLegacyComponentRegistrar"),
            serviceEntries(requireProperty("bugsee.variantJar.k21"), LEGACY_SERVICE),
        )
        for (variant in listOf("k22", "k24")) {
            ZipFile(requireProperty("bugsee.variantJar.$variant")).use { zip ->
                assertNull("$variant must not ship the legacy service file", zip.getEntry(LEGACY_SERVICE))
                assertNull(
                    "$variant must not ship the legacy registrar class",
                    zip.getEntry("com/bugsee/android/compose/compiler/BugseeComposeLegacyComponentRegistrar.class"),
                )
            }
        }
    }

    @Test
    fun `the k21 jar contains both registrar classes`() {
        ZipFile(requireProperty("bugsee.variantJar.k21")).use { zip ->
            assertNotNull(
                zip.getEntry("com/bugsee/android/compose/compiler/BugseeComposeLegacyComponentRegistrar.class"),
            )
            assertNotNull(zip.getEntry("META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"))
        }
    }

    @Test
    fun `the k21 jar prints no compiler warning on any Kotlin line it serves`() {
        for (kotlin in K21_LINES) {
            val log = compileOnce(kotlin)
            assertFalse(
                "Kotlin $kotlin: the k21 jar produced compiler warnings:\n$log",
                log.lines().any { it.contains("warning:", ignoreCase = true) },
            )
        }
    }

    private fun compileOnce(kotlin: String): String {
        val work = Files.createTempDirectory("bugsee-legacy-registration-").toFile()
        try {
            val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
            val compilerClasspath = requireProperty("bugsee.kotlinClasspath.$kotlin")
            val src = File(work, "src").apply { mkdirs() }
            File(src, "A.kt").writeText("package a\nfun f(x: Int): Int = x + 1\n")
            val out = File(work, "out").apply { mkdirs() }
            val process = ProcessBuilder(
                java, "-cp", compilerClasspath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib", "-classpath", compilerClasspath,
                "-Xplugin=${requireProperty("bugsee.variantJar.k21")}",
                "-d", out.absolutePath, src.absolutePath,
            ).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            assertEquals("Kotlin $kotlin compilation failed:\n$log", 0, process.waitFor())
            return log
        } finally {
            work.deleteRecursively()
        }
    }

    private fun serviceEntries(jar: String, service: String): List<String> =
        ZipFile(jar).use { zip ->
            val entry = zip.getEntry(service) ?: return emptyList()
            zip.getInputStream(entry).bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }

    private fun requireProperty(key: String): String =
        System.getProperty(key) ?: error("System property '$key' is not set; run through composeVariantMatrix.")

    private companion object {
        const val LEGACY_SERVICE = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar"

        /** The Kotlin versions the `k21` artifact serves that the matrix resolves compilers for. */
        val K21_LINES = listOf("1.9.22", "1.9.25", "2.0.21", "2.1.0", "2.1.21")
    }
}
