package com.bugsee.android.gradle.instrumentation.extensions_init

import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input

/**
 * Parameters for [ExtensionsInitClassVisitorFactory].
 *
 * The bytecode visitor needs the Bugsee extension init-provider FQNs that
 * [com.bugsee.android.gradle.manifest.BugseeManifestTask] strips from the merged manifest.
 */
internal interface ExtensionsInitParameters : InstrumentationParameters {

    /**
     * Init-provider FQNs of the extension modules on the variant's runtime classpath
     * ([ExtensionsOnClasspath]); the visitor registers exactly these. An empty set leaves the
     * `initializeExtensions()` body untouched.
     *
     * An `@Input` value, not a file written by another task: AGP may run the transform before any task
     * of this build (see [ExtensionsOnClasspath]).
     */
    @get:Input
    val providers: SetProperty<String>

    /**
     * Class-name patterns the user opted OUT of instrumentation
     * (`bugsee { instrumentation { excludes.add(...) } }`). Consulted in
     * `isInstrumentable` via
     * [com.bugsee.android.gradle.instrumentation.util.InstrumentationExcludes].
     */
    @get:Input
    val excludes: SetProperty<String>
}
