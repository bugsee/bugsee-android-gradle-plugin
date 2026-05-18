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

    /** Returns true if this instrumentation should be applied (dependency is present). */
    fun shouldApply(project: Project): Boolean

    /** Registers the ASM class visitor factory with the given variant. */
    fun apply(variant: Variant)
}
