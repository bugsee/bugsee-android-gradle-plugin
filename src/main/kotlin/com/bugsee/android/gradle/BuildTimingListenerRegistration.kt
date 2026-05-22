package com.bugsee.android.gradle

import com.bugsee.android.gradle.upload.BuildTimingService
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.build.event.BuildEventsListenerRegistry

/**
 * Single-flight flag set on the root project's extra-properties the
 * FIRST time the plugin's `apply()` runs in a given Gradle invocation.
 * Subsequent `apply()` calls (from other subprojects applying the
 * plugin) see the flag and skip re-registering the task-completion
 * listener — preventing N× event duplication in multi-module setups
 * where the plugin is applied to several subprojects.
 *
 * Lives in this top-level file (alongside
 * [registerBuildTimingListenerOnce]) rather than on
 * [BugseePlugin.Companion] so the test can reference it without
 * dragging the plugin class onto the unit-test classpath.
 */
internal const val BUILD_TIMING_LISTENER_FLAG_KEY: String =
    "bugseeBuildTimingListenerRegistered"

/**
 * Single-flight registration of the build-timings task-completion
 * listener.
 *
 * `BuildEventsListenerRegistry.onTaskCompletion(...)` does NOT dedupe —
 * every call from every subproject's `apply()` registers an independent
 * subscription, so a task-completion event would arrive once per
 * subproject the plugin is applied to and the service would record N
 * duplicate `TaskTiming` entries per task. The typical setup applies
 * the plugin to `:app` only, so this is rare in practice — but
 * defensive against multi-module setups (and the bugsee SDK's own
 * meta-build, where the plugin is applied to several library modules
 * simultaneously).
 *
 * Marks the Gradle invocation with a single-flight flag on the ROOT
 * project's extra-properties the FIRST call sets, so any later
 * subproject `apply()` observes the flag and skips re-registering the
 * listener (while still wiring through the same shared service).
 *
 * Lives in its own top-level file (rather than on the plugin's
 * companion object) so unit tests can call it without triggering class
 * loading of [BugseePlugin], whose `KotlinCompilerPluginSupportPlugin`
 * supertype is `compileOnly` and not on the test classpath.
 */
internal fun registerBuildTimingListenerOnce(
    project: Project,
    listenerRegistry: BuildEventsListenerRegistry,
    timingService: Provider<BuildTimingService>,
) {
    val rootExt = project.gradle.rootProject.extensions.extraProperties
    if (!rootExt.has(BUILD_TIMING_LISTENER_FLAG_KEY)) {
        rootExt.set(BUILD_TIMING_LISTENER_FLAG_KEY, true)
        listenerRegistry.onTaskCompletion(timingService)
    }
}
