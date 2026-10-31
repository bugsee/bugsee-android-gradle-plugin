package com.bugsee.android.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Test double registered under the id `com.android.dynamic-feature` (see
 * `META-INF/gradle-plugins`), so unit tests can make a [org.gradle.testfixtures.ProjectBuilder]
 * project look like a dynamic-feature module without AGP's real plugin.
 */
class StubDynamicFeaturePlugin : Plugin<Project> {
    override fun apply(target: Project) = Unit
}
