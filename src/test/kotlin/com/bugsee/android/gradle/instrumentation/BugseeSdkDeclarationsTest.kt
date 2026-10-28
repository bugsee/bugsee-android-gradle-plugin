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

    private fun named(n: String): Any = java.lang.reflect.Proxy.newProxyInstance(
        org.gradle.api.Named::class.java.classLoader, arrayOf(org.gradle.api.Named::class.java),
    ) { _, m, _ -> if (m.name == "getName") n else null }

    private fun container(vararg names: String): Any = java.lang.reflect.Proxy.newProxyInstance(
        org.gradle.api.NamedDomainObjectContainer::class.java.classLoader,
        arrayOf(org.gradle.api.NamedDomainObjectContainer::class.java),
    ) { _, m, _ -> if (m.name == "iterator") names.map { named(it) }.iterator() else null }

    private fun Project.declareFlavors(vararg flavors: String) {
        extensions.add(
            com.android.build.api.dsl.CommonExtension::class.java, "android",
            java.lang.reflect.Proxy.newProxyInstance(
                com.android.build.api.dsl.CommonExtension::class.java.classLoader,
                arrayOf(com.android.build.api.dsl.CommonExtension::class.java),
            ) { _, m, _ ->
                when (m.name) {
                    "getBuildTypes" -> container("debug", "release")
                    "getProductFlavors" -> container(*flavors)
                    else -> null
                }
            } as com.android.build.api.dsl.CommonExtension<*, *, *, *, *, *>,
        )
    }

    @Test fun `app flavored pins are read using the declared flavors (#114)`() {
        app.declare("freeDebugImplementation", "com.bugsee:bugsee-android:6.5.0")
        app.declare("latestTestImplementation", "com.bugsee:bugsee-android:7.0.0")
        // Undeclared flavors: latestTest reads as a test configuration.
        assertEquals("6.5.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
        app.declareFlavors("free", "latestTest")
        assertEquals("7.0.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
        // AGP's unit-test configuration of that flavor stays test.
        app.declare("testLatestTestImplementation", "com.bugsee:bugsee-android:7.9.0")
        assertEquals("7.0.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `library flavor ending in Test word is read when declared in its DSL`() {
        lib.declare("latestTestApi", "com.bugsee:bugsee-android:7.0.0")
        lib.declare("latestTestDebugApi", "com.bugsee:bugsee-android:6.5.0")
        dependOnLib()
        // Undeclared: the name rule says test, so the pin is invisible.
        assertNull(BugseeSdkDeclarations.declaredCoreSdkVersion(app))
        lib.declareFlavors("latestTest")
        // 7.0.0 from latestTestApi; the debug-variant 6.5.0 stays excluded for declarations.
        assertEquals("7.0.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `nothing declared is null`() {
        assertNull(BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `several app pins resolve to the highest regardless of configuration order`() {
        app.declare("implementation", "com.bugsee:bugsee-android:6.5.0")
        app.declare("api", "com.bugsee:bugsee-android:7.0.0")
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
        app.declare("api", "com.bugsee:bugsee-android:7.0.0")
        assertEquals("7.0.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `debug-only edge in a dependency project is ignored`() {
        lib.declare("debugImplementation", "com.bugsee:bugsee-android:6.5.0")
        dependOnLib()
        assertNull(BugseeSdkDeclarations.declaredCoreSdkVersion(app))
        lib.declare("implementation", "com.bugsee:bugsee-android:7.0.0")
        assertEquals("7.0.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `test and compileOnly pins on the app never beat a production pin (#114)`() {
        app.declare("implementation", "com.bugsee:bugsee-android:6.5.0")
        app.declare("testImplementation", "com.bugsee:bugsee-android:7.5.0")
        app.declare("androidTestImplementation", "com.bugsee:bugsee-android:7.6.0")
        app.declare("compileOnly", "com.bugsee:bugsee-android:7.7.0")
        assertEquals("6.5.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `test-only app pin resolves to null, compileOnly-only core pin is read (#130)`() {
        app.declare("testImplementation", "com.bugsee:bugsee-android:7.5.0")
        assertNull(BugseeSdkDeclarations.declaredCoreSdkVersion(app))
        app.declare("compileOnly", "com.bugsee:bugsee-android:7.7.0")
        assertEquals("7.7.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `compileOnly core pin below the floor is used as-is and variants and Api count (#130)`() {
        app.declare("debugCompileOnly", "com.bugsee:bugsee-android:6.5.0")
        assertEquals("6.5.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
        app.declare("compileOnlyApi", "com.bugsee:bugsee-android:6.9.0")
        assertEquals("6.9.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `compileOnly core pin is the last tier, after extension and dependency project pins (#130)`() {
        app.declare("compileOnly", "com.bugsee:bugsee-android:7.0.0")
        assertEquals("7.0.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
        // A production extension puts a real core on the runtime classpath: better evidence.
        app.declare("implementation", "com.bugsee:bugsee-android-okhttp:7.9.0")
        assertEquals("7.9.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
        lib.declare("api", "com.bugsee:bugsee-android:7.2.0")
        dependOnLib()
        assertEquals("7.2.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `test-named compileOnly pins are ignored and a dependency project extension beats compileOnly (#130)`() {
        app.declare("testCompileOnly", "com.bugsee:bugsee-android:7.5.0")
        app.declare("androidTestCompileOnly", "com.bugsee:bugsee-android:7.6.0")
        assertNull(BugseeSdkDeclarations.declaredCoreSdkVersion(app))
        app.declare("compileOnly", "com.bugsee:bugsee-android:7.0.0")
        lib.declare("api", "com.bugsee:bugsee-android-okhttp:7.9.0")
        dependOnLib()
        assertEquals("7.9.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `core pinned on a configuration compileOnly extends is read (#130)`() {
        val custom = app.configurations.create("bugseeSdk")
        app.configurations.maybeCreate("compileOnly").extendsFrom(custom)
        app.declare("bugseeSdk", "com.bugsee:bugsee-android:7.3.0")
        assertEquals("7.3.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `compileOnly extension pin and ignored compileOnly core do not count (#130)`() {
        app.declare("compileOnly", "com.bugsee:bugsee-android-okhttp:7.7.0")
        assertNull(BugseeSdkDeclarations.declaredCoreSdkVersion(app))
        app.declare("compileOnly", "com.bugsee:bugsee-android:7.0.0")
        val added = app.configurations.getByName("compileOnly").dependencies.toSet()
        assertNull(BugseeSdkDeclarations.declaredCoreSdkVersion(app, ignore = added))
    }

    @Test fun `debugImplementation-only pin on the app still counts (#114)`() {
        app.declare("debugImplementation", "com.bugsee:bugsee-android:6.5.0")
        assertEquals("6.5.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `test-scoped extension pin is ignored by the extension fallback tier (#114)`() {
        app.declare("testImplementation", "com.bugsee:bugsee-android-okhttp:7.1.0")
        assertNull(BugseeSdkDeclarations.declaredCoreSdkVersion(app))
        app.declare("implementation", "com.bugsee:bugsee-android-okhttp:7.0.0")
        assertEquals("7.0.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `known limit - app debug pin counts for every variant and can mask a release pin (#114)`() {
        app.declare("releaseImplementation", "com.bugsee:bugsee-android:6.5.0")
        app.declare("debugImplementation", "com.bugsee:bugsee-android:7.5.0")
        // Documented in BugseePlugin: the resolver is variant-agnostic and picks the highest.
        assertEquals("7.5.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `extension pin is the fallback when no core is declared and never beats a core`() {
        app.declare("implementation", "com.bugsee:bugsee-android-okhttp:7.1.0")
        assertEquals("7.1.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
        app.declare("api", "com.bugsee:bugsee-android:7.0.0")
        assertEquals("7.0.0", BugseeSdkDeclarations.declaredCoreSdkVersion(app))
    }

    @Test fun `plugin artifact is not an SDK module and ignored deps do not count`() {
        app.declare("implementation", "com.bugsee:bugsee-android-gradle-plugin:4.0.0")
        assertNull(BugseeSdkDeclarations.declaredCoreSdkVersion(app))
        app.declare("api", "com.bugsee:bugsee-android:7.0.0")
        val added = app.configurations.getByName("api").dependencies.toSet()
        assertNull(BugseeSdkDeclarations.declaredCoreSdkVersion(app, ignore = added))
    }
}
