package com.bugsee.android.gradle

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
 *    `onCreate`, `attachInfo`). The AndroidX startup pass is invisible at
 *    this tier: `androidx.startup.InitializationProvider` is excluded
 *    by exact-FQN short-circuit (to avoid double-counting against
 *    individual Initializer spans at STANDARD+), and user-defined
 *    `Initializer.create()` methods are out of scope. Choose this tier
 *    when you want startup spans on YOUR own Application / ContentProvider
 *    only.
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
 * import com.bugsee.android.gradle.StartupTier
 *
 * bugsee {
 *     instrumentation {
 *         startupTier.set(StartupTier.FULL)
 *     }
 * }
 * ```
 *
 * Gradle-property and manifest-meta-data sources pass the tier as a
 * String — those are parsed internally and accept case-insensitive
 * names. The DSL source is typed and cannot carry an invalid value.
 *
 * **Stability contract.** This is a public DSL type. Once the plugin
 * ships a non-beta release, the following changes become binary-
 * incompatible breaks:
 *
 *  - Renaming or removing a value (`OFF`/`MINIMAL`/`STANDARD`/
 *    `DETAILED`/`FULL`) — consumer build scripts reference them by
 *    name.
 *  - **Reordering existing values.** Internal predicates use `>=`
 *    against `Comparable` semantics, which keys off the declaration
 *    ordinal. A reorder silently changes which tier is "above"
 *    which. **Only ever APPEND new tiers** at the end (after
 *    `FULL`), and update the predicate ladder consciously when
 *    doing so.
 *
 * **Package note.** This type sits at the plugin's top-level package
 * (`com.bugsee.android.gradle`) rather than under the implementation
 * sub-package, so DSL imports stay short:
 * `import com.bugsee.android.gradle.StartupTier`. The supporting
 * predicates ([wrapsMethods] / [wrapsCalls] / [wrapsLoops] /
 * [picksUpAnnotated]) and the [DEFAULT] companion are `internal` —
 * consumers never need to call them; they are factory-internal helpers
 * for deciding which wrap layer fires at which tier, and keeping them
 * out of the public API surface lets us refactor the predicate ladder
 * without a binary break.
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
    internal fun wrapsMethods(): Boolean = this != OFF

    /**
     * @return `true` if this tier wraps top-level method calls inside
     * instrumented method bodies
     */
    internal fun wrapsCalls(): Boolean = this >= STANDARD

    /**
     * @return `true` if this tier wraps top-level loops inside instrumented
     * method bodies
     */
    internal fun wrapsLoops(): Boolean = this >= DETAILED

    /**
     * @return `true` if this tier picks up `@BugseeTrace`-annotated methods
     * outside the built-in init-class scope
     */
    internal fun picksUpAnnotated(): Boolean = this >= FULL

    internal companion object {
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
