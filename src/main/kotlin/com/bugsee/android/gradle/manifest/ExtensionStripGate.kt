package com.bugsee.android.gradle.manifest

import com.bugsee.android.gradle.instrumentation.extensions_init.ExtensionsInitClassVisitorFactory
import com.bugsee.android.gradle.instrumentation.BugseeSdkVersion
import com.bugsee.android.gradle.instrumentation.util.InstrumentationExcludes

/**
 * Single decision point for whether [BugseeManifestTask] may strip Bugsee
 * extension `<provider>` entries from the merged manifest.
 *
 * Stripping is only sound when the compensating bytecode injection
 * (`ExtensionsInitInstrumentation` inlining `register<Name>Extension()` calls
 * into `BugseeInitProvider.initializeExtensions()`) will actually run.
 * Historically the strip keyed ONLY on the `optimizeExtensionsLoading` DSL
 * flag, while the injection additionally required:
 *
 *  1. bytecode instrumentation being globally enabled
 *     (`bugsee.instrumentation.enabled` via DSL / Gradle property / manifest
 *     meta-data — e.g. `-Pbugsee.instrumentation.enabled=false`, the natural
 *     switch for keeping instrumented code away from host-app unit tests);
 *  2. the injection target class not matching the user's
 *     `instrumentation.excludes` (which `InstrumentationException`'s help
 *     text explicitly tells a blocked consumer to add).
 *
 * Under either mismatch the build stripped the providers AND skipped the
 * injection — every Bugsee extension (NDK crash reporting, feedback, compose,
 * remoting, …) silently never registered at runtime. This gate makes the
 * strip follow the injection's own preconditions.
 */
internal object ExtensionStripGate {

    /**
     * The SDK release that introduced `BugseeInitProvider.initializeExtensions()`,
     * the method the compensating injection rewrites. Paired with anything older
     * the injection silently matches nothing, so the strip must not run either.
     */
    val MIN_SDK_VERSION_WITH_EXTENSIONS_INIT = BugseeSdkVersion(
        major = 7,
        minor = 0,
        patch = 0,
        preLabel = "beta",
        preNumber = 11,
    )

    /**
     * @param sdkVersion the consumer's declared `com.bugsee:bugsee-android` version, or
     *   `null` when it could not be parsed (project dependency, `7.+`, an unresolved
     *   version catalog). An unknown version does NOT disarm the strip: refusing on
     *   anything unparseable would disable the optimisation for everyone who does not
     *   pin a literal version, which is a worse outcome than the narrow old-SDK
     *   pairing this guards. Same deliberate trade-off as the app-startup lane.
     */
    fun shouldStrip(
        optimizeExtensionsLoading: Boolean,
        instrumentationGloballyEnabled: Boolean,
        excludes: Set<String>,
        sdkVersion: BugseeSdkVersion?,
    ): Boolean {
        if (!optimizeExtensionsLoading) return false
        if (!instrumentationGloballyEnabled) return false
        if (sdkVersion != null && sdkVersion < MIN_SDK_VERSION_WITH_EXTENSIONS_INIT) return false
        return !InstrumentationExcludes.isExcluded(
            ExtensionsInitClassVisitorFactory.TARGET_CLASS_FQN,
            excludes,
        )
    }
}
