package com.bugsee.android.gradle.instrumentation.extensions_init

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Provider

/**
 * Which Bugsee extension providers a variant ships, read from its resolved runtime classpath.
 *
 * This is the single input of BOTH halves of the consolidation: the manifest task strips only these
 * providers, and the `BugseeInitProvider.initializeExtensions()` rewrite registers exactly these. The
 * two therefore cannot disagree, whatever order Gradle runs them in.
 *
 * It replaces a file the manifest task wrote and the rewrite read. That file is not there yet when
 * AGP rewrites the SDK AAR through an artifact transform that Gradle runs early (e.g. when the SDK is
 * reached through a project dependency, issue #176), so the rewrite found no extensions and left
 * `initializeExtensions()` empty while the providers were already stripped.
 */
internal object ExtensionsOnClasspath {

    private const val BUGSEE_GROUP = "com.bugsee"

    /** Lazily resolved when queried (task-graph time), never at configuration time. */
    fun providersFor(runtimeConfiguration: Configuration): Provider<Set<String>> =
        runtimeConfiguration.incoming.resolutionResult.rootComponent.map { providersIn(it) }

    /** Provider FQNs of the known extension modules in the graph below [root], in [ExtensionSpec.KNOWN] order. */
    internal fun providersIn(root: ResolvedComponentResult): Set<String> {
        val present = HashSet<String>()
        val seen = HashSet<ResolvedComponentResult>()
        val queue = ArrayDeque<ResolvedComponentResult>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!seen.add(component)) continue
            val id = component.id
            if (id is ModuleComponentIdentifier && id.group == BUGSEE_GROUP) present += id.module
            component.dependencies.forEach { dep ->
                if (dep is ResolvedDependencyResult) queue.add(dep.selected)
            }
        }
        return ExtensionSpec.KNOWN.filter { it.artifactId in present }.map { it.initProviderFqn }.toCollection(LinkedHashSet())
    }
}
