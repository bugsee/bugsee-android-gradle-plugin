package com.bugsee.android.gradle.upload

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

/**
 * Walks a variant's resolved Gradle dependency graph and produces the
 * payload sent to Bugsee — a flat list of [DependencyEntry] plus the
 * scalar [DependenciesSummary].
 *
 * Two-pass design:
 *   1. Pre-build a `(group, name) → scope-name` map from declared
 *      dependencies across api / implementation / runtimeOnly /
 *      compileOnly configurations. Only direct deps carry scope —
 *      transitives don't have a meaningful one.
 *   2. Walk the resolved graph in DFS pre-order; every unique
 *      component becomes one entry. Direct entries (first-level
 *      children of the resolved root) get a `direct=true` flag and
 *      the scope from step 1. The walk is recursive (not BFS) —
 *      ordering inside the direct / transitive partitions reflects
 *      DFS visitation, which is deterministic for a given resolved
 *      graph and stable across builds.
 *
 * Cap + truncation:
 *   - Entries are appended in deterministic order: direct deps first
 *     (so a truncated upload still preserves the most important
 *     information), then transitives in component-id order. Beyond
 *     `maxCount` the rest are dropped and the summary's `truncated`
 *     flag is set.
 *
 * Output is pure — no Gradle Project / Logger / Provider state leaks
 * out of this collector. Callers wire it to the upload task via task
 * inputs serialised to JSON.
 */
class DependencyCollector(
    private val maxCount: Int,
    private val includeSelectedReason: Boolean,
    /**
     * `true` to drop transitive dependencies from the output. The
     * resolved graph is still walked (we need it to identify what's
     * direct), but only first-level entries make it into the emitted
     * list and summary. Maps to the DSL value
     * `bugsee.buildInfo.dependencies.scope = "runtime_direct_only"`.
     */
    private val directOnly: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * @param resolutionRoot Resolved root component of the variant's
     *                       runtime classpath. `root.dependencies` is
     *                       the first-level set; transitives are
     *                       reached via component traversal.
     * @param declaredScopes Map from `"group:name"` to the
     *                       declaring-configuration name. Built by
     *                       [collectDeclaredScopes] from the project's
     *                       api / implementation / runtimeOnly /
     *                       compileOnly configurations.
     * @param fileDependencies Module-coordinate-less dependencies the
     *                       caller picked up from raw file/jar
     *                       references (Configuration.dependencies
     *                       filtered to FileCollectionDependency).
     */
    fun collect(
        resolutionRoot: ResolvedComponentResult,
        declaredScopes: Map<String, String>,
        fileDependencies: List<FileDep>
    ): Result {
        // First-level resolved deps from the variant's classpath — these
        // are what the user *declared*; everything else is transitive.
        val directComponents: Set<String> =
            resolutionRoot.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .map { componentKey(it.selected) }
                .toSet()

        // Order: direct entries first (preserves the most meaningful
        // information across truncation), then transitives in DFS
        // pre-order visit order. Walk every unique component exactly
        // once (dedup via the `visited` set keyed by `componentKey`).
        //
        // `parentsByKey` accumulates the upstream-edges set for each
        // component — populated on EVERY arrival at a node (not just
        // the first), so a shared transitive correctly records all of
        // its parents. Recursion into children, by contrast, only
        // happens once per component (the standard DFS dedup).
        // LinkedHashSet keeps the insertion order stable.
        val visited = mutableSetOf<String>()
        val ordered = mutableListOf<ResolvedComponentResult>()
        val parentsByKey = mutableMapOf<String, LinkedHashSet<String>>()
        walk(resolutionRoot, parentId = null, visited, ordered, parentsByKey, skipRoot = true)

        // Partition: direct first, then transitive. `ordered` was
        // built by a deterministic DFS pre-order walk, so the result
        // is stable across builds.
        val (directList, transitiveList) =
            ordered.partition { directComponents.contains(componentKey(it)) }

        val cap = maxCount.coerceAtLeast(0)
        val entries = mutableListOf<DependencyEntry>()
        var truncated = false

        // File-collection deps come from the raw configuration.dependencies
        // tree, not the resolved graph (they have no GAV / component id).
        // They are always direct by definition.
        for (file in fileDependencies) {
            if (entries.size >= cap) { truncated = true; break }
            entries.add(
                DependencyEntry(
                    group = "",
                    name = file.displayName,
                    version = null,
                    direct = true,
                    scope = file.scope,
                    type = DependencyEntry.Type.FILE
                )
            )
        }

        for (c in directList) {
            if (entries.size >= cap) { truncated = true; break }
            entries.add(toEntry(c, direct = true, declaredScopes = declaredScopes, parentsByKey = parentsByKey))
        }
        // In `directOnly` mode (`scope = "runtime_direct_only"`),
        // skip transitives entirely. Walking the graph is still
        // necessary to identify what's direct, so the cost is paid
        // either way — only the emission differs.
        if (!directOnly) {
            for (c in transitiveList) {
                if (entries.size >= cap) { truncated = true; break }
                entries.add(toEntry(c, direct = false, declaredScopes = declaredScopes, parentsByKey = parentsByKey))
            }
        }

        // Self-consistency pass: truncation can drop entries that
        // still appear in someone else's `parents` list. Filter those
        // dangling references out so every ID in any `parents` list
        // is guaranteed to resolve to an entry present in this blob.
        // Cheap (linear in total parent-edge count) and only matters
        // when `truncated == true`, but the filter is safe either way.
        val keptIds: Set<String> = entries.mapTo(mutableSetOf()) { it.id }
        val cleaned = entries.map { e ->
            if (e.parents.isEmpty()) e
            else {
                val kept = e.parents.filter { it in keptIds }
                if (kept.size == e.parents.size) e else e.copy(parents = kept)
            }
        }

        val config = CollectionConfig(
            scope = if (directOnly) "runtime_direct_only" else "runtime",
            includeSelectedReason = includeSelectedReason,
            maxCount = maxCount
        )
        val summary = DependenciesSummary.from(cleaned, truncated, clock(), config)
        return Result(entries = cleaned, summary = summary)
    }

    private fun walk(
        node: ResolvedComponentResult,
        parentId: String?,
        visited: MutableSet<String>,
        ordered: MutableList<ResolvedComponentResult>,
        parentsByKey: MutableMap<String, LinkedHashSet<String>>,
        skipRoot: Boolean
    ) {
        val key = componentKey(node)
        if (!skipRoot) {
            // Parent edge is recorded BEFORE the dedup check — a
            // shared transitive C reached via both A→C and B→C must
            // accumulate both parents, even though we only recurse
            // into its children once.
            if (parentId != null) {
                parentsByKey.getOrPut(key) { LinkedHashSet() }.add(parentId)
            }
            if (!visited.add(key)) return
            ordered.add(node)
        }
        // DFS pre-order traversal: visit each child fully before
        // moving to the next sibling. `dependencies` returns
        // DependencyResult; resolved ones carry the next component.
        // For the root frame we pass `null` so the immediate children
        // (direct deps) get an empty parents list, mirroring the
        // "root is never an entry" convention.
        val nextParentId = if (skipRoot) null else idFromKey(node)
        for (dep in node.dependencies) {
            if (dep is ResolvedDependencyResult) {
                walk(dep.selected, nextParentId, visited, ordered, parentsByKey, skipRoot = false)
            }
        }
    }

    /**
     * Build the [DependencyEntry.id] for a resolved component without
     * going through [toEntry]. Used during the DFS walk so the
     * parents-set values match the IDs the emitted entries carry.
     */
    private fun idFromKey(component: ResolvedComponentResult): String {
        val (group, name, _, type) = identityTuple(component)
        return DependencyEntry.makeId(type, group, name)
    }

    private fun toEntry(
        component: ResolvedComponentResult,
        direct: Boolean,
        declaredScopes: Map<String, String>,
        parentsByKey: Map<String, LinkedHashSet<String>>
    ): DependencyEntry {
        val (group, name, version, type) = identityTuple(component)
        val scope = if (direct) declaredScopes[scopeKey(group, name)] else null
        val reason = if (includeSelectedReason) {
            // `selectionReason.descriptions` is a list of
            // ComponentSelectionDescriptor; each one has a string
            // `description`. The first carries the headline rationale
            // (forced, conflict resolution, …); we take that and cap
            // its length.
            component.selectionReason.descriptions
                .firstOrNull()
                ?.description
                ?.take(SELECTION_REASON_MAX_LEN)
        } else null
        val parents = parentsByKey[componentKey(component)]?.toList() ?: emptyList()
        return DependencyEntry(
            group = group,
            name = name,
            version = version,
            direct = direct,
            scope = scope,
            type = type,
            selectedReason = reason,
            id = DependencyEntry.makeId(type, group, name),
            parents = parents
        )
    }

    /** Stable identity for de-dup. Project deps don't have group/version. */
    private fun componentKey(c: ResolvedComponentResult): String {
        val id = c.id
        return when (id) {
            is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
            is ProjectComponentIdentifier -> "project:${id.projectPath}"
            else -> "other:${id.displayName}"
        }
    }

    /**
     * Extract `(group, name, version, type)` from a resolved component.
     * Shared between [toEntry] and [idFromKey] so the parents-edge IDs
     * are built from the same coordinates the emitted entries carry —
     * any drift would silently produce dangling references.
     */
    private fun identityTuple(c: ResolvedComponentResult): Quad {
        val id = c.id
        return when (id) {
            is ModuleComponentIdentifier -> Quad(id.group, id.module, id.version, DependencyEntry.Type.LIBRARY)
            is ProjectComponentIdentifier -> Quad("", id.projectPath, null, DependencyEntry.Type.PROJECT)
            else -> Quad("", id.displayName, null, DependencyEntry.Type.FILE)
        }
    }

    private fun scopeKey(group: String, name: String): String = "$group:$name"

    private data class Quad(
        val group: String,
        val name: String,
        val version: String?,
        val type: DependencyEntry.Type
    )

    /** Output bundle. */
    data class Result(val entries: List<DependencyEntry>,
                      val summary: DependenciesSummary)

    /** Module-coordinate-less dep extracted from a raw configuration. */
    data class FileDep(val displayName: String, val scope: String?)

    companion object {
        // Bounded length on the human-readable selection reason so a
        // pathological multi-paragraph description (some plugins emit
        // those) can't blow up the per-entry serialised size.
        private const val SELECTION_REASON_MAX_LEN = 512

        /**
         * Build the `(group:name) → scope-name` map from declared
         * dependencies across the four well-known scopes. Caller
         * decides which scopes to include based on the configured
         * `scope` option — `runtime` walks api/implementation/
         * runtimeOnly, `compile_runtime` adds compileOnly.
         *
         * Skips project + file deps — those don't have GA coords
         * matching the resolved graph the [collect] step walks.
         */
        @JvmStatic
        fun collectDeclaredScopes(configurations: Map<String, Configuration>): Map<String, String> {
            // Multiple configurations can declare the same (group, name)
            // — `implementation` + `api` is a real pattern when a
            // module re-exports a dep. First-wins is deterministic
            // since the caller passes a LinkedHashMap; documented at
            // the call site.
            val out = LinkedHashMap<String, String>()
            for ((scopeName, conf) in configurations) {
                for (dep in conf.dependencies) {
                    if (dep is ProjectDependency) continue
                    if (dep is ModuleDependency) {
                        val g = dep.group ?: continue
                        val n = dep.name
                        val key = "$g:$n"
                        out.putIfAbsent(key, scopeName)
                    }
                }
            }
            return out
        }
    }
}
