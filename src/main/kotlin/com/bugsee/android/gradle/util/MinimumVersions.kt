package com.bugsee.android.gradle.util

import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * Enforces the documented minimum AGP and Gradle versions (see README) with a readable message,
 * instead of a linkage error from deep inside the plugin on an older toolchain.
 */
internal object MinimumVersions {

    const val MIN_AGP = "8.6.0"
    const val MIN_GRADLE = "8.7"
    const val REQUIREMENTS_URL = "https://docs.bugsee.com/sdk/android/gradle-plugin/requirements"

    /**
     * Throws ONE [GradleException] listing every unmet minimum when [gradle] or [agp] is below it.
     * [agp] may be null when it could not be determined (not checked). Pre-release and nightly
     * suffixes (`-beta01`, `-milestone-1`, `-20240115010203+0000`) are ignored: `8.7-2024...`
     * counts as 8.7.
     */
    fun check(agp: String?, gradle: String) {
        val problems = mutableListOf<String>()
        if (compare(gradle, MIN_GRADLE) < 0) {
            problems += "Gradle $MIN_GRADLE or newer (found $gradle)"
        }
        if (agp != null && compare(agp, MIN_AGP) < 0) {
            problems += "Android Gradle Plugin (AGP) $MIN_AGP or newer (found $agp)"
        }
        if (problems.isEmpty()) return
        throw GradleException(
            "Bugsee plugin requires " + problems.joinToString(" and ") + ". " +
                "Upgrade the toolchain or use an older Bugsee plugin release. " +
                "See $REQUIREMENTS_URL"
        )
    }

    /** Compares the numeric dotted prefixes of two versions; missing components count as 0. */
    internal fun compare(a: String, b: String): Int {
        val x = numericParts(a)
        val y = numericParts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun numericParts(v: String): List<Int> =
        Regex("""^\d+(\.\d+)*""").find(v.trim())?.value?.split('.')?.map { it.toInt() } ?: emptyList()

    /** Runs [check] for [project]'s Gradle and (if the android extension is present) AGP. */
    fun checkProject(project: Project) {
        val android = project.extensions.findByName("android")
        val agp = if (android != null) detectAgpVersion(android.javaClass.classLoader) else null
        if (android != null && agp == null) {
            project.logger.info("Bugsee: could not determine the AGP version; skipping the AGP minimum check")
        }
        check(agp, project.gradle.gradleVersion)
    }

    private val VERSION_CLASSES = listOf("com.android.Version", "com.android.builder.model.Version")

    /**
     * AGP's own version constant, read reflectively so it works on every AGP (current and legacy
     * class location). [loader] should be AGP's classloader; null if unavailable.
     */
    fun detectAgpVersion(loader: ClassLoader? = MinimumVersions::class.java.classLoader): String? {
        for (name in VERSION_CLASSES) {
            try {
                val v = Class.forName(name, true, loader).getField("ANDROID_GRADLE_PLUGIN_VERSION").get(null) as? String
                if (v != null) return v
            } catch (_: ReflectiveOperationException) {
            } catch (_: LinkageError) {
            }
        }
        return null
    }
}
