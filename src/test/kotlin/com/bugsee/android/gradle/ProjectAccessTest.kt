package com.bugsee.android.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Isolated Projects-safe helpers that replaced the plugin's root-project reads. The
 * end-to-end proof (a multi-module build run with isolated projects enabled) lives in the PR; these
 * guard the behaviour the replacements must keep.
 */
class ProjectAccessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun buildStateClaimIsOncePerKey() {
        val project = ProjectBuilder.builder().withName("p").withProjectDir(tmp.newFolder("p")).build()
        val state = BugseeBuildState.of(project)
        assertTrue(state.claim("k"))
        assertFalse(state.claim("k"))
        assertTrue(state.claim("other"))
        // The service is shared: a second lookup sees the same instance and the same claims.
        assertFalse(BugseeBuildState.of(project).claim("k"))
    }

    @Test
    fun buildStateIsSharedBetweenSubprojects() {
        val root = ProjectBuilder.builder().withName("root").withProjectDir(tmp.newFolder("root")).build()
        val a = ProjectBuilder.builder().withName("a").withProjectDir(tmp.newFolder("root", "a")).withParent(root).build()
        val b = ProjectBuilder.builder().withName("b").withProjectDir(tmp.newFolder("root", "b")).withParent(root).build()
        BugseeBuildState.of(a).knownVariantNames() += "free"
        assertTrue("free" in BugseeBuildState.of(b).knownVariantNames())
        assertTrue(BugseeBuildState.of(a).claim("x"))
        assertFalse(BugseeBuildState.of(b).claim("x"))
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
    fun gradlePropertyReadsOwnExtraPropertiesAndNeverTheParent() {
        val root = ProjectBuilder.builder().withName("r2").withProjectDir(tmp.newFolder("r2")).build()
        val child = ProjectBuilder.builder().withName("app").withProjectDir(tmp.newFolder("r2", "app")).withParent(root).build()
        child.extensions.extraProperties.set("bugsee.instrumentation.okhttp", false)
        root.extensions.extraProperties.set("only.on.root", "x")

        assertEquals("false", ProjectAccess.gradleProperty(child, "bugsee.instrumentation.okhttp"))
        assertNull(ProjectAccess.gradleProperty(child, "only.on.root"), "must not fall through to the parent")
        assertNull(ProjectAccess.gradleProperty(child, "absent"))
    }

    @Test
    fun isolatedProjectsIsOffUnlessSwitchedOn() {
        val project = ProjectBuilder.builder().withName("p3").withProjectDir(tmp.newFolder("p3")).build()
        assertFalse(ProjectAccess.isIsolatedProjects(project))
    }
}
