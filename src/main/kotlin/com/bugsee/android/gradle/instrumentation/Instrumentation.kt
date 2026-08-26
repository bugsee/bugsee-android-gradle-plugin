package com.bugsee.android.gradle.instrumentation

import com.android.build.api.variant.Variant
import org.gradle.api.Project

/**
 * Interface for bytecode instrumentations that can be applied to the build.
 *
 * Each instrumentation is gated by dependency detection: it only applies
 * if the relevant library dependency is found in the project.
 */
internal interface Instrumentation {
    /** Human-readable name for logging. */
    val name: String

    /** Machine-readable key used for gradle property and manifest meta-data lookups. */
    val key: String

    /**
     * Whether this instrumentation is governed by a non-boolean knob
     * (e.g. a tier enum) rather than the standard `isFeatureEnabled` boolean
     * gate. The registrar skips the boolean check for tier-driven entries
     * so that a typo'd `bugsee.instrumentation.<key>=garbage` Gradle
     * property does not produce a misleading "invalid boolean" warning and
     * silently disable the feature. Defaults to `false` (boolean-gated,
     * matching the long-standing behavior of every other instrumentation).
     */
    val isTierDriven: Boolean get() = false

    /**
     * Returns true if this instrumentation should be applied.
     *
     * @param coreSdkAutoLoad `true` when the plugin will auto-add the core
     *   `com.bugsee:bugsee-android` SDK (sdkAutoLoad enabled AND the consumer
     *   has not declared the core themselves). That auto-add happens in a later
     *   `withDependencies` pass, so at gating time the core is NOT yet a
     *   *declared* dependency the [DependencyDetector] can see. Instrumentations
     *   gated solely on the core SDK must therefore treat it as present when
     *   this is `true`; extension-gated instrumentations (OkHttp, Compose, …)
     *   ignore it and keep requiring their own dependency.
     * @param scope the configuration names feeding the RUNTIME classpath of
     *   the variant being instrumented. Implementations MUST forward it to
     *   [DependencyDetector.hasBugseeDependency]: without it a build-type- or
     *   flavor-scoped SDK dependency is seen by every variant, and a variant
     *   whose runtime classpath lacks the SDK gets instrumented against absent
     *   classes — a launch-time `NoClassDefFoundError`.
     */
    fun shouldApply(project: Project, coreSdkAutoLoad: Boolean, scope: Set<String>?): Boolean

    /**
     * Registers the ASM class visitor factory with the given variant.
     *
     * [excludes] is the user-configured set of class-name patterns to skip
     * (`bugsee { instrumentation { excludes.add(...) } }`); each
     * implementation forwards it into its instrumentation parameters so the
     * factory's `isInstrumentable` can honor it. May be empty.
     */
    fun apply(variant: Variant, excludes: Set<String>)
}
