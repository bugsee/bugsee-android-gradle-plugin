package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * Pin the library-variant gating in the bugsee Gradle plugin's
 * `onVariants` callback.
 *
 * **The bug this guards against.** The plugin's `BugseeManifestTask`
 * strips Bugsee extension `<provider>` declarations from the merged
 * manifest (under the default `optimizeExtensionsLoading=true`) and
 * records the FQNs in a sidecar file. A separate, **application-only**
 * bytecode transform inlines `register<Name>Extension()` calls into
 * `BugseeInitProvider.initializeExtensions()` so the auto-register
 * happens at app start.
 *
 * Until the fix that this test guards, the manifest task ran for
 * library variants too — meaning a library AAR that happened to
 * declare a Bugsee extension provider (e.g. inherited from a
 * dependency, or shipped as a re-export wrapper) ended up with:
 *  - the `<provider>` stripped from the AAR's merged manifest,
 *  - no compensating bytecode (the transform that adds it only runs
 *    on application variants),
 *  - and therefore no auto-registration of the extension at consumer
 *    app runtime.
 *
 * The fix gates EVERY plugin side effect (manifest transform AND
 * upload-task registration AND bytecode instrumentation) on
 * `variant is ApplicationVariant` in the `onVariants` callback. This
 * test materialises a small library fixture, builds the AAR, and
 * asserts the manifest inside the AAR retains the provider intact.
 *
 * The companion `BugseeManifestOptimizeExtensionsTest` continues to
 * pin the application-variant behavior (provider IS stripped). The
 * two together close both ends of the gating contract.
 */
class BugseeLibraryVariantGatingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val feedbackProviderFqn = "com.bugsee.library.BugseeFeedbackInitProvider"

    @Test
    fun library_variants_do_not_get_their_extension_providers_stripped() {
        val sharedDir = temp.newFolder("lib-gating")
        val fixture = FixtureProject.materialize("library-variant-gating", sharedDir)

        val result = buildLibAssembleDebug(fixture.projectDir)

        val outcome = result.task(":lib:assembleDebug")?.outcome
        assertNotNull("expected :lib:assembleDebug to run", outcome)
        assertTrue(
            ":lib:assembleDebug failed: $outcome",
            outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE),
        )

        // Locate the produced AAR. AGP writes it at
        // `lib/build/outputs/aar/lib-debug.aar` by default.
        val aar = fixture.projectDir
            .resolve("lib/build/outputs/aar")
            .walk()
            .firstOrNull { it.isFile && it.extension == "aar" }
        assertNotNull(
            "expected an AAR under lib/build/outputs/aar/ after assembleDebug",
            aar,
        )

        // The AAR's `AndroidManifest.xml` lives at the AAR zip root.
        // Read it in plain text form (libraries ship a text manifest
        // at this stage; the binary-XML transform happens during
        // application packaging).
        val manifestText = readAarManifest(aar!!)

        // The load-bearing claim: the Bugsee extension provider was
        // NOT stripped from the library AAR. A regression that
        // re-enabled the manifest transform for library variants
        // would silently strip this and fail the assertion.
        assertTrue(
            "AAR manifest MUST retain '$feedbackProviderFqn' — library variants must " +
                "NOT have the manifest transform applied. Manifest content:\n$manifestText",
            manifestText.contains(feedbackProviderFqn),
        )

        // Defense-in-depth: pin the absence of the BUILD_UUID
        // meta-data the manifest transform would inject. If the
        // task did run, this would be present.
        assertFalse(
            "AAR manifest MUST NOT contain 'com.bugsee.android.BUILD_UUID' — the BUILD_UUID " +
                "injection is application-scoped (libraries don't ship crashes). " +
                "Manifest content:\n$manifestText",
            manifestText.contains("com.bugsee.android.BUILD_UUID"),
        )
    }

    @Test
    fun library_variants_do_not_register_a_BugseeManifestTask() {
        // Lighter weight check: run `tasks --all` and inspect the
        // task list. If the manifest transform were wired for
        // library variants, a `BugseeDebugManifest` task would
        // appear; we assert it's absent.
        val sharedDir = temp.newFolder("lib-gating-tasks")
        val fixture = FixtureProject.materialize("library-variant-gating", sharedDir)

        val result = GradleRunner.create()
            .withProjectDir(fixture.projectDir)
            .withArguments(
                ":lib:tasks", "--all",
                "-PbugseeStubSdkRepo=${requireSystemProperty("bugsee.testkit.stubSdkRepo")}",
                "-PbugseePluginProjectDir=${requireSystemProperty("bugsee.testkit.pluginProjectDir")}",
                "--stacktrace",
            )
            .withEnvironment(testKitEnvironment())
            .forwardOutput()
            .build()

        val output = result.output
        // A task named `bugseeDebugManifest` (or any variant of it)
        // would surface in the all-tasks listing. The exact task
        // name is `bugseeDebugManifest`; we use case-insensitive
        // substring so a future capitalization change still catches
        // the regression.
        assertFalse(
            "library variants must NOT have a Bugsee manifest task registered. " +
                "Tasks list contained 'bugsee.*manifest':\n${
                    output.lines()
                        .filter { it.contains("bugsee", ignoreCase = true) && it.contains("manifest", ignoreCase = true) }
                        .joinToString("\n")
                }",
            output.lineSequence()
                .filter { it.contains("bugsee", ignoreCase = true) && it.contains("manifest", ignoreCase = true) }
                .any { it.contains("bugseeDebugManifest") || it.contains("BugseeDebugManifest") },
        )
        // Pin that other Bugsee tasks ARE registered (upload mapping,
        // etc., for library variants? Actually NO — those are
        // application-only too. Pin nothing here; this test focuses
        // on the manifest-task absence.).
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun buildLibAssembleDebug(projectDir: File): org.gradle.testkit.runner.BuildResult {
        // Pure helper — assembles the library AAR. Doesn't go through
        // FixtureProject.build() because that hardcodes
        // `:app:assembleDebug` (which doesn't exist in this fixture).
        return GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(
                ":lib:assembleDebug",
                "-PbugseeStubSdkRepo=${requireSystemProperty("bugsee.testkit.stubSdkRepo")}",
                "-PbugseePluginProjectDir=${requireSystemProperty("bugsee.testkit.pluginProjectDir")}",
                "--stacktrace",
            )
            .withEnvironment(testKitEnvironment())
            .forwardOutput()
            .build()
    }

    private fun readAarManifest(aar: File): String {
        // The AAR is a zip. AndroidManifest.xml is at the root in
        // plain text form (library AARs ship a text manifest; the
        // text-to-binary compile happens at application package
        // time).
        ZipFile(aar).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: error("AAR ${aar.path} has no AndroidManifest.xml entry")
            return zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
        }
    }

    private fun requireSystemProperty(key: String): String {
        return System.getProperty(key)
            ?: error("missing system property $key — is the integrationTest task wired to forward it?")
    }

    private fun testKitEnvironment(): Map<String, String> {
        // Same shape as FixtureProject's private helper — duplicated
        // here because that one is package-private to the
        // harness module and this test wants the buildTasks side
        // door without going through FixtureProject.build's
        // hardcoded `:app:assembleDebug`.
        val env = HashMap<String, String>()
        env["JAVA_HOME"] = requireSystemProperty("bugsee.testkit.javaHome")
        val androidHome = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: System.getProperty("user.home") + "/Library/Android/sdk"
        env["ANDROID_HOME"] = androidHome
        env["ANDROID_SDK_ROOT"] = androidHome
        System.getenv("HOME")?.let { env["HOME"] = it }
        System.getenv("PATH")?.let { env["PATH"] = it }
        return env
    }
}
