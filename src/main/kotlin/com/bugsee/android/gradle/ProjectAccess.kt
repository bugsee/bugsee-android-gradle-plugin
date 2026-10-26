package com.bugsee.android.gradle

import org.gradle.api.Project
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.file.RegularFile
import java.util.Collections
import java.util.WeakHashMap
import javax.inject.Inject

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
     * Without Isolated Projects this is exactly `Project.findProperty` (own properties, then the
     * parent chain, so a root build script's `extra["..."]` is still inherited) - behaviour is
     * unchanged for every build that does not opt in. Under Isolated Projects the parent chain is
     * off limits, so it reads the project's own extra properties (which already include `-P`
     * and every `gradle.properties` level) and then [org.gradle.api.provider.ProviderFactory.gradleProperty].
     */
    fun gradleProperty(project: Project, name: String): String? {
        if (!isIsolatedProjects(project)) return project.findProperty(name)?.toString()
        val extra = project.extensions.extraProperties
        if (extra.has(name)) return extra.get(name)?.toString()
        return project.providers.gradleProperty(name).orNull
    }

    private val cache: MutableMap<Any, Boolean> = Collections.synchronizedMap(WeakHashMap())

    /**
     * Whether Isolated Projects is active for this build, read from Gradle's own
     * [BuildFeatures] (so `-Porg.gradle.unsafe.isolated-projects=true`, which Gradle ignores, does
     * not count). Falls back to `false` on a Gradle without that API.
     */
    fun isIsolatedProjects(project: Project): Boolean =
        cache.getOrPut(project.gradle) {
            runCatching { isActive(project.objects.newInstance(FeaturesHolder::class.java).features) }
                .getOrDefault(false)
        }

    /** The decision itself, separated so tests can drive it with a fake [BuildFeatures]. */
    internal fun isActive(features: BuildFeatures): Boolean =
        features.isolatedProjects.active.getOrElse(false)

    /** Gradle injects its [BuildFeatures] service into this when created via `ObjectFactory`. */
    internal abstract class FeaturesHolder {
        @get:Inject
        abstract val features: BuildFeatures
    }
}
