package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #53: a non-minified variant has no mapping to upload, so it must not
 * warn about a missing app token, and must not make AGP print "will never be
 * invoked" for the OBFUSCATION_MAPPING_FILE listener. A minified variant keeps
 * both behaviours (warning + listener-driven build-id resolution).
 */
class NonMinifiedVariantWarningsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `non-minified debug assemble prints neither the token warning nor the AGP listener message`() {
        val fixture = FixtureProject.materialize("build-type-gating", temp.newFolder("bt"))
        val out = fixture.buildTasks(listOf(":app:assembleDebug")).output
        assertTrue("build must succeed\n${out.takeLast(2000)}", out.contains("BUILD SUCCESSFUL"))
        assertFalse("token warning leaked for a variant with no mapping\n$out", "Could not resolve appToken" in out)
        assertFalse("AGP listener message leaked\n$out", "will never be invoked" in out)
    }

    @Test
    fun `minified release still warns about the missing token and resolves the build id`() {
        val fixture = FixtureProject.materialize("build-type-gating", temp.newFolder("bt"))
        val out = fixture.buildTasks(
            listOf(":app:assembleRelease"),
            null,
            "-PbugseeFixtureMinifyRelease=true",
        ).output
        assertTrue("build must succeed\n${out.takeLast(2000)}", out.contains("BUILD SUCCESSFUL"))
        assertTrue(
            "minified release must still warn, naming the variant\n$out",
            "Could not resolve appToken for variant release" in out,
        )
        assertFalse("AGP listener message leaked for a minified variant\n$out", "will never be invoked" in out)
        val buildId = File(fixture.projectDir, "app/build/intermediates/bugsee/release/build-id.txt")
        assertTrue("resolved build id missing", buildId.isFile && buildId.readText().isNotBlank())
    }
}
