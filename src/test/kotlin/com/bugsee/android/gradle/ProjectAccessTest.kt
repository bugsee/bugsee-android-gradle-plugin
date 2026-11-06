package com.bugsee.android.gradle

import com.bugsee.android.gradle.instrumentation.ProjectDependencyCompat
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.configuration.BuildFeature
import org.gradle.api.provider.Provider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Isolated Projects-safe helpers that replaced the plugin's root-project reads. The
 * end-to-end proof (a multi-module build run with Isolated Projects on) is the TestKit
 * `IsolatedProjectsIntegrationTest`; these guard the behaviour the replacements must keep.
 */
class ProjectAccessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun buildStateClaimIsOncePerKey() {
        val project = ProjectBuilder.builder().withName("p").withProjectDir(tmp.newFolder("p")).build()
        assertTrue(BugseeBuildState.claim(project, "k"))
        assertFalse(BugseeBuildState.claim(project, "k"))
        assertTrue(BugseeBuildState.claim(project, "other"))
    }

    @Test
    fun buildStateIsSharedBetweenSubprojects() {
        val root = ProjectBuilder.builder().withName("root").withProjectDir(tmp.newFolder("root")).build()
        val a = ProjectBuilder.builder().withName("a").withProjectDir(tmp.newFolder("root", "a")).withParent(root).build()
        val b = ProjectBuilder.builder().withName("b").withProjectDir(tmp.newFolder("root", "b")).withParent(root).build()
        BugseeBuildState.knownVariantNames(a) += "free"
        assertTrue("free" in BugseeBuildState.knownVariantNames(b))
        assertTrue(BugseeBuildState.claim(a, "x"))
        assertFalse(BugseeBuildState.claim(b, "x"))
    }

    @Test
    fun rootFileResolvesAgainstTheRootNotTheModule() {
        val root = ProjectBuilder.builder().withName("r").withProjectDir(tmp.newFolder("r")).build()
        val child = ProjectBuilder.builder().withName("app").withProjectDir(tmp.newFolder("r", "app")).withParent(root).build()
        assertEquals(
            root.projectDir.resolve("bugsee.properties").canonicalFile,
            ProjectAccess.rootFile(child, "bugsee.properties").asFile.canonicalFile,
        )
    }

    @Test
    fun gradlePropertyKeepsParentInheritanceWhenIsolatedProjectsIsOff() {
        val root = ProjectBuilder.builder().withName("r2").withProjectDir(tmp.newFolder("r2")).build()
        val child = ProjectBuilder.builder().withName("app").withProjectDir(tmp.newFolder("r2", "app")).withParent(root).build()
        child.extensions.extraProperties.set("bugsee.instrumentation.okhttp", false)
        // A root build script's `extra[...]` reaches subprojects through findProperty. Dropping that
        // silently changed behaviour for every build that has not opted in to Isolated Projects.
        root.extensions.extraProperties.set("bugsee.instrumentation.log", "false")

        assertEquals("false", ProjectAccess.gradleProperty(child, "bugsee.instrumentation.okhttp"))
        assertEquals("false", ProjectAccess.gradleProperty(child, "bugsee.instrumentation.log"))
        assertNull(ProjectAccess.gradleProperty(child, "absent"))
    }

    @Test
    fun isolatedProjectsIsOffWithoutTheFeature() {
        val project = ProjectBuilder.builder().withName("p3").withProjectDir(tmp.newFolder("p3")).build()
        assertFalse(ProjectAccess.isIsolatedProjects(project))
    }

    @Test
    fun isolatedProjectsFollowsTheBuildFeature() {
        assertTrue(ProjectAccess.isActive(features(active = true)), "an active feature must be detected")
        assertFalse(ProjectAccess.isActive(features(active = false)))
    }

    @Test
    fun targetProjectShortcutSkipsTheSiblingOnlyUnderIsolatedProjects() {
        val root = ProjectBuilder.builder().withName("r4").withProjectDir(tmp.newFolder("r4")).build()
        ProjectBuilder.builder().withName("lib").withProjectDir(tmp.newFolder("r4", "lib")).withParent(root).build()
        val app = ProjectBuilder.builder().withName("app").withProjectDir(tmp.newFolder("r4", "app")).withParent(root).build()
        app.configurations.create("implementation")
        val dep = app.dependencies.project(mapOf("path" to ":lib")) as ProjectDependency

        assertNull(
            ProjectDependencyCompat.targetProject(app, dep, isolatedProjects = true),
            "under Isolated Projects the sibling project must not be resolved",
        )
        val resolved = ProjectDependencyCompat.targetProject(app, dep, isolatedProjects = false)
        assertNotNull(resolved, "without Isolated Projects the sibling is still resolved")
        assertEquals(":lib", resolved.path)
    }

    @Test
    fun targetProjectResolvesNestedAbsolutePathFromANonRootProject() {
        val root = ProjectBuilder.builder().withName("r5").withProjectDir(tmp.newFolder("r5")).build()
        val libs = ProjectBuilder.builder().withName("libs").withProjectDir(tmp.newFolder("r5", "libs")).withParent(root).build()
        ProjectBuilder.builder().withName("core").withProjectDir(tmp.newFolder("r5", "libs", "core")).withParent(libs).build()
        val app = ProjectBuilder.builder().withName("app").withProjectDir(tmp.newFolder("r5", "app")).withParent(root).build()
        app.configurations.create("implementation")
        val dep = app.dependencies.project(mapOf("path" to ":libs:core")) as ProjectDependency

        // The wrapper's Gradle (8.7) has no ProjectDependency.getPath(), so inject it to exercise the
        // findProject(path) branch used on Gradle 8.11+.
        val viaPath = ProjectDependencyCompat.targetProject(app, dep, isolatedProjects = false) { ":libs:core" }
        assertEquals(":libs:core", viaPath?.path)
        assertNull(ProjectDependencyCompat.targetProject(app, dep, isolatedProjects = true) { ":libs:core" })
    }

    /** A [BuildFeatures] whose `isolatedProjects.active` is [active]; every other member is unused. */
    private fun features(active: Boolean): BuildFeatures {
        val provider = object : Provider<Boolean> by tmpProject().provider({ active }) {}
        val feature = Proxy.newProxyInstance(
            BuildFeature::class.java.classLoader, arrayOf(BuildFeature::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getActive" -> provider
                else -> throw UnsupportedOperationException(method.name)
            }
        } as BuildFeature
        return Proxy.newProxyInstance(
            BuildFeatures::class.java.classLoader, arrayOf(BuildFeatures::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getIsolatedProjects" -> feature
                else -> throw UnsupportedOperationException(method.name)
            }
        } as BuildFeatures
    }

    private fun tmpProject() = ProjectBuilder.builder().withName("f").withProjectDir(tmp.newFolder()).build()
}
