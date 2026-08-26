package com.bugsee.android.gradle.manifest

import com.bugsee.android.gradle.instrumentation.extensions_init.ExtensionsInitClassVisitorFactory
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

    fun shouldStrip(
        optimizeExtensionsLoading: Boolean,
        instrumentationGloballyEnabled: Boolean,
        excludes: Set<String>,
    ): Boolean {
        if (!optimizeExtensionsLoading) return false
        if (!instrumentationGloballyEnabled) return false
        return !InstrumentationExcludes.isExcluded(
            ExtensionsInitClassVisitorFactory.TARGET_CLASS_FQN,
            excludes,
        )
    }
}
