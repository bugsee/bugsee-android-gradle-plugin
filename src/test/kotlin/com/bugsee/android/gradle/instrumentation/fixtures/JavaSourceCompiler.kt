package com.bugsee.android.gradle.instrumentation.fixtures

import java.net.URI
import java.nio.file.Files
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider

/**
 * Compiles inline Java source strings to class bytes using the JDK's
 * built-in {@code javax.tools.JavaCompiler}.
 *
 * Returns a {@code Map<String, ByteArray>} keyed by fully-qualified class
 * name (e.g. {@code "com.example.MyApp"}). Multiple top-level / nested /
 * synthetic classes from a single source unit are all included.
 *
 * Requires running on a JDK (not a JRE) — {@link ToolProvider#getSystemJavaCompiler}
 * returns null on JREs. The Gradle Java toolchain guarantees a JDK at
 * test time.
 */
internal object JavaSourceCompiler {

    /**
     * @param fileName e.g. {@code "MyApp.java"} — must match the public
     * top-level class in [source]
     * @param source the full Java source
     * @return map of FQN → class bytes
     */
    fun compile(fileName: String, source: String): Map<String, ByteArray> {
        return compileAll(mapOf(fileName to source))
    }

    /**
     * Compile multiple source files in one invocation so they can refer to
     * each other.
     */
    fun compileAll(sources: Map<String, String>): Map<String, ByteArray> {
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error(
                "No JavaCompiler available. The test runtime must be a JDK " +
                        "(not a JRE) — verify the Gradle toolchain is a JDK install."
            )

        val sourceObjects = sources.map { (name, src) ->
            JavaSource(name, src)
        }

        val outputDir = Files.createTempDirectory("bugsee-asm-harness-").toFile()
        try {
            val fileManager = compiler.getStandardFileManager(null, null, Charsets.UTF_8)
            fileManager.use {
                it.setLocation(
                    StandardLocation.CLASS_OUTPUT,
                    listOf(outputDir),
                )

                val diagnostics = javax.tools.DiagnosticCollector<JavaFileObject>()
                val task = compiler.getTask(
                    null, // writer; null → System.err
                    it,
                    diagnostics,
                    listOf("-g", "-source", "11", "-target", "11"),
                    null,
                    sourceObjects,
                )
                val ok = task.call()
                if (ok != true) {
                    val errors = diagnostics.diagnostics.joinToString("\n") { d -> d.toString() }
                    error("Java compilation failed:\n$errors")
                }
            }

            val collected = mutableMapOf<String, ByteArray>()
            outputDir.walk()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { classFile ->
                    val relative = classFile.relativeTo(outputDir).path
                    val fqn = relative
                        .removeSuffix(".class")
                        .replace(java.io.File.separatorChar, '.')
                    collected[fqn] = classFile.readBytes()
                }
            return collected
        } finally {
            // Always clean up — compile failure must not leak the temp
            // dir (deleteOnExit() does not recurse into non-empty dirs).
            outputDir.deleteRecursively()
        }
    }

    /** In-memory {@link SimpleJavaFileObject} backed by a source string. */
    private class JavaSource(
        fileName: String,
        private val source: String,
    ) : SimpleJavaFileObject(
        URI.create("string:///$fileName"),
        JavaFileObject.Kind.SOURCE,
    ) {
        override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
    }

}
