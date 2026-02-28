package com.bugsee.android.gradle.instrumentation

import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Checks whether a Bugsee dependency is present in any of the project's configurations.
 *
 * Handles two scenarios:
 * - **External dependency**: matched by `com.bugsee` group and artifact name prefix
 *   (e.g. `com.bugsee:bugsee-android` or `com.bugsee:bugsee-android-beta`).
 * - **Project dependency**: matched by `GROUP=com.bugsee` Gradle property on the
 *   dependency project, optionally restricted to a specific module name.
 */
internal object DependencyDetector {

    private const val BUGSEE_GROUP = "com.bugsee"

    /**
     * Returns `true` if any configuration contains a dependency matching
     * the given [artifactPrefix] (for external deps) or [projectName] (for project deps).
     *
     * @param project        the project whose configurations to scan
     * @param artifactPrefix prefix for the maven artifact name (e.g. `"bugsee-android"`)
     * @param projectName    if non-null, only match project dependencies with this module name
     */
    fun hasBugseeDependency(
        project: Project,
        artifactPrefix: String,
        projectName: String? = null
    ): Boolean {
        return project.configurations.any { config ->
            config.dependencies.any { dep ->
                isMatchingExternal(dep, artifactPrefix) ||
                    isMatchingProject(dep, projectName)
            }
        }
    }

    private fun isMatchingExternal(dep: org.gradle.api.artifacts.Dependency, artifactPrefix: String): Boolean {
        return dep.group == BUGSEE_GROUP && dep.name.startsWith(artifactPrefix)
    }

    private fun isMatchingProject(dep: org.gradle.api.artifacts.Dependency, projectName: String?): Boolean {
        if (dep !is ProjectDependency) return false
        val depProject = dep.dependencyProject
        if (depProject.findProperty("GROUP") != BUGSEE_GROUP) return false
        return projectName == null || depProject.name == projectName
    }
}
