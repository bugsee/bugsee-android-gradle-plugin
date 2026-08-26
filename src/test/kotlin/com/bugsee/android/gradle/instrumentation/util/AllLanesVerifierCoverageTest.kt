package com.bugsee.android.gradle.instrumentation.util

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Guards that **every** instrumentation lane has at least one test running its output
 * through [com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness] — i.e.
 * `CheckClassAdapter.verify` plus per-method `SimpleVerifier` dataflow.
 *
 * ### Why a meta-test
 *
 * A lane that emits unverifiable bytecode does not fail the build: it fails at CLASS
 * LOAD on a user's device, as `VerifyError`, in whatever app happens to hit the shape.
 * Nothing in a green plugin build points at the responsible lane. Comparable plugins
 * accumulated years of exactly these reports.
 *
 * Lanes are enumerated from the SOURCE TREE, not a hand-maintained list, so a lane added
 * later is covered the day it appears rather than the day someone remembers this file.
 */
class AllLanesVerifierCoverageTest {

    @Test
    fun `every instrumentation lane verifies its emitted bytecode`() {
        val mainRoot = File("src/main/kotlin/com/bugsee/android/gradle/instrumentation")
        val testRoot = File("src/test/kotlin/com/bugsee/android/gradle/instrumentation")
        assertTrue("instrumentation source root not found", mainRoot.isDirectory)

        // A "lane" is a package that declares a ClassVisitorFactory.
        val lanes = mainRoot.listFiles { f: File -> f.isDirectory }
            .orEmpty()
            .filter { dir -> dir.listFiles { f: File -> f.name.endsWith("ClassVisitorFactory.kt") }?.isNotEmpty() == true }
            .map { it.name }
            .sorted()

        assertTrue("expected to find lanes under $mainRoot", lanes.isNotEmpty())

        val uncovered = lanes.filter { lane ->
            val dir = File(testRoot, lane)
            !dir.isDirectory || dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .none { it.readText().contains("AsmTestHarness") }
        }

        if (uncovered.isNotEmpty()) {
            fail(
                "these instrumentation lanes emit bytecode that no test ever runs through " +
                    "the verifier:\n" +
                    uncovered.joinToString("\n") { "  - $it" } +
                    "\n\nAdd a test under src/test/.../instrumentation/<lane>/ that transforms a " +
                    "fixture with AsmTestHarness.transform(...) and asserts " +
                    "AsmTestHarness.verify(bytes).assertOk()."
            )
        }
    }
}
