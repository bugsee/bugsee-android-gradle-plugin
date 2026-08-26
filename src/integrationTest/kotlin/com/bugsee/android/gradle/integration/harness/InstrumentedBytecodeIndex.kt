package com.bugsee.android.gradle.integration.harness

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Walks the post-transform `.class` files produced by AGP for a fixture
 * project and indexes every `INVOKESTATIC` site that targets the Bugsee
 * app-startup dispatcher.
 *
 * AGP 8.6 writes post-`AsmClassVisitorFactory` output to:
 *
 * ```
 * <project>/<module>/build/intermediates/asm_instrumented_project_classes/<variant>/<task>/...
 * <project>/<module>/build/intermediates/asm_instrumented_project_jars/<variant>/<task>/...
 * ```
 *
 * The exact `<task>` folder name has changed across AGP minor versions
 * (`transformDebugClassesWithAsm`, `transformClassesWithAsmForDebug`, …),
 * so we recurse under both intermediate roots rather than pinning the
 * path. Loose `.class` files and jar entries are both inspected.
 */
internal object InstrumentedBytecodeIndex {

    /** Internal name of the dispatcher class — must match the SDK contract. */
    const val DISPATCHER_INTERNAL = "com/bugsee/library/adapters/BugseeAppStartupDispatcher"

    /** Internal name of the annotation marker — must match the SDK contract. */
    const val BUGSEE_TRACE_DESCRIPTOR = "Lcom/bugsee/library/contracts/performance/BugseeTrace;"

    /**
     * A single `INVOKESTATIC` site found in transformed bytecode.
     *
     * @property ownerClassInternal the internal name of the class the
     *   instruction lives in (e.g. `"com/example/fixture/SampleApp"`).
     * @property ownerMethodName the method that contains the call.
     * @property ownerMethodDescriptor the method's JVM descriptor.
     * @property targetMethodName the dispatcher method invoked
     *   (e.g. `"onMethodStart"`).
     * @property siteIdConstant the `Ldc String` operand immediately
     *   preceding the call — captures the site-id payload the plugin
     *   passes to the dispatcher. `null` if the immediately preceding
     *   instruction is not an LDC (defensive — should never happen for
     *   valid plugin output).
     */
    data class CallSite(
        val ownerClassInternal: String,
        val ownerMethodName: String,
        val ownerMethodDescriptor: String,
        val targetMethodName: String,
        val siteIdConstant: String?,
    )

    /**
     * Indexed view of one transformed project.
     *
     * @property dispatcherCalls all dispatcher INVOKESTATIC sites, grouped
     *   by the class they appear in.
     * @property annotatedMethods set of `internal_name#method_name` keys
     *   for methods carrying `@BugseeTrace` post-transform (the marker
     *   is CLASS-retention, so it survives into the .class file).
     */
    data class Index(
        val dispatcherCalls: Map<String, List<CallSite>>,
        val annotatedMethods: Set<String>,
    ) {
        /** Total dispatcher calls anywhere in the index. */
        fun totalDispatcherCalls(): Int = dispatcherCalls.values.sumOf { it.size }

        /** Dispatcher calls inside one class, grouped by target method name. */
        fun countByTarget(classInternal: String): Map<String, Int> {
            val sites = dispatcherCalls[classInternal] ?: return emptyMap()
            return sites.groupingBy { it.targetMethodName }.eachCount()
        }

        /** All dispatcher sites inside one class+method. */
        fun sitesIn(classInternal: String, methodName: String): List<CallSite> {
            val sites = dispatcherCalls[classInternal] ?: return emptyList()
            return sites.filter { it.ownerMethodName == methodName }
        }

        /** Total dispatcher calls grouped by target method (e.g. `onMethodStart` → 4). */
        fun countByTargetGlobal(): Map<String, Int> {
            val grouped = HashMap<String, Int>()
            for (sites in dispatcherCalls.values) {
                for (site in sites) {
                    grouped.merge(site.targetMethodName, 1) { a, b -> a + b }
                }
            }
            return grouped
        }
    }

    /**
     * Walks the post-`AsmClassVisitorFactory` output for the given
     * variant under `<projectDir>/<module>/build/intermediates/` and
     * returns an [Index] over the dispatcher `INVOKESTATIC` sites +
     * `@BugseeTrace`-annotated methods.
     *
     * AGP writes post-transform output under:
     *   `intermediates/classes/<variant>/transformDebugClassesWithAsm/{dirs,jars}`
     * In AGP 8.6 the task name is `transform<Variant>ClassesWithAsm` —
     * we don't pin that exact name because it has changed across AGP
     * minor releases. Instead the harness recursively scans under
     * `intermediates/classes/<variant>/` and picks up every `.class`
     * file and `.jar` containing `.class` entries.
     */
    /**
     * Like [walk], but returns an EMPTY index when the post-transform classes
     * directory does not exist, instead of throwing.
     *
     * Absence is a legitimate, expected outcome for a variant that registers no
     * instrumentation at all: AGP only creates
     * `intermediates/classes/<variant>` when some transform actually runs. Use
     * this only where "nothing was instrumented" is the assertion being made —
     * [walk] deliberately keeps throwing, so a test that EXPECTS instrumentation
     * still fails loudly if the transform silently never ran.
     */
    fun walkOrEmpty(projectDir: File, module: String = "app", variant: String = "debug"): Index {
        val classesRoot = File(File(projectDir, "$module/build/intermediates"), "classes/$variant")
        if (!classesRoot.isDirectory) return Index(emptyMap(), emptySet())
        return walk(projectDir, module, variant)
    }

    fun walk(projectDir: File, module: String = "app", variant: String = "debug"): Index {
        val intermediates = File(projectDir, "$module/build/intermediates")
        require(intermediates.isDirectory) {
            "intermediates dir not found: $intermediates"
        }

        val classesRoot = File(intermediates, "classes/$variant")
        require(classesRoot.isDirectory) {
            "post-transform classes dir not found: $classesRoot — " +
                    "did the AGP transform task run?"
        }
        val classRoots = listOf(classesRoot)

        val dispatcherCalls = HashMap<String, MutableList<CallSite>>()
        val annotated = HashSet<String>()

        for (root in classRoots) {
            if (!root.isDirectory) continue
            root.walkTopDown().forEach { entry ->
                when {
                    entry.isFile && entry.name.endsWith(".class") ->
                        entry.inputStream().use { collectFromStream(it, dispatcherCalls, annotated) }
                    entry.isFile && entry.name.endsWith(".jar") ->
                        ZipFile(entry).use { zf ->
                            zf.entries().asSequence()
                                .filter { it.name.endsWith(".class") }
                                .forEach { zip ->
                                    zf.getInputStream(zip).use {
                                        collectFromStream(it, dispatcherCalls, annotated)
                                    }
                                }
                        }
                }
            }
        }

        return Index(
            dispatcherCalls = dispatcherCalls.mapValues { it.value.toList() },
            annotatedMethods = annotated,
        )
    }

    private fun collectFromStream(
        stream: InputStream,
        out: HashMap<String, MutableList<CallSite>>,
        annotated: HashSet<String>,
    ) {
        val node = ClassNode()
        ClassReader(stream).accept(node, ClassReader.SKIP_FRAMES)

        for (method in node.methods.orEmpty()) {
            val visAnnotations = method.visibleAnnotations.orEmpty()
            val invisAnnotations = method.invisibleAnnotations.orEmpty()
            val isAnnotated = (visAnnotations + invisAnnotations).any { it.desc == BUGSEE_TRACE_DESCRIPTOR }
            if (isAnnotated) {
                annotated.add(node.name + "#" + method.name)
            }

            val instructions = method.instructions ?: continue
            var prev: AbstractInsnNode? = null
            for (insn in instructions) {
                if (insn is MethodInsnNode
                    && insn.opcode == Opcodes.INVOKESTATIC
                    && insn.owner == DISPATCHER_INTERNAL
                ) {
                    val siteId = (prev as? LdcInsnNode)?.cst as? String
                    out.getOrPut(node.name) { ArrayList() }.add(
                        CallSite(
                            ownerClassInternal = node.name,
                            ownerMethodName = method.name,
                            ownerMethodDescriptor = method.desc,
                            targetMethodName = insn.name,
                            siteIdConstant = siteId,
                        )
                    )
                }
                // Track only real instructions (skip labels/line numbers) for
                // the LDC-precedes-call heuristic.
                if (insn.opcode >= 0) {
                    prev = insn
                }
            }
        }
    }
}
