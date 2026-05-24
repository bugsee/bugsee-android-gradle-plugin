package com.bugsee.android.gradle.instrumentation.extensions_init

import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/**
 * Parameters for [ExtensionsInitClassVisitorFactory].
 *
 * The bytecode visitor needs the list of Bugsee extension init-provider
 * FQNs that were stripped from the merged manifest by
 * [com.bugsee.android.gradle.manifest.BugseeManifestTask]. Wiring it as a
 * [RegularFileProperty] (rather than a `ListProperty<String>`) creates the
 * implicit task dependency: AGP will sequence the manifest task before
 * the class-transformation phase.
 */
internal interface ExtensionsInitParameters : InstrumentationParameters {

    /**
     * Text file produced by the manifest task. One extension init-provider
     * FQN per line (e.g. `com.bugsee.library.BugseeFeedbackInitProvider`).
     * Empty file means no extensions detected — visitor leaves the
     * `initializeExtensions()` body untouched.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val detectedExtensionsFile: RegularFileProperty
}
