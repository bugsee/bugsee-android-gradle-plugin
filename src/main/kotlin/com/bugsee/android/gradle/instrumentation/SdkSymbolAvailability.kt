package com.bugsee.android.gradle.instrumentation

import com.android.build.api.variant.Variant
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Provider

/**
 * Wires [SdkClassProbe] to a variant's resolved classpath, yielding "may this lane inject?".
 *
 * Every instrumentation emits an `INVOKESTATIC` naming an SDK class by string. That call lands in
 * the HOST app's own bytecode, so if the resolved SDK does not carry the class there is no SDK
 * code path left to absorb the failure — a debug build ships a dangling reference and dies with
 * `NoClassDefFoundError` when the site executes, and a minified build fails at R8 with
 * "Missing class" (unless shrinking happens to remove the caller first).
 *
 * Plugin and SDK are versioned and released independently, so that pairing is not hypothetical.
 * Asking the artifact directly — rather than inferring from a declared version string — is the
 * only check that holds for Gradle ranges, platform-managed versions, project and composite
 * dependencies, and for a class that exists in source but was stripped from the published AAR.
 *
 * Resolution is lazy: `resolvedArtifacts` is a Gradle provider, so the classpath resolves when the
 * transform executes rather than during configuration.
 */
internal object SdkSymbolAvailability {

    /**
     * @param fqn dot-separated class name the lane injects, e.g.
     *        `com.bugsee.library.adapters.BugseeLogAdapter`
     * @param feature human-readable capability name used in the skip warning
     */
    fun of(
        variant: Variant,
        fqn: String,
        feature: String,
        logger: Logger,
    ): Provider<Boolean> =
        variant.runtimeConfiguration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            val present = SdkClassProbe.containsClass(artifacts.map { it.file }, fqn.replace('.', '/'))
            if (!present) {
                logger.warn(
                    "Bugsee gradle plugin: the resolved Bugsee SDK does not contain $fqn, " +
                        "so $feature instrumentation is unavailable and will be skipped. " +
                        "Upgrade the Bugsee SDK to enable it."
                )
            }
            present
        }
}
