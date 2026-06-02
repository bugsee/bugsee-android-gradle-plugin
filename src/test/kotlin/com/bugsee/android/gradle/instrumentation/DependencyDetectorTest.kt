package com.bugsee.android.gradle.instrumentation

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DependencyDetector] — in particular that `com.bugsee:bugsee-android`
 * is detected when reached **transitively through intermediate project modules**
 * (the Kotlin-Multiplatform / wrapper case: `:app` -> `:library` -> the SDK),
 * not only when declared directly. Before this, an app that depended on the SDK
 * only transitively had every Bugsee instrumentation silently skipped.
 */
class DependencyDetectorTest {

    private val root: Project = ProjectBuilder.builder().withName("root").build()

    private fun child(name: String): Project =
        ProjectBuilder.builder().withParent(root).withName(name).build()

    private fun Project.addExternal(coord: String) {
        val cfg = configurations.maybeCreate("implementation")
        dependencies.add(cfg.name, coord)
    }

    private fun Project.addProjectDep(other: Project) {
        val cfg = configurations.maybeCreate("implementation")
        dependencies.add(cfg.name, dependencies.project(mapOf("path" to other.path)))
    }

    @Test
    fun `direct external bugsee-android is detected`() {
        val app = child("app")
        app.addExternal("com.bugsee:bugsee-android:7.0.0")
        assertTrue(DependencyDetector.hasBugseeDependency(app, "bugsee-android"))
    }

    @Test
    fun `transitive bugsee-android through one intermediate project is detected`() {
        val app = child("app")
        val lib = child("lib")
        app.addProjectDep(lib)
        lib.addExternal("com.bugsee:bugsee-android:7.0.0")
        assertTrue(DependencyDetector.hasBugseeDependency(app, "bugsee-android"))
    }

    @Test
    fun `transitive through two intermediate projects is detected`() {
        val app = child("app")
        val mid = child("mid")
        val lib = child("lib")
        app.addProjectDep(mid)
        mid.addProjectDep(lib)
        lib.addExternal("com.bugsee:bugsee-android:7.0.0")
        assertTrue(DependencyDetector.hasBugseeDependency(app, "bugsee-android"))
    }

    @Test
    fun `absent bugsee returns false`() {
        val app = child("app")
        val lib = child("lib")
        app.addProjectDep(lib)
        lib.addExternal("com.squareup.okhttp3:okhttp:4.12.0")
        assertFalse(DependencyDetector.hasBugseeDependency(app, "bugsee-android"))
    }

    @Test
    fun `artifact prefix matching is honored`() {
        val app = child("app")
        app.addExternal("com.bugsee:bugsee-android-feedback:7.0.0")
        // feedback still matches the "bugsee-android" prefix...
        assertTrue(DependencyDetector.hasBugseeDependency(app, "bugsee-android"))
        // ...but an unrelated prefix does not.
        assertFalse(DependencyDetector.hasBugseeDependency(app, "bugsee-ios"))
    }

    @Test
    fun `project-dependency cycle does not stack overflow`() {
        val app = child("app")
        val lib = child("lib")
        app.addProjectDep(lib)
        lib.addProjectDep(app) // cycle
        // Must terminate and report false when no SDK is present anywhere.
        assertFalse(DependencyDetector.hasBugseeDependency(app, "bugsee-android"))
        // And still find the SDK once it's added, despite the cycle.
        lib.addExternal("com.bugsee:bugsee-android:7.0.0")
        assertTrue(DependencyDetector.hasBugseeDependency(app, "bugsee-android"))
    }

    @Test
    fun `version is found transitively`() {
        val app = child("app")
        val lib = child("lib")
        app.addProjectDep(lib)
        lib.addExternal("com.bugsee:bugsee-android:7.0.0-beta12")
        assertEquals("7.0.0-beta12", DependencyDetector.getBugseeDependencyVersion(app, "bugsee-android"))
    }

    @Test
    fun `version is null when SDK absent`() {
        val app = child("app")
        assertNull(DependencyDetector.getBugseeDependencyVersion(app, "bugsee-android"))
    }
}
