package com.bugsee.android.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Predicate
import java.util.function.Supplier

/**
 * Build-wide, in-memory state shared by every project that applies the plugin.
 *
 * This replaces flags and sets that used to live in the root project's extra properties. Reading
 * those from a subproject is a cross-project access, which Isolated Projects forbids; a build
 * service is the supported way to share state between projects. Nothing here is persisted or
 * handed to a task, so it has no configuration-cache footprint.
 *
 * **Only JDK types cross the service boundary.** When two modules load this plugin in different
 * classloaders (each declares the plugin with a version and one also applies another plugin), a
 * shared service registered by one module's copy of this class is handed to the other module's
 * copy. Casting it to [BugseeBuildState] then fails with a `ClassCastException`, so callers go
 * through [Predicate] and [Supplier] (shared JDK classes) and never touch the concrete type. The
 * state is therefore also deduplicated across such classloaders, as the old root-extras flags were.
 */
abstract class BugseeBuildState :
    BuildService<BuildServiceParameters.None>,
    Predicate<String>,
    Supplier<MutableSet<String>> {

    private val claimed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val variantNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** [Predicate] view of the claim: `true` for the first caller of a key in this build. */
    override fun test(key: String): Boolean = claimed.add(key)

    /** [Supplier] view of the names every application module has declared or produced. */
    override fun get(): MutableSet<String> = variantNames

    companion object {
        const val SERVICE_NAME = "bugseeBuildState"

        /** `true` for the first caller of [key] in this build, `false` for every later one. */
        fun claim(project: Project, key: String): Boolean = claimer(project).test(key)

        /** Names every application module has declared or produced; see `warnUnmatchedNames`. */
        fun knownVariantNames(project: Project): MutableSet<String> = namesSupplier(project).get()

        // The provider is read as `Provider<*>` and the instance used only as a JDK interface:
        // an `of(): BugseeBuildState` accessor would cast to this classloader's class.
        @Suppress("UNCHECKED_CAST")
        private fun claimer(project: Project): Predicate<String> = instance(project) as Predicate<String>

        @Suppress("UNCHECKED_CAST")
        private fun namesSupplier(project: Project): Supplier<MutableSet<String>> =
            instance(project) as Supplier<MutableSet<String>>

        private fun instance(project: Project): Any {
            val provider: Provider<*> =
                project.gradle.sharedServices.registerIfAbsent(SERVICE_NAME, BugseeBuildState::class.java) { }
            return provider.get() ?: error("Bugsee build state service is unavailable")
        }
    }
}
