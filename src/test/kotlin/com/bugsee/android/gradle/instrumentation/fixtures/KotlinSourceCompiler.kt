package com.bugsee.android.gradle.instrumentation.fixtures

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.nio.file.Files

/**
 * Compiles inline Kotlin source strings to class bytes using the embedded
 * Kotlin compiler.
 *
 * Like [JavaSourceCompiler], returns a map of fully-qualified class names
 * to bytes — Kotlin top-level functions land in synthetic `*Kt` classes,
 * companion objects in nested `$Companion` classes, etc. Whatever the
 * compiler emits is collected.
 *
 * The compiler is invoked via [K2JVMCompiler.exec] against a temp source
 * dir + temp output dir. The cost is significant (~1s per invocation),
 * so tests should compile multiple snippets per call when possible.
 */
internal object KotlinSourceCompiler {

    /**
     * @param fileName e.g. {@code "MyApp.kt"}
     * @param source the full Kotlin source
     */
    fun compile(fileName: String, source: String): Map<String, ByteArray> {
        return compileAll(mapOf(fileName to source))
    }

    fun compileAll(sources: Map<String, String>): Map<String, ByteArray> {
        val sourceDir = Files.createTempDirectory("bugsee-asm-harness-kt-src-").toFile()
        val outputDir = Files.createTempDirectory("bugsee-asm-harness-kt-out-").toFile()
        try {
            sources.forEach { (name, src) ->
                File(sourceDir, name).writeText(src)
            }

            val args = K2JVMCompilerArguments().apply {
                freeArgs = listOf(sourceDir.absolutePath)
                destination = outputDir.absolutePath
                noStdlib = false
                jvmTarget = "11"
                // Skip Kotlin runtime classpath classpath wiring: tests
                // execute with kotlin-stdlib already on the test classpath,
                // and our generated classes only need to be verifiable +
                // loadable (which the test JVM handles).
                classpath = System.getProperty("java.class.path")
                // -Xjvm-default=all so default-method generation matches
                // what real app code typically uses (avoids surprises for
                // any future fixture that depends on interface defaults).
                jvmDefault = "all"
            }

            val messages = StringBuilder()
            // NOTE: Cannot use `by PrintingMessageCollector(...)` interface
            // delegation here — an override of a delegated method has no
            // `super` path back to the delegate (super resolves to Any),
            // so we hold the printing collector as an explicit field and
            // forward to it from inside `report`.
            val printing = PrintingMessageCollector(
                System.err,
                MessageRenderer.PLAIN_FULL_PATHS,
                /* verbose = */ false,
            )
            val collector: MessageCollector = object : MessageCollector {
                override fun clear() {
                    messages.setLength(0)
                    printing.clear()
                }

                override fun hasErrors(): Boolean = printing.hasErrors()

                override fun report(
                    severity: CompilerMessageSeverity,
                    message: String,
                    location: CompilerMessageSourceLocation?,
                ) {
                    messages.append("[").append(severity).append("] ").append(message)
                    if (location != null) {
                        messages.append(" at ").append(location.path).append(":").append(location.line)
                    }
                    messages.append("\n")
                    printing.report(severity, message, location)
                }
            }

            val exit = K2JVMCompiler().exec(collector, Services.EMPTY, args)
            if (exit != ExitCode.OK) {
                error("Kotlin compilation failed (exit=$exit):\n$messages")
            }

            val collected = mutableMapOf<String, ByteArray>()
            outputDir.walk()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { classFile ->
                    val relative = classFile.relativeTo(outputDir).path
                    val fqn = relative
                        .removeSuffix(".class")
                        .replace(File.separatorChar, '.')
                    collected[fqn] = classFile.readBytes()
                }
            return collected
        } finally {
            sourceDir.deleteRecursively()
            outputDir.deleteRecursively()
        }
    }
}
