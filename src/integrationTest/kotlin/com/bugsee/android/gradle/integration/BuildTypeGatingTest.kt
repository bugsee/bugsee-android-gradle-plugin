package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import com.bugsee.android.gradle.integration.harness.InstrumentedBytecodeIndex
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Does the plugin instrument a variant whose classpath does NOT carry the SDK?
 *
 * `DependencyDetector.hasBugseeDependency` scans `project.configurations.any { }` —
 * every configuration, not the variant's. `InstrumentationRegistrar.applyAll(variant)`
 * runs per-variant but calls `shouldApply(project, …)`, which cannot see the variant,
 * and nothing filters variants at the call site (`BugseePlugin.kt:347`).
 *
 * So on paper a `debug`-only SDK dependency makes the gate answer true for `release`
 * too, and release bytecode gets Bugsee calls injected against a class that is not on
 * its runtime classpath — an R8 "Missing class", or `NoClassDefFoundError` in the
 * shipped app.
 *
 * The customer whose report started this investigation states "Bugsee is not in our
 * release builds", so this is their configuration. They also build release
 * successfully today, which means either they declare the dependency for every
 * variant, or something downstream masks it. This test exists to find out which.
 *
 * It is deliberately written to REPORT what happens rather than to assert a conclusion
 * that has not been established: debug must be instrumented (proving the fixture and
 * the lane both work), and the release count is surfaced in the failure message either
 * way so the answer is legible whichever it turns out to be.
 */
class BuildTypeGatingTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a debug-only SDK dependency does not leak instrumentation into release`() {
        val fixture = FixtureProject.materialize("build-type-gating", temp.newFolder("bt"))

        val debugResult = fixture.buildTasks(listOf(":app:assembleDebug"))
        assertTrue(
            "fixture sanity: :app:assembleDebug must succeed.\n${debugResult.output.takeLast(2000)}",
            debugResult.output.contains("BUILD SUCCESSFUL"),
        )

        val debugIndex = InstrumentedBytecodeIndex.walk(fixture.projectDir, variant = "debug")
        assertTrue(
            "fixture sanity: the debug variant DECLARES the SDK, so it must be " +
                "instrumented — otherwise this test proves nothing about release. " +
                "Found ${debugIndex.totalDispatcherCalls()} dispatcher calls.",
            debugIndex.totalDispatcherCalls() > 0,
        )

        // Release does NOT declare the SDK (debugCompileOnly only).
        val releaseResult = fixture.buildTasks(listOf(":app:assembleRelease"))
        assertTrue(
            "release assembly must succeed.\n${releaseResult.output.takeLast(3000)}",
            releaseResult.output.contains("BUILD SUCCESSFUL"),
        )

        // walkOrEmpty, not walk: when the gate is correct, release registers no
        // instrumentation at all and AGP never creates the post-transform
        // classes directory. That absence IS the pass condition, not an error.
        val releaseIndex = InstrumentedBytecodeIndex
            .walkOrEmpty(fixture.projectDir, variant = "release")
            .totalDispatcherCalls()
        assertTrue(
            "RELEASE was instrumented despite not declaring the SDK: found " +
                "${releaseIndex} injected dispatcher calls. Those reference " +
                "com.bugsee.library.adapters.BugseeOperationDispatcher, which is absent " +
                "from the release runtime classpath — R8 'Missing class' or " +
                "NoClassDefFoundError in the shipped app.",
            releaseIndex == 0,
        )
    }
}

