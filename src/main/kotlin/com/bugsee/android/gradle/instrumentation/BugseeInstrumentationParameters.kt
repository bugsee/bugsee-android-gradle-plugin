package com.bugsee.android.gradle.instrumentation

import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input

/**
 * Shared instrumentation parameters carrying the target adapter class name.
 *
 * Each factory checks whether the target class actually exists on the classpath
 * (via [com.android.build.api.instrumentation.ClassContext.loadClassData]) before
 * applying any bytecode transformations. This prevents runtime crashes when the
 * Bugsee SDK version does not include the expected adapter class.
 */
internal interface BugseeInstrumentationParameters : InstrumentationParameters {

    /** Fully-qualified (dot-separated) class name of the adapter that must exist. */
    @get:Input
    val targetClass: Property<String>

    /**
     * Class-name patterns the user opted OUT of instrumentation
     * (`bugsee { instrumentation { excludes.add(...) } }`). Consulted in
     * each factory's `isInstrumentable` via
     * [com.bugsee.android.gradle.instrumentation.util.InstrumentationExcludes].
     */
    @get:Input
    val excludes: SetProperty<String>
}
