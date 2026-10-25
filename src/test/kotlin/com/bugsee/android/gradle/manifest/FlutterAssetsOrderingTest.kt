package com.bugsee.android.gradle.manifest

import org.gradle.api.DefaultTask
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlutterAssetsOrderingTest {

    private fun deps(project: org.gradle.api.Project, name: String): Set<String> =
        project.tasks.getByName(name).taskDependencies.getDependencies(null).map { it.name }.toSet()

    @Test
    fun `depends on Flutter copy task registered before wiring`() {
        val project = ProjectBuilder.builder().build()
        project.tasks.register("copyFlutterAssetsDebug")
        val inject = project.tasks.register("injectBugseeDebugBuildId")
        FlutterAssetsOrdering.wire(project, inject, "Debug")
        assertTrue("copyFlutterAssetsDebug" in deps(project, "injectBugseeDebugBuildId"))
    }

    @Test
    fun `depends on Flutter copy task registered after wiring`() {
        val project = ProjectBuilder.builder().build()
        val inject = project.tasks.register("injectBugseeDebugBuildId")
        FlutterAssetsOrdering.wire(project, inject, "Debug")
        project.tasks.register("copyFlutterAssetsDebug", DefaultTask::class.java)
        assertTrue("copyFlutterAssetsDebug" in deps(project, "injectBugseeDebugBuildId"))
    }

    @Test
    fun `ignores other variants and is a no-op without Flutter`() {
        val project = ProjectBuilder.builder().build()
        project.tasks.register("copyFlutterAssetsRelease")
        val inject = project.tasks.register("injectBugseeDebugBuildId")
        FlutterAssetsOrdering.wire(project, inject, "Debug")
        assertEquals(emptySet(), deps(project, "injectBugseeDebugBuildId"))
    }

    @Test
    fun `resolving dependencies realizes only the Flutter task`() {
        val project = ProjectBuilder.builder().build()
        var flutterRealized = false
        var otherRealized = false
        project.tasks.register("copyFlutterAssetsDebug") { flutterRealized = true }
        project.tasks.register("unrelated") { otherRealized = true }
        val inject = project.tasks.register("injectBugseeDebugBuildId")
        FlutterAssetsOrdering.wire(project, inject, "Debug")
        assertEquals(false, flutterRealized, "wiring alone must stay lazy")
        val resolved = deps(project, "injectBugseeDebugBuildId")
        assertEquals(setOf("copyFlutterAssetsDebug"), resolved)
        assertTrue(flutterRealized, "dependency resolution realizes the Flutter task")
        assertEquals(false, otherRealized, "unrelated tasks must stay unrealized")
    }
}
