package com.bugsee.android.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class BugseeSizeAnalysisExtension @Inject constructor(objects: ObjectFactory) {

    /** Enable size analysis upload. Disabled by default (opt-in). */
    val enabled: Property<Boolean> = objects.property(Boolean::class.javaObjectType).convention(false)

    /**
     * Override the build configuration label used for comparison grouping.
     * Defaults to the Gradle variant name (e.g. "release", "freeRelease").
     */
    val buildConfiguration: Property<String> = objects.property(String::class.java)
}
