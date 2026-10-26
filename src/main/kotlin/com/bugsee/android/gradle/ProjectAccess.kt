package com.bugsee.android.gradle

import org.gradle.api.Project
import org.gradle.api.file.RegularFile

/**
 * Project-model access that stays inside the project being configured, so it is safe under
 * Gradle's Isolated Projects mode (`org.gradle.unsafe.isolated-projects=true`).
 *
 * The plugin used to reach the root project (`rootProject.layout`, `rootProject.extensions`) and
 * rely on `Project.findProperty`'s fall-through to parent projects. Isolated Projects reports each
 * of those as a cross-project access. Everything here reads only from [Project] itself, from
 * `providers`, or from build-wide shared services.
 */
internal object ProjectAccess {

    /**
     * `<rootProject>/<name>` as a [RegularFile], without touching the root project object.
     * `Project.getRootDir()` is a plain build-level value, not another project's model.
     */
    fun rootFile(project: Project, name: String): RegularFile =
        project.layout.projectDirectory.file(project.rootDir.resolve(name).absolutePath)

    /**
     * The value of Gradle property [name] as seen from [project], or `null`.
     *
     * Order: the project's own extra properties, then Gradle properties (`-P`, `gradle.properties`
     * in the root, the user home or the project directory, via [org.gradle.api.provider.ProviderFactory.gradleProperty]).
     * Unlike `Project.findProperty` this never walks up to a parent project.
     */
    fun gradleProperty(project: Project, name: String): String? {
        val extra = project.extensions.extraProperties
        if (extra.has(name)) return extra.get(name)?.toString()
        return project.providers.gradleProperty(name).orNull
    }

    /**
     * Whether Isolated Projects is enabled for this build. Gradle exposes no public API for it, so
     * this reads the switch itself (`-D` or `gradle.properties`, current and legacy spelling).
     */
    fun isIsolatedProjects(project: Project): Boolean =
        ISOLATED_PROJECTS_KEYS.any { key ->
            project.providers.systemProperty(key).orNull.equals("true", ignoreCase = true) ||
                project.providers.gradleProperty(key).orNull.equals("true", ignoreCase = true)
        }

    private val ISOLATED_PROJECTS_KEYS = listOf(
        "org.gradle.unsafe.isolated-projects",
        "org.gradle.isolated-projects",
    )
}
