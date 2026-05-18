package com.bugsee.android.gradle.instrumentation.fixtures

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.BasicValue
import org.objectweb.asm.tree.analysis.SimpleVerifier
import org.objectweb.asm.util.CheckClassAdapter
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Drives the test-harness workflow for the app-startup tracing ASM
 * transform:
 *
 *  1. **Compile** an inline Java or Kotlin source snippet via
 *     [JavaSourceCompiler] / [KotlinSourceCompiler] to a map of class
 *     name → bytes.
 *  2. **Transform** any subset of those bytes through an arbitrary ASM
 *     `ClassVisitor` chain via [transform].
 *  3. **Verify** the resulting bytecode passes the JVM verifier
 *     ([CheckClassAdapter]) and per-method dataflow analysis
 *     ([Analyzer]) via [verify]. Both checks are mandatory — bytecode
 *     that produces broken stack maps or type-incorrect frames is what
 *     production crashes look like, and skipping them gives false
 *     confidence.
 *  4. **Load and invoke** transformed classes via [loadAndInvokeStatic]
 *     using an [InMemoryClassLoader].
 *
 * Each operation is intentionally a free function so tests can compose
 * partial workflows (e.g. compile-and-verify-without-loading) without
 * dragging in the full pipeline.
 */
internal object AsmTestHarness {

    /**
     * Runs [bytes] through [transformer] (which receives a fresh
     * [ClassWriter] and must return a [ClassVisitor] chain whose terminal
     * delegate is that writer). Returns the transformed bytes.
     *
     * Pass [computeFrames] = `true` (default) to compute stack-map frames
     * — required when injected bytecode changes max-stack or locals,
     * which it usually does.
     */
    fun transform(
        bytes: ByteArray,
        computeFrames: Boolean = true,
        transformer: (ClassWriter) -> ClassVisitor,
    ): ByteArray {
        val reader = ClassReader(bytes)
        val writerFlags = if (computeFrames) ClassWriter.COMPUTE_FRAMES else 0
        val writer = ClassWriter(reader, writerFlags)
        val visitor = transformer(writer)
        // EXPAND_FRAMES so visitors that read frames receive uncompressed
        // frame info — the production transform code will need this.
        val readFlags = if (computeFrames) ClassReader.EXPAND_FRAMES else 0
        reader.accept(visitor, readFlags)
        return writer.toByteArray()
    }

    /**
     * Runs the JVM verifier ([CheckClassAdapter]) and per-method
     * [Analyzer] dataflow check over [bytes]. Returns a [VerifyResult]
     * collecting all diagnostics.
     *
     * Throws nothing; tests should call [VerifyResult.assertOk] to fail
     * with a readable message including the offending class/method.
     */
    fun verify(bytes: ByteArray): VerifyResult {
        val reader = ClassReader(bytes)

        // 1. JVM-level structural / verifier check.
        val checkOutput = StringWriter()
        try {
            CheckClassAdapter.verify(reader, false, PrintWriter(checkOutput))
        } catch (t: Throwable) {
            return VerifyResult(
                checkAdapterOutput = checkOutput.toString(),
                analyzerErrors = emptyList(),
                fatalThrowable = t,
            )
        }

        // 2. Per-method dataflow analysis with SimpleVerifier (NOT
        //    BasicInterpreter). SimpleVerifier resolves actual reference
        //    types through a ClassLoader, which is what catches the
        //    frame-type bugs the eventual try/finally injection in
        //    Phase 5+ will produce — e.g. an ATHROW where the handler
        //    frame claims Throwable but the actual value is some other
        //    reference. BasicInterpreter erases everything to "REFERENCE"
        //    and would miss these.
        val cn = ClassNode()
        reader.accept(cn, ClassReader.EXPAND_FRAMES)
        val analyzerErrors = mutableListOf<AnalyzerMethodError>()
        val verifierClassLoader: ClassLoader =
            Thread.currentThread().contextClassLoader
                ?: AsmTestHarness::class.java.classLoader

        val currentClass = Type.getObjectType(cn.name)
        val superClass = cn.superName?.let { Type.getObjectType(it) }
        val interfaces = cn.interfaces?.map { Type.getObjectType(it) } ?: emptyList()
        val isInterface = (cn.access and Opcodes.ACC_INTERFACE) != 0
        for (method in cn.methods) {
            // Build a fresh verifier per method — SimpleVerifier holds
            // per-method state internally during analyze().
            val verifier = SimpleVerifier(currentClass, superClass, interfaces, isInterface)
            verifier.setClassLoader(verifierClassLoader)
            try {
                Analyzer<BasicValue>(verifier).analyze(cn.name, method)
            } catch (e: AnalyzerException) {
                analyzerErrors.add(
                    AnalyzerMethodError(
                        className = cn.name,
                        methodName = method.name,
                        descriptor = method.desc,
                        cause = e,
                    )
                )
            }
        }

        return VerifyResult(
            checkAdapterOutput = checkOutput.toString(),
            analyzerErrors = analyzerErrors,
            fatalThrowable = null,
        )
    }

    /**
     * Loads [classBytes] into a fresh [InMemoryClassLoader] (so each call
     * is hermetic — defining the same class twice in one classloader
     * throws `LinkageError`) and reflectively invokes a static method.
     *
     * @param classBytes map of FQN → bytes produced by the source
     * compilers and/or [transform]
     * @param ownerFqn fully-qualified name of the class to invoke on
     * @param methodName name of the static method
     * @param parameterTypes for binding the reflective method lookup —
     * use the JDK boxed equivalents for primitives (`Int::class.javaPrimitiveType`)
     * @param args matching the [parameterTypes] count
     */
    fun loadAndInvokeStatic(
        classBytes: Map<String, ByteArray>,
        ownerFqn: String,
        methodName: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        args: Array<Any?> = emptyArray(),
    ): Any? {
        val loader = InMemoryClassLoader(classBytes)
        val klass = loader.loadClass(ownerFqn)
        val method = klass.getDeclaredMethod(methodName, *parameterTypes)
        method.isAccessible = true
        return method.invoke(null, *args)
    }

    /**
     * Composite result of [verify].
     */
    data class VerifyResult(
        val checkAdapterOutput: String,
        val analyzerErrors: List<AnalyzerMethodError>,
        val fatalThrowable: Throwable?,
    ) {
        /** True iff no diagnostics were collected. */
        val isOk: Boolean
            get() = checkAdapterOutput.isBlank() && analyzerErrors.isEmpty() && fatalThrowable == null

        /** Throws an [AssertionError] with a readable message if not [isOk]. */
        fun assertOk() {
            if (isOk) return
            val sb = StringBuilder("ASM verification failed:\n")
            if (fatalThrowable != null) {
                sb.append("  CheckClassAdapter threw: ").append(fatalThrowable).append('\n')
            }
            if (checkAdapterOutput.isNotBlank()) {
                sb.append("  CheckClassAdapter output:\n").append(checkAdapterOutput.prependIndent("    "))
            }
            for (err in analyzerErrors) {
                sb.append("  Analyzer error in ")
                    .append(err.className).append('#').append(err.methodName)
                    .append(err.descriptor).append(": ").append(err.cause.message).append('\n')
            }
            throw AssertionError(sb.toString())
        }
    }

    data class AnalyzerMethodError(
        val className: String,
        val methodName: String,
        val descriptor: String,
        val cause: AnalyzerException,
    )
}
