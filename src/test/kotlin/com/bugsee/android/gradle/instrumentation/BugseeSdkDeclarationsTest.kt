package com.bugsee.android.gradle.instrumentation

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #100: [BugseeSdkDeclarations.declaredCoreSdkVersion] is the single resolver behind the
 * core auto-add and the SDK-version gates. Includes the cases where the old
 * `DependencyDetector.getBugseeDependencyVersion` disagreed with it.
 */
class BugseeSdkDeclarationsTest {

    private val root: Project = ProjectBuilder.builder().withName("root").build()
    private val app: Project = ProjectBuilder.builder().withParent(root).withName("app").build()
    private val lib: Project = ProjectBuilder.builder().withParent(root).withName("lib").build()

    private fun Project.declare(config: String, coord: String) {
        dependencies.add(configurations.maybeCreate(config).name, coord)
    }

    private fun dependOnLib() {
        app.dependencies.add(
            app.configurations.maybeCreate("implementation").name,
            app.dependencies.project(mapOf("path" to lib.path))
        )
    }

    @Test fun `nothing declared is null`() {
        assertNull(BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `several app pins resolve to the highest regardless of configuration order`() {
        app.declare("a", "com.bugsee:bugsee-android:6.5.0")
        app.declare("b", "com.bugsee:bugsee-android:7.0.0")
        assertEquals("7.0.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `project-dependency pin is used when the app declares nothing`() {
        lib.declare("implementation", "com.bugsee:bugsee-android:7.0.0")
        dependOnLib()
        assertEquals("7.0.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `app core pin wins over a project-dependency pin`() {
        lib.declare("implementation", "com.bugsee:bugsee-android:7.5.0")
        dependOnLib()
        app.declare("other", "com.bugsee:bugsee-android:7.0.0")
        assertEquals("7.0.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `debug-only edge in a dependency project is ignored`() {
        lib.declare("debugImplementation", "com.bugsee:bugsee-android:6.5.0")
        dependOnLib()
        assertNull(BugseeSdkDeclarations.declaredCoreSdkVersion(app))
        lib.declare("implementation", "com.bugsee:bugsee-android:7.0.0")
        assertEquals("7.0.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `extension pin is the fallback when no core is declared and never beats a core`() {
        app.declare("a", "com.bugsee:bugsee-android-okhttp:7.1.0")
        assertEquals("7.1.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
        app.declare("b", "com.bugsee:bugsee-android:7.0.0")
        assertEquals("7.0.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `plugin artifact is not an SDK module and ignored deps do not count`() {
        app.declare("a", "com.bugsee:bugsee-android-gradle-plugin:4.0.0")
        assertNull(BugseeSdkDeclarations.declaredCoreSdkVersion(app))
        app.declare("b", "com.bugsee:bugsee-android:7.0.0")
        val added = app.configurations.getByName("b").dependencies.toSet()
        assertNull(BugseeSdkDeclarations.declaredCoreSdkVersion(app, ignore = added))
    }
}
