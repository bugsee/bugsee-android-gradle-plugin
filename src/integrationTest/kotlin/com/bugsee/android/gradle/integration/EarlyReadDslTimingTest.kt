package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #50 against real AGP. A plugin (or the build script) reads `implementation` before the
 * `bugsee { }` block runs, which fires the plugin's auto-add with default DSL values. The decision
 * is corrected once the DSL is final; this must happen BEFORE AGP's `onVariants` callbacks read
 * the declared dependencies to gate the bytecode instrumentation, otherwise the SDK is dropped
 * but the classes are still rewritten to call it (NoClassDefFoundError at runtime). A
 * ProjectBuilder test cannot show that ordering.
 */
class EarlyReadDslTimingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun releaseClassesReferencingTheSdk(projectDir: File): List<String> =
        projectDir.resolve("app/build/intermediates").walk()
            .filter { it.isFile && it.extension == "class" && it.path.contains("elease") }
            .filter { String(it.readBytes(), Charsets.ISO_8859_1).contains("com/bugsee/library/") }
            .map { it.name }
            .toList()

    private fun fooClassesInRelease(projectDir: File): List<File> =
        projectDir.resolve("app/build/intermediates").walk()
            .filter { it.isFile && it.name == "Foo.class" && it.path.contains("elease") }
            .toList()

    @Test
    fun `early read then sdkAutoLoad opt-out leaves no SDK and no instrumented bytecode`() {
        val fixture = FixtureProject.materialize("early-read-dsl", temp.newFolder("early"))
        val result = fixture.buildTasks(listOf(":app:assembleRelease"))
        assertTrue("fixture must read implementation early", result.output.contains("EARLY-READ"))

        assertTrue("the release classes were compiled", fooClassesInRelease(fixture.projectDir).isNotEmpty())
        assertEquals(
            "SDK opted out: no class may reference com/bugsee/library/ (NoClassDefFoundError at runtime)",
            emptyList<String>(),
            releaseClassesReferencingTheSdk(fixture.projectDir),
        )
    }

    @Test
    fun `control - with the SDK declared the same early read still instruments`() {
        val fixture = FixtureProject.materialize("early-read-dsl", temp.newFolder("control"))
        fixture.buildTasks(listOf(":app:assembleRelease"), null, "-PdeclareSdk=true")
        assertTrue(
            "control: with the SDK on the classpath the Log/Thread instrumentation must run, otherwise " +
                "the negative test above proves nothing",
            releaseClassesReferencingTheSdk(fixture.projectDir).isNotEmpty(),
        )
    }

    @Test
    fun `normal order gives the same result as the early read`() {
        val fixture = FixtureProject.materialize("early-read-dsl", temp.newFolder("normal"))
        fixture.buildTasks(listOf(":app:assembleRelease"), null, "-PearlyRead=false")
        assertEquals(emptyList<String>(), releaseClassesReferencingTheSdk(fixture.projectDir))
    }
}
