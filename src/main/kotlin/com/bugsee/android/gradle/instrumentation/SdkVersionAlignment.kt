package com.bugsee.android.gradle.instrumentation

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.logging.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolution-time warning for mixed `com.bugsee:*` versions on one runtime classpath
 * (e.g. `bugsee-android:7.1.0` next to `bugsee-android-okhttp:7.0.2`). The SDK modules are
 * released as one line; mixing them can fail at runtime with `NoSuchMethodError`.
 *
 * Only the project's own variant configuration is inspected, and only inside Gradle's own
 * `afterResolve` callback: nothing here forces resolution at configuration time, touches
 * another project, or is stored on a task, so it is configuration-cache and Isolated Projects
 * safe. It never fails the build. On a configuration-cache hit nothing resolves in the
 * configuration phase, so the warning is only emitted on runs that actually resolve.
 */
internal object SdkVersionAlignment {

    /** A resolved `group:name` at [version]. */
    data class Module(val group: String, val name: String, val version: String)

    /**
     * The warning text when the SDK modules in [modules] do not all share one version, else
     * `null`. Non-SDK modules (see [BugseeSdkDeclarations.isSdkModule]) are ignored.
     */
    fun mismatchMessage(label: String, modules: Collection<Module>): String? {
        val sdk = modules
            .filter { BugseeSdkDeclarations.isSdkModule(it.group, it.name) && it.version.isNotBlank() }
            .distinct()
        if (sdk.map { it.version }.toSet().size < 2) return null
        val listing = sdk.sortedWith(compareBy({ it.name }, { it.version }))
            .joinToString(", ") { "${it.group}:${it.name}:${it.version}" }
        return "Bugsee gradle plugin: variant $label resolves mixed Bugsee SDK versions " +
            "($listing). Modules from different SDK releases may be incompatible at runtime " +
            "(NoSuchMethodError / NoClassDefFoundError). Align all com.bugsee:* dependencies on one version."
    }

    /**
     * Registers the check on [configuration] (a variant runtime configuration). Fires at most
     * once per call, when that configuration resolves.
     */
    fun watch(logger: Logger, label: String, configuration: Configuration) {
        val fired = AtomicBoolean(false)
        configuration.incoming.afterResolve { resolvable ->
            if (fired.get()) return@afterResolve
            val modules = try {
                resolvable.resolutionResult.allComponents.mapNotNull { component ->
                    val id = component.id as? ModuleComponentIdentifier ?: return@mapNotNull null
                    Module(id.group, id.module, id.version)
                }
            } catch (e: Exception) {
                // A diagnostic must never fail the build (an unresolvable graph reports its own error).
                logger.debug("Bugsee: could not inspect resolved SDK versions for $label", e)
                return@afterResolve
            }
            val message = mismatchMessage(label, modules) ?: return@afterResolve
            if (fired.compareAndSet(false, true)) logger.warn(message)
        }
    }
}
