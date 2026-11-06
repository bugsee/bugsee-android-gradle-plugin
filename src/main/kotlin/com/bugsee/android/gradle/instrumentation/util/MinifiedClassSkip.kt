package com.bugsee.android.gradle.instrumentation.util

import org.gradle.api.logging.Logging
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import java.util.Collections
import java.util.WeakHashMap

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
 *
 * The same entry point also skips a class whose constant pool is already near the 65,535-entry
 * limit (see [isPoolNearLimit]): instrumenting it could fail the build with
 * `ClassTooLargeException`.
 */
internal object MinifiedClassSkip {

    fun shouldSkip(nextClassVisitor: ClassVisitor): Boolean {
        val reader = findClassReader(nextClassVisitor) ?: return false
        // Each check fails open on its own: one throwing must not disable the other.
        val minified = try {
            MinifiedClassDetector.isMinified(reader)
        } catch (t: Throwable) {
            false
        }
        if (minified) return true
        return try {
            isPoolNearLimit(reader)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * A class whose constant pool is already within [POOL_HEADROOM] entries of the 65,535 limit
     * is left wholly uninstrumented. Instrumentation adds pool entries, and the overflow
     * (`ClassTooLargeException`) is raised by AGP's writer after the visitor chain has finished,
     * where no lane can take back the instrumentation of the methods already written. The
     * buffering lanes catch it per method (see [FrameFallbackContext]); the streaming lanes
     * cannot, so the only safe answer for such a class is to not start. Such classes are
     * pathological (typically obfuscator output), so nothing of value is lost.
     *
     * The headroom is a heuristic. Most lanes add a few hundred deduplicated entries in total; app
     * startup tracing adds a site-id string per distinct callee, which can exceed it in a huge
     * class, but that lane buffers through [FrameFallbackContext], whose padded trial reverts the
     * method that would overflow.
     *
     * Warns once per processing run of a class (all lanes share one reader), not once per lane.
     */
    private fun isPoolNearLimit(reader: ClassReader): Boolean {
        if (reader.itemCount <= MAX_POOL_ENTRIES - POOL_HEADROOM) return false
        if (warned.add(reader)) {
            val name = reader.className.replace('/', '.')
            val message = "Bugsee: $name was left UNINSTRUMENTED: its constant pool has ${reader.itemCount} " +
                "of $MAX_POOL_ENTRIES entries, so adding instrumentation could exceed the class file " +
                "limit (ClassTooLargeException) and fail the build. The build continues; Bugsee " +
                "captures nothing in that class."
            val sink = reportSink
            if (sink != null) sink(message) else LOGGER.warn(message)
        }
        return true
    }

    /** Test hook: receives every message that would be logged. */
    @Volatile
    internal var reportSink: ((String) -> Unit)? = null

    private val LOGGER = Logging.getLogger("com.bugsee.android.gradle.instrumentation")

    /** Identity-keyed (ClassReader has no equals) and weak, so it never outlives a run. */
    private val warned: MutableSet<ClassReader> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<ClassReader, Boolean>()))

    internal const val MAX_POOL_ENTRIES = 0xFFFF

    /** Headroom below [MAX_POOL_ENTRIES] that a class must have to be instrumented at all. */
    internal const val POOL_HEADROOM = 4096

    internal fun findClassReader(visitor: ClassVisitor): ClassReader? = try {
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
    internal fun findClassWriter(visitor: ClassVisitor): ClassWriter? {
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
