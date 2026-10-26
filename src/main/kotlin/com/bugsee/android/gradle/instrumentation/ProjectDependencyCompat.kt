package com.bugsee.android.gradle.instrumentation

import com.bugsee.android.gradle.ProjectAccess
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Resolves the target [Project] of a [ProjectDependency] across the Gradle versions this plugin
 * supports.
 *
 * There is no single API that works everywhere, which is why this is reflective in BOTH
 * directions rather than a straight call:
 *
 *  - `getDependencyProject()` existed forever, was deprecated in Gradle 8.11, and was **removed
 *    in Gradle 9.0**. Calling it on Gradle 9 throws `NoSuchMethodError` at configuration time —
 *    which is what a consumer sees as "Failed to notify project evaluation listener".
 *  - `getPath()` is the replacement, but it was only **added in Gradle 8.11**. The plugin
 *    compiles against an older `gradleApi()`, so it cannot be referenced statically without
 *    raising the floor and dropping the older Gradle versions we still support.
 *
 * Resolving a path back to a [Project] needs another project's mutable model, which Isolated
 * Projects forbids, so that mode short-circuits to `null` (see [targetProject]).
 *
 * Both lookups are cached: this runs inside a loop over every dependency of every configuration.
 */
internal object ProjectDependencyCompat {

    /** Gradle >= 8.11. Null when running on an older Gradle. */
    private val getPathMethod by lazy {
        runCatching { ProjectDependency::class.java.getMethod("getPath") }.getOrNull()
    }

    /** Gradle < 9.0. Null once removed. */
    private val getDependencyProjectMethod by lazy {
        runCatching { ProjectDependency::class.java.getMethod("getDependencyProject") }.getOrNull()
    }

    /**
     * The project [dep] points at, or `null` if it cannot be resolved on this Gradle version.
     *
     * Always `null` under Isolated Projects: another project's model (its configurations, its
     * `GROUP` property, `evaluationDependsOn`) is off limits there. Callers treat `null` as "not
     * detected", so detection then covers only the module being configured - an SDK declared by a
     * sibling module, or an SDK source project dependency, is not seen.
     *
     * @param from the project whose graph is being walked; used to resolve the dependency path
     */
    fun targetProject(from: Project, dep: ProjectDependency): Project? =
        targetProject(from, dep, ProjectAccess.isIsolatedProjects(from))

    /** [targetProject] with the Isolated Projects decision supplied, for tests. */
    internal fun targetProject(from: Project, dep: ProjectDependency, isolatedProjects: Boolean): Project? {
        if (isolatedProjects) return null
        getPathMethod?.let { method ->
            val path = runCatching { method.invoke(dep) as? String }.getOrNull()
            if (path != null) {
                // getPath() returns an absolute project path (":lib"), which findProject
                // resolves from any project in the build.
                return from.rootProject.findProject(path)
            }
        }
        return getDependencyProjectMethod?.let { method ->
            runCatching { method.invoke(dep) as? Project }.getOrNull()
        }
    }
}

/**
 * Once per module, tells the user that Isolated Projects stopped the plugin from looking into
 * this module's project dependencies, when that can matter: [what] describes the lookup that
 * came up empty (for example "the core Bugsee SDK was not found"). Silent when the mode is off
 * or the module has no project dependencies.
 */
internal fun warnIfProjectDependenciesNotInspected(project: Project, what: String) {
    if (!ProjectAccess.isIsolatedProjects(project)) return
    val hasProjectDeps = project.configurations.any { config -> config.dependencies.any { it is ProjectDependency } }
    if (!hasProjectDeps) return
    if (!com.bugsee.android.gradle.BugseeBuildState.claim(project, "bugsee.isolatedProjectsSkip:${project.path}")) return
    project.logger.warn(
        "Bugsee: Isolated Projects is enabled, so the plugin does not inspect other modules, and " +
            "$what in ${project.displayName} itself, so SDK-dependent instrumentation is skipped. " +
            "If the Bugsee SDK is declared only in a module this one depends on (project(\":...\")), " +
            "declare com.bugsee:bugsee-android directly in this module. " +
            "See the README section on Isolated Projects."
    )
}
