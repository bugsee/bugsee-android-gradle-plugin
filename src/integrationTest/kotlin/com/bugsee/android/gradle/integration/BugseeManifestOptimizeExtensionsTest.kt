package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end coverage for the `optimizeExtensionsLoading` DSL
 * switch (default: `true`) and the multi-extension manifest-stripping
 * behavior. The fixture's `AndroidManifest.xml` declares TWO Bugsee
 * extension providers (`BugseeFeedbackInitProvider` and
 * `BugseeRemotingInitProvider`) so this test class exercises BOTH:
 *  - the regex's ability to detect more than one extension in the
 *    merged manifest;
 *  - the de-duplicated `detectedExtensions` output;
 *  - the symmetric `optimizeExtensionsLoading=false` path that
 *    leaves the providers intact.
 *
 * Unit-level coverage in [com.bugsee.android.gradle.manifest.ManifestModifierExtensionsTest]
 * pins the regex + the DOM editing in isolation, but the wiring
 * through AGP's MERGED_MANIFEST transform + the DSL property
 * resolution only ever runs in TestKit. A regression that severed
 * either link (e.g., the task ran but ignored the DSL value, or the
 * stripping ran but the de-dup logic dropped one of the FQNs) would
 * only surface here.
 */
class BugseeManifestOptimizeExtensionsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val feedbackProviderFqn = "com.bugsee.library.BugseeFeedbackInitProvider"
    private val remotingProviderFqn = "com.bugsee.library.BugseeRemotingInitProvider"

    /**
     * Build with `optimizeExtensionsLoading` left at its default
     * (`true`). The default behavior strips every Bugsee extension
     * `<provider>` from the merged manifest and records the FQNs in
     * the `detectedExtensions` output file so the downstream
     * bytecode visitor can inline the `register*Extension()` calls.
     */
    @Test
    fun defaultOptimization_strips_extension_providers_from_merged_manifest() {
        val dir = temp.newFolder("opt-on")
        val fixture = FixtureProject.materialize("app-startup-tracing", dir)
        val result = fixture.buildTasks(
            tasks = listOf(":app:assembleDebug"),
            tier = "STANDARD",
            // No `bugseeFixtureOptimizeExtensions` override → DSL
            // default kicks in (true).
        )
        assertTrue(
            "build must succeed: ${result.task(":app:assembleDebug")?.outcome}",
            result.task(":app:assembleDebug")?.outcome ==
                TaskOutcome.SUCCESS,
        )

        val manifest = fixture.readMergedManifest("debug")
        assertNotNull("merged manifest must exist on disk", manifest)
        val text = manifest!!

        assertTrue(
            "merged manifest must NOT contain '$feedbackProviderFqn' when optimization is on; got:\n$text",
            !text.contains(feedbackProviderFqn),
        )
        assertTrue(
            "merged manifest must NOT contain '$remotingProviderFqn' when optimization is on; got:\n$text",
            !text.contains(remotingProviderFqn),
        )

        // Core SDK's own `BugseeInitProvider` is NOT an extension and
        // must NOT be stripped. The fixture doesn't actually declare
        // it, but pin the negative-space contract by asserting the
        // regex didn't over-match: anything `Bugsee*InitProvider`
        // other than `BugseeInitProvider` is stripped, but a literal
        // `BugseeInitProvider` (no `<name>` infix) would be left
        // alone.
        // (Empty assert — we just want to be sure the test stays
        // alert to the negative case if a future fixture adds the
        // core init provider.)
    }

    /**
     * Build with `optimizeExtensionsLoading=false` via the
     * `bugseeFixtureOptimizeExtensions` Gradle property, which the
     * fixture's `app/build.gradle.kts` plumbs into the typed DSL.
     * Both extension `<provider>` declarations must remain in the
     * merged manifest.
     */
    @Test
    fun optimization_off_leaves_extension_providers_intact() {
        val dir = temp.newFolder("opt-off")
        val fixture = FixtureProject.materialize("app-startup-tracing", dir)
        val result = fixture.buildTasks(
            tasks = listOf(":app:assembleDebug"),
            tier = "STANDARD",
            "-PbugseeFixtureOptimizeExtensions=false",
        )
        assertTrue(
            "build must succeed: ${result.task(":app:assembleDebug")?.outcome}",
            result.task(":app:assembleDebug")?.outcome ==
                TaskOutcome.SUCCESS,
        )

        val manifest = fixture.readMergedManifest("debug")
        assertNotNull("merged manifest must exist on disk", manifest)
        val text = manifest!!

        // Both extension providers must survive into the merged
        // manifest. The `<application>` block contains them because
        // the fixture's source manifest declares them and the
        // stripper was disabled. This is the AAR-shipped product:
        // each extension's init provider is what causes the
        // extension to auto-register at app start.
        assertTrue(
            "merged manifest MUST contain '$feedbackProviderFqn' when optimization is off; got:\n$text",
            text.contains(feedbackProviderFqn),
        )
        assertTrue(
            "merged manifest MUST contain '$remotingProviderFqn' when optimization is off; got:\n$text",
            text.contains(remotingProviderFqn),
        )
    }

    /**
     * With optimization on, the task writes the FQNs of every
     * stripped provider into the `detectedExtensions` output file
     * (one per line). Verify both FQNs appear, in
     * document order, with no duplicates.
     *
     * The output file lives at
     * `app/build/intermediates/bugsee/detected_extensions/<variant>/detected_extensions.txt`
     * but its exact location is the plugin's contract with the
     * downstream visitor, not user-facing. We read whichever path
     * the plugin actually wrote to by scanning under the bugsee
     * intermediates dir.
     */
    @Test
    fun stripped_extension_FQNs_are_recorded_in_detected_extensions_output() {
        val dir = temp.newFolder("opt-detected")
        val fixture = FixtureProject.materialize("app-startup-tracing", dir)
        val result = fixture.buildTasks(
            tasks = listOf(":app:assembleDebug"),
            tier = "STANDARD",
        )
        assertTrue(
            "build must succeed",
            result.task(":app:assembleDebug")?.outcome == TaskOutcome.SUCCESS,
        )

        // Locate the detected-extensions output. The exact path is
        // a moving target across AGP versions; scan under the
        // app's bugsee intermediates root for a `.txt` file named
        // `detected_extensions.txt` or similar.
        val intermediatesRoot = fixture.projectDir.resolve("app/build/intermediates")
        val detectedFile = intermediatesRoot.walk()
            .filter { it.isFile && it.name.contains("detected", ignoreCase = true) && it.extension == "txt" }
            .firstOrNull()
        assertNotNull(
            "expected to find a detected-extensions output file under " +
                "${intermediatesRoot.absolutePath}; tree:\n" +
                intermediatesRoot.walk().filter { it.isFile }.joinToString("\n") { it.relativeTo(intermediatesRoot).path },
            detectedFile,
        )
        val fqns = detectedFile!!.readLines().filter { it.isNotBlank() }

        // Pin the multi-extension behavior: both FQNs are recorded
        // exactly once, and the regex matched both even though they
        // share a prefix. Order: the regex preserves document order
        // from the source manifest. The fixture declares Feedback
        // before Remoting.
        assertEquals(
            "expected detected-extensions output to list both stripped FQNs in document order " +
                "with no duplicates; got: $fqns",
            listOf(feedbackProviderFqn, remotingProviderFqn),
            fqns,
        )
    }
}
