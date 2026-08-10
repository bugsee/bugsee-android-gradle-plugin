package com.bugsee.android.gradle.instrumentation

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
 * Preferring `getPath()` also moves us toward Isolated Projects: the path is a plain string that
 * needs no access to another project's mutable model. Resolving it back to a [Project] still
 * does, so this narrows the incompatibility rather than removing it — the eventual fix is to
 * stop needing the other project at all and read the dependency graph via resolution APIs.
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
     * @param from the project whose graph is being walked; used to resolve the dependency path
     */
    fun targetProject(from: Project, dep: ProjectDependency): Project? {
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
