package com.bugsee.android.gradle.instrumentation.util

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter

/**
 * Decides whether a class should be left completely untouched because R8 already
 * optimised it. See [MinifiedClassDetector] for why, and for what is deliberately not
 * checked.
 *
 * Every `AsmClassVisitorFactory.createClassVisitor` calls this FIRST and returns
 * `nextClassVisitor` unchanged when it answers true, so the class passes through as if
 * no Bugsee lane were installed.
 *
 * ### Why this needs reflection
 *
 * AGP hands a factory only the downstream [ClassVisitor]; the class bytes are not
 * exposed. `ClassData` carries names, not the constant pool. So the only route to the
 * [ClassReader] is the visitor chain: walk `cv` down to the [ClassWriter], then reach
 * its `symbolTable.sourceClassReader`. Those are ASM-internal fields, which is why
 * every step is guarded and any failure means "not minified" — instrumentation then
 * proceeds exactly as it did before this check existed.
 *
 * Failing OPEN is the deliberate choice: a missed skip costs the pre-existing behaviour
 * (a class we would have instrumented anyway), whereas failing closed on a reflection
 * change would silently disable every lane at once.
 */
internal object MinifiedClassSkip {

    fun shouldSkip(nextClassVisitor: ClassVisitor): Boolean {
        val reader = findClassReader(nextClassVisitor) ?: return false
        return try {
            MinifiedClassDetector.isMinified(reader)
        } catch (t: Throwable) {
            false
        }
    }

    private fun findClassReader(visitor: ClassVisitor): ClassReader? = try {
        val writer = findClassWriter(visitor)
        writer?.let {
            val symbolTable = readField(it, ClassWriter::class.java, "symbolTable")
            symbolTable?.let { table ->
                readField(table, table.javaClass, "sourceClassReader") as? ClassReader
            }
        }
    } catch (t: Throwable) {
        null
    }

    /** Walks the delegate chain (`ClassVisitor.cv`) looking for the terminal writer. */
    private fun findClassWriter(visitor: ClassVisitor): ClassWriter? {
        var current: ClassVisitor? = visitor
        // Bounded: a malformed or cyclic chain must not spin the build.
        repeat(MAX_CHAIN_DEPTH) {
            if (current == null) return null
            if (current is ClassWriter) return current
            current = readField(current!!, ClassVisitor::class.java, "cv") as? ClassVisitor
        }
        return null
    }

    private fun readField(target: Any, declaringClass: Class<*>, name: String): Any? = try {
        declaringClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    } catch (t: Throwable) {
        null
    }

    private const val MAX_CHAIN_DEPTH = 16
}
