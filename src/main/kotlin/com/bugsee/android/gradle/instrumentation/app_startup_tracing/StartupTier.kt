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
 * Parsed from the DSL / Gradle property / manifest meta-data via
 * [StartupTier.parse], which accepts case-insensitive names and falls back
 * to [STANDARD] (with a warning) for unknown values.
 */
internal enum class StartupTier {
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
