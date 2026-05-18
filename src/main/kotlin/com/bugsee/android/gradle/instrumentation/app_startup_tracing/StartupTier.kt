package com.bugsee.android.gradle.instrumentation.app_startup_tracing

/**
 * Depth of app-startup bytecode tracing the Bugsee Gradle plugin will apply.
 *
 * Each tier bundles a **scope** (which classes are visible to the transform)
 * and a **depth** (how aggressively each instrumented method body is wrapped).
 * Higher tiers strictly contain lower tiers' behavior.
 *
 *  - [OFF] — instrumentation disabled. No bytecode rewriting.
 *  - [MINIMAL] — Application + ContentProvider init methods wrapped at the
 *    method level only (one start/end pair per `attachBaseContext`,
 *    `onCreate`, `attachInfo`).
 *  - [STANDARD] — adds AndroidX `Initializer.create`, Firebase
 *    `ComponentRegistrar.getComponents`, WorkManager
 *    `Configuration.Provider.getWorkManagerConfiguration` to the scope,
 *    and wraps every top-level `INVOKE*` call inside instrumented methods.
 *    **This is the default.**
 *  - [DETAILED] — additionally wraps top-level loops (via natural-loop
 *    detection) and wraps calls inside loop bodies. Pays the per-method
 *    `Analyzer<BasicValue>` cost for CFG / dominator computation.
 *  - [FULL] — additionally instruments methods annotated
 *    `@com.bugsee.library.contracts.performance.BugseeTrace` anywhere in
 *    the application or its dependencies (subject to the package denylist).
 *    Method-level wrap only — calls and loops inside annotated methods are
 *    not auto-wrapped.
 *
 * **Public DSL surface.** Configured from `build.gradle.kts`:
 * ```kotlin
 * import com.bugsee.android.gradle.instrumentation.app_startup_tracing.StartupTier
 *
 * bugsee {
 *     instrumentation {
 *         startupTier.set(StartupTier.FULL)
 *     }
 * }
 * ```
 *
 * Gradle-property and manifest-meta-data sources still pass the tier as
 * a String — those are parsed via [parse], which accepts case-insensitive
 * names. The DSL source is typed and cannot carry an invalid value.
 *
 * **Backwards-compat note.** This used to be `Property<String>` plus
 * runtime `StartupTier.parse(...)`. The enum-typed DSL is a breaking
 * change relative to the prior `Property<String>` shape — consumers
 * who wrote `startupTier.set("FULL")` must migrate to
 * `startupTier.set(StartupTier.FULL)` and add the import shown above.
 * The change ships in plugin `4.0.0-beta8`; earlier `4.0.0-beta7` and
 * below were unreleased so no public API contract was broken.
 *
 * **Stability contract.** This is a public DSL type. Once the plugin
 * ships a non-beta release, the following changes become binary-
 * incompatible breaks:
 *
 *  - Renaming or removing a value (`OFF`/`MINIMAL`/`STANDARD`/
 *    `DETAILED`/`FULL`) — consumer build scripts reference them by
 *    name.
 *  - **Reordering existing values.** Predicates such as
 *    [wrapsCalls]/[wrapsLoops]/[picksUpAnnotated] use `>=` against
 *    `Comparable` semantics, which keys off the declaration ordinal.
 *    A reorder silently changes which tier is "above" which —
 *    callers' expectations break with no compile-time signal.
 *    **Only ever APPEND new tiers** at the end (after `FULL`), and
 *    update the predicate ladder consciously when doing so.
 *  - Renaming any of the predicate methods or the [DEFAULT] companion
 *    constant.
 */
public enum class StartupTier {
    OFF,
    MINIMAL,
    STANDARD,
    DETAILED,
    FULL;

    /**
     * @return `true` if this tier wraps method bodies at all
     * (i.e. anything other than [OFF])
     */
    fun wrapsMethods(): Boolean = this != OFF

    /**
     * @return `true` if this tier wraps top-level method calls inside
     * instrumented method bodies
     */
    fun wrapsCalls(): Boolean = this >= STANDARD

    /**
     * @return `true` if this tier wraps top-level loops inside instrumented
     * method bodies
     */
    fun wrapsLoops(): Boolean = this >= DETAILED

    /**
     * @return `true` if this tier picks up `@BugseeTrace`-annotated methods
     * outside the built-in init-class scope
     */
    fun picksUpAnnotated(): Boolean = this >= FULL

    companion object {
        /** Default tier when no configuration source supplies a value. */
        val DEFAULT: StartupTier = STANDARD

        /**
         * Case-insensitive parse. Returns `null` for unknown values so the
         * caller can decide between throwing, warning, or falling back.
         */
        fun parse(name: String?): StartupTier? {
            if (name.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        }
    }
}
