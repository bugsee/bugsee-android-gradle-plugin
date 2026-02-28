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

    /** Returns true if this instrumentation should be applied (dependency is present). */
    fun shouldApply(project: Project): Boolean

    /** Registers the ASM class visitor factory with the given variant. */
    fun apply(variant: Variant)
}
