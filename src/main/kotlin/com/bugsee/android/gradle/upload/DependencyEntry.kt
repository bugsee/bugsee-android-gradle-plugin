package com.bugsee.android.gradle.upload

/**
 * One entry in the per-variant dependency list shipped to Bugsee.
 *
 * Maps 1:1 to the JSON shape consumed by the worker
 * (`schema_version=1`, `dependencies` array) and stored in S3 under
 * `{org}/{app}/builds/{build}/dependencies.json` (the suffix is
 * `.json`, not `.json.gz` — the object is stored with
 * `Content-Encoding: gzip` so consumers fetching the presigned GET
 * URL get transparent decompression at the HTTP layer).
 *
 * Field semantics (matches the appserver `DependencySchema` subdoc):
 *   - [group], [name], [version]: GAV coordinates. `version` may be
 *     null for project / file dependencies (loose .jar/.aar refs and
 *     in-repo Gradle modules don't carry an external version).
 *   - [direct]: `true` for entries declared directly in the variant's
 *     build script; `false` for transitives pulled in by another
 *     dependency. Derivable from `parents.isEmpty()` but retained as
 *     a denormalised flag — every downstream consumer (viewer table
 *     column, diff summary, worker filter) already reads it.
 *   - [scope]: Only meaningful (and only set) for direct entries —
 *     reflects the configuration the dependency was declared in
 *     (`api`, `implementation`, `runtimeOnly`, `compileOnly`).
 *     `null` for transitives.
 *   - [type]: `"library"` (Maven coordinates), `"project"` (in-repo
 *     `:module` reference), or `"file"` (raw .jar / .aar / file
 *     collection that doesn't carry GAV).
 *   - [selectedReason]: Gradle's conflict-resolution rationale
 *     (`forced`, `selected by rule`, `constraint`, …). Off by default
 *     via [BugseeDependenciesCollectionExtension.includeSelectedReason].
 *   - [id]: Stable identity string `"<type>:<group>:<name>"`. Same
 *     shape the viewer / worker already use as the diff key, surfaced
 *     here so the JSON blob is self-describing — consumers can match
 *     [parents] references without re-deriving the identity scheme.
 *   - [parents]: Immediate-parent IDs in the resolved graph. Empty
 *     for direct dependencies (the resolution root is never an entry).
 *     Lets a consumer (viewer, AI agent, …) reconstruct the dependency
 *     tree from the flat list. Truncation guarantees: every ID in
 *     [parents] points to an entry present in this blob — references
 *     to evicted entries are filtered out so the blob is
 *     self-consistent.
 */
data class DependencyEntry(
    val group: String,
    val name: String,
    val version: String?,
    val direct: Boolean,
    val scope: String?,
    val type: Type,
    val selectedReason: String? = null,
    val id: String = makeId(type, group, name),
    val parents: List<String> = emptyList()
) {
    enum class Type(val wire: String) {
        LIBRARY("library"),
        PROJECT("project"),
        FILE("file")
    }

    companion object {
        /**
         * Canonical identity string. Format must stay in sync with the
         * worker's `_make_identity` and the viewer's `identityOf` —
         * the diff machinery already keys on this shape.
         */
        fun makeId(type: Type, group: String, name: String): String =
            "${type.wire}:$group:$name"
    }
}


/**
 * Fingerprint of HOW the dependencies were collected. Sent inline
 * on `dependencies_summary.collection_config` so the worker / server
 * can compare two builds and decide whether the dependency lists are
 * apples-to-apples comparable.
 *
 * Comparability is gated on `scope` + `maxCount` (with `truncated`
 * from the parent summary): different scopes produce wildly different
 * sets; differing caps yield partial views. `includeSelectedReason`
 * affects per-entry data shape but NOT the (type, group, name)
 * identity set, so it's recorded for completeness but not
 * gated on.
 */
data class CollectionConfig(
    val scope: String,
    val includeSelectedReason: Boolean,
    val maxCount: Int
)


/**
 * Aggregate counts derived from a list of [DependencyEntry] — the
 * scalar shape sent inline in the build-info POST body so the viewer
 * can render summary chips without fetching the per-entry blob.
 *
 * Matches the appserver `BuildDependenciesSummarySchema`. `truncated`
 * is `true` when the producer-side `maxCount` cap kicked in and the
 * uploaded list is shorter than the resolved graph.
 *
 * `collectionConfig` is the comparability fingerprint — the worker
 * uses it to decide whether the current build's dep list can be
 * diffed against the previous build's. See [CollectionConfig].
 */
data class DependenciesSummary(
    val total: Int,
    val direct: Int,
    val transitive: Int,
    val byType: ByType,
    val truncated: Boolean,
    val collectedAtEpochMs: Long,
    val collectionConfig: CollectionConfig
) {
    data class ByType(
        val library: Int,
        val project: Int,
        val file: Int
    )

    companion object {
        fun from(entries: List<DependencyEntry>,
                 truncated: Boolean,
                 collectedAtEpochMs: Long,
                 collectionConfig: CollectionConfig): DependenciesSummary {
            var directCount = 0
            var library = 0
            var project = 0
            var file = 0
            for (e in entries) {
                if (e.direct) directCount++
                when (e.type) {
                    DependencyEntry.Type.LIBRARY -> library++
                    DependencyEntry.Type.PROJECT -> project++
                    DependencyEntry.Type.FILE -> file++
                }
            }
            return DependenciesSummary(
                total = entries.size,
                direct = directCount,
                transitive = entries.size - directCount,
                byType = ByType(library = library, project = project, file = file),
                truncated = truncated,
                collectedAtEpochMs = collectedAtEpochMs,
                collectionConfig = collectionConfig
            )
        }
    }
}
