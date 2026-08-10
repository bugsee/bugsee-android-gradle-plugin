package com.bugsee.android.gradle.instrumentation

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Provider

/**
 * Wires [SdkClassProbe] to the Bugsee artifact a lane injects into, yielding "may this lane
 * inject?".
 *
 * Every instrumentation emits an `INVOKESTATIC` naming an SDK class by string, into the HOST
 * app's own bytecode. If the resolved SDK lacks that class there is no SDK code path left to
 * absorb it: a debug build ships a dangling reference and dies with `NoClassDefFoundError`, and
 * a minified build fails at R8 with "Missing class". Plugin and SDK are versioned independently,
 * so that pairing is not hypothetical.
 *
 * **Why a DETACHED configuration, and not the variant's runtime classpath.** The obvious
 * implementation — resolving `variant.runtimeConfiguration` — cannot be used: the ASM transform
 * instruments that very classpath, so taking its artifacts as a transform input makes the
 * transform's own dependencies unresolvable. Consumers with Android PROJECT dependencies fail
 * outright with "Could not determine the dependencies of task ...transformClassesWithAsm /
 * cannot choose between the following variants". External-only consumers happen not to hit it,
 * which is what makes the bug easy to miss.
 *
 * A detached configuration holding the single Bugsee coordinate sidesteps that entirely: it is
 * not part of the app's task graph, so nothing waits on it and nothing is ambiguous.
 *
 * **Project dependencies are treated permissively.** Probing one would require BUILDING that
 * module to inspect its classes, reintroducing the ordering knot. A project dependency is source
 * the consumer controls (composite builds, the SDK's own sample app), which is precisely the
 * case where a stale SDK is least likely — so it is not worth a build-ordering hazard.
 */
internal object SdkSymbolAvailability {

    /**
     * @param fqn dot-separated class name the lane injects
     * @param feature human-readable capability name used in the skip warning
     */
    fun of(
        project: Project,
        fqn: String,
        feature: String,
        logger: Logger,
    ): Provider<Boolean> {
        val artifactPrefix = artifactFor(fqn)
        val coordinate = externalCoordinate(project, artifactPrefix)
            ?: return project.provider { true }

        val detached = project.configurations.detachedConfiguration(
            project.dependencies.create(coordinate)
        ).apply {
            // Only this artifact matters; pulling the transitive graph would be wasted work.
            isTransitive = false
        }

        return detached.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            val present =
                SdkClassProbe.containsClass(artifacts.map { it.file }, fqn.replace('.', '/'))
            if (!present) {
                logger.warn(
                    "Bugsee gradle plugin: the resolved Bugsee SDK ($coordinate) does not " +
                        "contain $fqn, so $feature instrumentation is unavailable and will be " +
                        "skipped. Upgrade the Bugsee SDK to enable it."
                )
            }
            present
        }.orElse(true)
    }

    /** Which published artifact ships [fqn]. Extension classes live in their own AAR. */
    private fun artifactFor(fqn: String): String = when {
        fqn.startsWith("com.bugsee.library.okhttp.") -> "bugsee-android-okhttp"
        else -> "bugsee-android"
    }

    /**
     * The declared `group:name:version` for [artifactPrefix], or null when it is not declared as
     * an external module with a concrete version — project dependencies, version ranges and
     * platform-managed versions all land here and are treated permissively by the caller.
     */
    private fun externalCoordinate(project: Project, artifactPrefix: String): String? {
        for (configuration in project.configurations) {
            for (dependency in configuration.dependencies) {
                if (dependency !is ExternalModuleDependency) continue
                if (dependency.group != "com.bugsee") continue
                if (dependency.name != artifactPrefix) continue
                val version = dependency.version
                if (version.isNullOrBlank() || version.contains('+') || version.contains('[')) {
                    return null
                }
                return "com.bugsee:${dependency.name}:$version"
            }
        }
        return null
    }
}
