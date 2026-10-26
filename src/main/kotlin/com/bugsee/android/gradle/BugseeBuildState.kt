package com.bugsee.android.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ConcurrentHashMap

/**
 * Build-wide, in-memory state shared by every project that applies the plugin.
 *
 * This replaces flags and sets that used to live in the root project's extra properties. Reading
 * those from a subproject is a cross-project access, which Isolated Projects forbids; a build
 * service is the supported way to share state between projects. Nothing here is persisted or
 * handed to a task, so it has no configuration-cache footprint.
 */
abstract class BugseeBuildState : BuildService<BuildServiceParameters.None> {

    private val claimed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val variantNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** `true` for the first caller of [key] in this build, `false` for every later one. */
    fun claim(key: String): Boolean = claimed.add(key)

    /** Names every application module has declared or produced; see `warnUnmatchedNames`. */
    fun knownVariantNames(): MutableSet<String> = variantNames

    companion object {
        const val SERVICE_NAME = "bugseeBuildState"

        fun of(project: Project): BugseeBuildState = provider(project).get()

        fun provider(project: Project): Provider<BugseeBuildState> =
            project.gradle.sharedServices.registerIfAbsent(SERVICE_NAME, BugseeBuildState::class.java) { }
    }
}
