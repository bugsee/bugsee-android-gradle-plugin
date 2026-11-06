package com.bugsee.android.gradle.manifest

import org.gradle.api.DefaultTask
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlutterAssetsOrderingTest {

    private fun noExtra(project: org.gradle.api.Project) = project.provider { emptyList<String>() }

    private fun deps(project: org.gradle.api.Project, name: String): Set<String> =
        project.tasks.getByName(name).taskDependencies.getDependencies(null).map { it.name }.toSet()

    @Test
    fun `depends on Flutter copy task registered before wiring`() {
        val project = ProjectBuilder.builder().build()
        project.tasks.register("copyFlutterAssetsDebug")
        val inject = project.tasks.register("injectBugseeDebugBuildId")
        FlutterAssetsOrdering.wire(project, inject, "Debug", noExtra(project))
        assertTrue("copyFlutterAssetsDebug" in deps(project, "injectBugseeDebugBuildId"))
    }

    @Test
    fun `depends on Flutter copy task registered after wiring`() {
        val project = ProjectBuilder.builder().build()
        val inject = project.tasks.register("injectBugseeDebugBuildId")
        FlutterAssetsOrdering.wire(project, inject, "Debug", noExtra(project))
        project.tasks.register("copyFlutterAssetsDebug", DefaultTask::class.java)
        assertTrue("copyFlutterAssetsDebug" in deps(project, "injectBugseeDebugBuildId"))
    }

    @Test
    fun `ignores other variants and is a no-op without Flutter`() {
        val project = ProjectBuilder.builder().build()
        project.tasks.register("copyFlutterAssetsRelease")
        val inject = project.tasks.register("injectBugseeDebugBuildId")
        FlutterAssetsOrdering.wire(project, inject, "Debug", noExtra(project))
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
        FlutterAssetsOrdering.wire(project, inject, "Debug", noExtra(project))
        assertEquals(false, flutterRealized, "wiring alone must stay lazy")
        val resolved = deps(project, "injectBugseeDebugBuildId")
        assertEquals(setOf("copyFlutterAssetsDebug"), resolved)
        assertTrue(flutterRealized, "dependency resolution realizes the Flutter task")
        assertEquals(false, otherRealized, "unrelated tasks must stay unrealized")
    }

    @Test
    fun `configured names add dependencies and expand the Variant placeholder`() {
        val project = ProjectBuilder.builder().build()
        project.tasks.register("flutterCopyAssetsDebug")
        project.tasks.register("otherWriterRelease")
        val inject = project.tasks.register("injectBugseeDebugBuildId")
        FlutterAssetsOrdering.wire(
            project, inject, "Debug",
            project.provider { listOf("flutterCopyAssets<Variant>", "otherWriterRelease", "missing") },
        )
        assertEquals(setOf("flutterCopyAssetsDebug", "otherWriterRelease"), deps(project, "injectBugseeDebugBuildId"))
    }

    @Test
    fun `taskNames lists the built-in name first and dedupes`() {
        assertEquals(
            listOf("copyFlutterAssetsDebug", "x"),
            FlutterAssetsOrdering.taskNames("Debug", listOf("copyFlutterAssets<Variant>", "x", "x")),
        )
    }

    @Test
    fun `warns when Flutter is applied but its copy task is missing`() {
        val msgs = FlutterAssetsOrdering.diagnostics(setOf("assembleDebug"), true, listOf("Debug"), emptyList())
        assertEquals(1, msgs.size)
        assertTrue("copyFlutterAssets<Variant>" in msgs[0] && "assetsDependsOn" in msgs[0])
    }

    @Test
    fun `no warning when the Flutter task exists, a configured task exists, or Flutter is absent`() {
        val none = emptyList<String>()
        assertEquals(none, FlutterAssetsOrdering.diagnostics(setOf("copyFlutterAssetsDebug"), true, listOf("Debug"), none))
        assertEquals(
            none,
            FlutterAssetsOrdering.diagnostics(setOf("renamedDebug"), true, listOf("Debug"), listOf("renamed<Variant>")),
        )
        assertEquals(none, FlutterAssetsOrdering.diagnostics(emptySet(), false, listOf("Debug"), none))
    }

    @Test
    fun `warns about a configured name that matches no task`() {
        val msgs = FlutterAssetsOrdering.diagnostics(setOf("a"), false, listOf("Debug"), listOf("typo<Variant>"))
        assertEquals(1, msgs.size)
        assertTrue("typo<Variant>" in msgs[0])
    }

    @Test
    fun `no warning when only some variants have the task, as Flutter registers just the built one`() {
        val msgs = FlutterAssetsOrdering.diagnostics(
            setOf("copyFlutterAssetsDebug"), true, listOf("Debug", "Release", "Profile"), emptyList(),
        )
        assertEquals(emptyList<String>(), msgs)
    }

    @Test
    fun `one warning lists every variant when none has the task`() {
        val msgs = FlutterAssetsOrdering.diagnostics(emptySet(), true, listOf("Debug", "Release"), emptyList())
        assertEquals(1, msgs.size)
        assertTrue("Debug, Release" in msgs[0])
    }
}
