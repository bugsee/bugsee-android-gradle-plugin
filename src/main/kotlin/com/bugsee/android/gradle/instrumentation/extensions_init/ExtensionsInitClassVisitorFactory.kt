package com.bugsee.android.gradle.instrumentation.extensions_init

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.bugsee.android.gradle.instrumentation.util.InstrumentationExcludes
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/**
 * ASM visitor factory that rewrites the body of
 * `com.bugsee.library.BugseeInitProvider#initializeExtensions()` to call
 * each detected extension's `register<Name>Extension()` static method.
 *
 * Unlike the other visitors in this plugin, this one is *narrowly
 * targeted* at a single SDK class — the per-class denylist that the
 * app-startup tracing visitor uses to keep `com.bugsee.*` out of
 * instrumentation is irrelevant here. The same denylist still exists
 * over there for a reason: that visitor wraps every Application /
 * Initializer / ContentProvider it sees, and self-wrapping the SDK would
 * recurse. The current visitor only ever touches one specific method on
 * one specific class, so there is no recursion risk.
 */
abstract class ExtensionsInitClassVisitorFactory :
    AsmClassVisitorFactory<ExtensionsInitParameters> {

    // Intentionally stateless. AGP's `AsmClassesTransform` isolates worker
    // parameters by serializing the decorated factory; any instance field
    // ends up on that serialization path. Kotlin's `by lazy` delegate
    // captures the initializer lambda and is not serializable, so caching
    // here used to fail the build with:
    //
    //   Could not serialize value of type ExtensionsInitClassVisitorFactory
    //
    // `isInstrumentable` filters to a single class (`BugseeInitProvider`),
    // so `createClassVisitor` runs at most once per build — re-reading
    // the detection file there is essentially free.

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor {
        // No MinifiedClassSkip here, unlike every other lane: the only target is our
        // own BugseeInitProvider, and the published SDK is always R8-processed, so the
        // skip matched it on every real build and left the stripped extensions with
        // no registration (plugin 4.0.6). The skip exists for third-party bytecode.

        val specs = loadSpecs()
        if (specs.isEmpty()) return nextClassVisitor
        return ExtensionsInitClassVisitor(
            apiVersion = Opcodes.ASM9,
            nextClassVisitor = nextClassVisitor,
            extensionSpecs = specs,
            className = classContext.currentClassData.className,
        )
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        if (InstrumentationExcludes.isExcluded(classData.className, parameters.get().excludes.get())) {
            return false
        }
        return classData.className == TARGET_CLASS_FQN
    }

    private fun loadSpecs(): List<ExtensionSpec> {
        val fileProvider = parameters.get().detectedExtensionsFile
        if (!fileProvider.isPresent) return emptyList()
        val file = fileProvider.get().asFile
        if (!file.isFile) return emptyList()
        return file.readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { ExtensionSpec.fromInitProviderFqn(it) }
            .toList()
    }

    companion object {
        internal const val TARGET_CLASS_FQN = "com.bugsee.library.BugseeInitProvider"
    }
}
