package com.bugsee.android.gradle.instrumentation

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency

/**
 * Checks whether a Bugsee dependency is present in a project's configurations,
 * including dependencies reached **transitively through intermediate project
 * modules**.
 *
 * Handles three scenarios:
 * - **External dependency**: matched by `com.bugsee` group and artifact name prefix
 *   (e.g. `com.bugsee:bugsee-android` or `com.bugsee:bugsee-android-beta`).
 * - **Project dependency**: matched by `GROUP=com.bugsee` Gradle property on the
 *   dependency project, optionally restricted to a specific module name.
 * - **Transitive via an intermediate module**: the SDK is frequently consumed
 *   through a wrapper/aggregator project (e.g. a Kotlin Multiplatform `:library`
 *   module that itself declares `com.bugsee:bugsee-android`). Detection recurses
 *   into project dependencies so an app that only *transitively* depends on the
 *   SDK is still recognized. Without this, the plugin's instrumentations (and
 *   the extension-init bytecode pass) silently disable themselves for such apps.
 *
 * Only **declared** dependencies are inspected (no configuration resolution), so
 * this is safe to call at configuration time. Recursion is cycle-guarded by
 * project path.
 */
internal object DependencyDetector {

    private const val BUGSEE_GROUP = "com.bugsee"

    /**
     * Returns `true` if [project] — or any project it depends on, transitively —
     * declares a dependency matching the given [artifactPrefix] (external deps)
     * or [projectName] (Bugsee project deps).
     *
     * @param project        the project whose dependency graph to scan
     * @param artifactPrefix prefix for the maven artifact name (e.g. `"bugsee-android"`)
     * @param projectName    if non-null, only match Bugsee project dependencies with this module name
     */
    fun hasBugseeDependency(
        project: Project,
        artifactPrefix: String,
        projectName: String? = null
    ): Boolean = hasBugseeDependency(project, artifactPrefix, projectName, HashSet())

    private fun hasBugseeDependency(
        project: Project,
        artifactPrefix: String,
        projectName: String?,
        visited: MutableSet<String>
    ): Boolean {
        // Cycle guard: project graphs can contain cycles in test/edge setups.
        if (!visited.add(project.path)) {
            return false
        }
        return project.configurations.any { config ->
            config.dependencies.any { dep ->
                when {
                    isMatchingExternal(dep, artifactPrefix) -> true
                    isMatchingProject(dep, projectName) -> true
                    // Recurse through intermediate project modules: the external
                    // SDK dep may be declared by a wrapper/aggregator project that
                    // this project depends on (e.g. KMP `:composeApp` -> `:library`
                    // -> com.bugsee:bugsee-android).
                    dep is ProjectDependency ->
                        hasBugseeDependency(evaluated(project, dep.dependencyProject), artifactPrefix, projectName, visited)
                    else -> false
                }
            }
        }
    }

    /**
     * Returns [dependencyProject] after ensuring it has been evaluated.
     *
     * Cross-project dependency inspection runs during the consuming project's
     * configuration, at which point an intermediate module may not have been
     * configured yet — its `configurations` container would then be empty and
     * its declared SDK dependency invisible. [Project.evaluationDependsOn] forces
     * the target project to evaluate first. Failures (evaluation cycles, test
     * fixtures without a real build) fall back to the project as-is.
     */
    private fun evaluated(from: Project, dependencyProject: Project): Project {
        return try {
            from.evaluationDependsOn(dependencyProject.path)
        } catch (t: Throwable) {
            dependencyProject
        }
    }

    /**
     * Returns the declared version string of the matching external Bugsee
     * dependency, or `null` if no match is found or no version is declared
     * (project deps, dynamic versions without a string form).
     *
     * Scans this project's configurations first, then recurses into project
     * dependencies (so the version is found even when the external SDK dep is
     * declared by an intermediate module). For external deps the version is
     * [org.gradle.api.artifacts.Dependency.getVersion].
     *
     * <p>Used by tier-driven instrumentation to bail out early when
     * the runtime SDK is older than the minimum version that ships
     * the injected dispatcher symbols.
     */
    fun getBugseeDependencyVersion(
        project: Project,
        artifactPrefix: String,
    ): String? = getBugseeDependencyVersion(project, artifactPrefix, HashSet())

    private fun getBugseeDependencyVersion(
        project: Project,
        artifactPrefix: String,
        visited: MutableSet<String>
    ): String? {
        if (!visited.add(project.path)) {
            return null
        }
        // Prefer a version declared directly on this project.
        for (config in project.configurations) {
            for (dep in config.dependencies) {
                if (isMatchingExternal(dep, artifactPrefix)) {
                    val v = dep.version
                    if (!v.isNullOrBlank()) return v
                }
            }
        }
        // Otherwise, look through intermediate project modules.
        for (config in project.configurations) {
            for (dep in config.dependencies) {
                if (dep is ProjectDependency) {
                    val v = getBugseeDependencyVersion(evaluated(project, dep.dependencyProject), artifactPrefix, visited)
                    if (v != null) return v
                }
            }
        }
        return null
    }

    private fun isMatchingExternal(dep: Dependency, artifactPrefix: String): Boolean {
        return dep.group == BUGSEE_GROUP && dep.name.startsWith(artifactPrefix)
    }

    private fun isMatchingProject(dep: Dependency, projectName: String?): Boolean {
        if (dep !is ProjectDependency) return false
        val depProject = dep.dependencyProject
        if (depProject.findProperty("GROUP") != BUGSEE_GROUP) return false
        return projectName == null || depProject.name == projectName
    }
}
