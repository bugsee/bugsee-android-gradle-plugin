package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FakeBugseeRepo
import com.bugsee.android.gradle.integration.harness.FakeBugseeRepo.Extension
import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Extension-provider consolidation (issue #176): the manifest strips an extension's `<provider>` and the
 * `BugseeInitProvider.initializeExtensions()` rewrite registers it instead. The invariant, asserted on
 * real AGP builds for every module layout and both variants:
 *
 *     a provider is gone from the merged manifest  <=>  its register call is in the dex.
 *
 * The SDK is always an EXTERNAL artifact (fake AARs of the published shape, [FakeBugseeRepo]); that is what
 * makes the rewrite a Gradle artifact transform, which can run before any task of the build.
 */
class BugseeExtensionConsolidationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val ndk = FakeBugseeRepo.NDK

    // ── the layouts: where the SDK is declared relative to the module the plugin is applied to ───────

    @Test fun direct_in_the_app() = check("direct", listOf(ndk))

    /** The Unity layout, and the one #176 broke: `implementation` pin in a library module. */
    @Test fun in_a_library_module() = check("library", listOf(ndk))

    @Test fun in_a_library_module_exposed_as_api() = check("library-api", listOf(ndk))

    @Test fun two_modules_down() = check("chain", listOf(ndk))

    /** Core in one module, the extension in another. */
    @Test fun core_and_extension_in_different_modules() = check("split", listOf(ndk))

    /** Nothing declared: the plugin auto-adds the core SDK and the NDK extension. */
    @Test fun auto_added_by_the_plugin() = check("autoadd", listOf(ndk))

    @Test fun several_extensions_in_a_library_module() =
        check("library", listOf(FakeBugseeRepo.NDK, FakeBugseeRepo.FEEDBACK, FakeBugseeRepo.OKHTTP))

    @Test fun an_extension_whose_provider_lives_in_another_package() =
        check("library", listOf(FakeBugseeRepo.NDK, FakeBugseeRepo.COMPOSE))

    @Test fun core_sdk_without_any_extension_strips_nothing() = check("core-only", emptyList())

    /**
     * The extension reaches the classpath only through a third-party wrapper, and auto-add is off: nothing the app
     * declares names Bugsee, so the rewrite does not apply. The provider must then STAY (strip only what is injected).
     */
    @Test fun extension_only_via_a_wrapper_without_the_rewrite_keeps_its_provider() =
        check("wrapper", listOf(ndk), consolidated = emptyList())

    /** Same wrapper, auto-add on: the plugin adds the SDK, the rewrite applies, the NDK provider is consolidated. */
    @Test fun extension_only_via_a_wrapper_with_the_rewrite_is_consolidated() =
        check("wrapper-autoload", listOf(ndk))

    @Test fun no_sdk_at_all_strips_nothing() = check("none", emptyList())

    /**
     * A known provider the APP declares itself, with no module for it on the classpath: nothing will register it, so it
     * must stay. Only the extension that IS on the classpath (NDK) is consolidated.
     */
    @Test fun a_known_provider_declared_by_the_app_without_its_module_stays() {
        val fixture = materialize()
        fixture.buildTasks(listOf(":app:assembleDebug"), null, *args("library", listOf(ndk), "-PbugseeOwnProvider=true"))
        val manifest = manifestOf(fixture, "debug")
        assertTrue("the app's own Feedback provider must stay", manifest.contains(FakeBugseeRepo.FEEDBACK.providerFqn))
        assertTrue("the NDK provider is consolidated", !manifest.contains(ndk.providerFqn))
        val dex = dexStrings(fixture.projectDir, "debug")
        assertTrue("NDK is registered by the rewrite", FakeBugseeRepo.registerCall(ndk) in dex)
        assertTrue("Feedback is NOT registered by the rewrite (its provider is still there)", FakeBugseeRepo.registerCall(FakeBugseeRepo.FEEDBACK) !in dex)
    }

    // ── switches ─────────────────────────────────────────────────────────

    @Test fun optimization_off_keeps_every_provider_and_injects_nothing() =
        check("library", listOf(ndk), consolidated = emptyList(), "-PbugseeOptimize=false")

    @Test fun instrumentation_off_keeps_every_provider_and_injects_nothing() =
        check("library", listOf(ndk), consolidated = emptyList(), "-Pbugsee.instrumentation.enabled=false")

    // ── build-to-build behaviour ───────────────────────────────────────────

    /** The rewrite must not depend on a file an earlier build left behind: clean and incremental agree. */
    @Test fun a_second_incremental_build_gives_the_same_result() {
        val fixture = materialize()
        val args = args("library", listOf(ndk))
        fixture.buildTasks(listOf(":app:assembleDebug"), null, *args)
        val first = observe(fixture, listOf(ndk))
        fixture.buildTasks(listOf(":app:assembleDebug"), null, *args)
        assertEquals(first, observe(fixture, listOf(ndk)))
        assertEquals(setOf(ndk.providerFqn) to setOf(ndk.name), first.strippedProviders to first.injected)
    }

    /** Flip the consolidation off and on across builds in one project (a cached transform must not be reused). */
    @Test fun turning_the_consolidation_on_after_it_was_off_injects() {
        val fixture = materialize()
        fixture.buildTasks(listOf(":app:assembleDebug"), null, *args("library", listOf(ndk)), "-PbugseeOptimize=false")
        assertEquals(Observed(emptySet(), emptySet()), observe(fixture, listOf(ndk)))
        fixture.buildTasks(listOf(":app:assembleDebug"), null, *args("library", listOf(ndk)))
        assertEquals(Observed(setOf(ndk.providerFqn), setOf(ndk.name)), observe(fixture, listOf(ndk)))
    }

    /** Changing which extensions are on the classpath changes the rewrite (it is a real task/transform input). */
    @Test fun adding_an_extension_between_builds_registers_it() {
        val fixture = materialize()
        fixture.buildTasks(listOf(":app:assembleDebug"), null, *args("library", listOf(ndk)))
        assertEquals(Observed(setOf(ndk.providerFqn), setOf(ndk.name)), observe(fixture, listOf(ndk)))
        fixture.buildTasks(listOf(":app:assembleDebug"), null, *args("library", listOf(ndk, FakeBugseeRepo.FEEDBACK)))
        assertEquals(
            Observed(setOf(ndk.providerFqn, FakeBugseeRepo.FEEDBACK.providerFqn), setOf(ndk.name, FakeBugseeRepo.FEEDBACK.name)),
            observe(fixture, listOf(ndk, FakeBugseeRepo.FEEDBACK)),
        )
    }

    @Test fun works_with_the_configuration_cache() {
        val fixture = materialize()
        val args = args("library", listOf(ndk)) + "--configuration-cache"
        val first = fixture.buildTasks(listOf(":app:assembleDebug"), null, *args)
        assertEquals(Observed(setOf(ndk.providerFqn), setOf(ndk.name)), observe(fixture, listOf(ndk)))
        assertTrue(first.output, first.output.contains("Configuration cache entry stored"))
        // A cache hit on a clean tree: the rewrite and the strip both come from the stored values.
        fixture.projectDir.resolve("app/build").deleteRecursively()
        val second = fixture.buildTasks(listOf(":app:assembleDebug"), null, *args)
        assertTrue(second.output, second.output.contains("Reusing configuration cache"))
        assertEquals(Observed(setOf(ndk.providerFqn), setOf(ndk.name)), observe(fixture, listOf(ndk)))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** What a build produced: the known providers missing from the merged manifest, and the register calls in the dex. */
    private data class Observed(val strippedProviders: Set<String>, val injected: Set<String>)

    private fun materialize(): FixtureProject =
        FixtureProject.materialize("extension-layouts", temp.newFolder("project"))

    private val fakeRepo by lazy { FakeBugseeRepo.create(temp.newFolder("fake-repo")) }

    private fun args(layout: String, extensions: List<Extension>, vararg more: String): Array<String> = arrayOf(
        "-PbugseeLayout=$layout",
        "-PbugseeExtensions=${extensions.joinToString(",") { it.artifactId.removePrefix("bugsee-android-") }}",
        "-PbugseeFakeSdkRepo=${fakeRepo.absolutePath}",
        *more,
    )

    /**
     * Builds both variants of [layout] with [extensions] on the classpath and asserts the invariant per variant,
     * and that exactly [consolidated] (default: every extension) was consolidated.
     */
    private fun check(
        layout: String,
        extensions: List<Extension>,
        consolidated: List<Extension> = extensions,
        vararg more: String,
    ) {
        val fixture = materialize()
        for (variant in listOf("debug", "release")) {
            val result = fixture.buildTasks(listOf(":app:assemble${variant.replaceFirstChar { it.uppercase() }}"), null, *args(layout, extensions, *more))
            assertEquals(result.output, TaskOutcome.SUCCESS, result.task(":app:assemble${variant.replaceFirstChar { it.uppercase() }}")?.outcome)
            val seen = observe(fixture, extensions, variant)
            assertEquals(
                "[$layout/$variant] stripped providers and injected calls must name the same extensions",
                seen.strippedProviders, FakeBugseeRepo.ALL.filter { it.name in seen.injected }.map { it.providerFqn }.toSet(),
            )
            assertEquals(
                "[$layout/$variant] consolidated extensions",
                consolidated.map { it.providerFqn }.toSet(), seen.strippedProviders,
            )
            assertEquals("[$layout/$variant] core provider stays", true, manifestOf(fixture, variant).contains(FakeBugseeRepo.CORE_PROVIDER).or(layout == "none"))
            // providers of extensions that are on the classpath but not consolidated must still be in the manifest
            for (e in extensions - consolidated.toSet()) {
                assertTrue("[$layout/$variant] ${e.providerFqn} must stay in the manifest", manifestOf(fixture, variant).contains(e.providerFqn))
            }
        }
    }

    private fun manifestOf(fixture: FixtureProject, variant: String): String =
        requireNotNull(fixture.readMergedManifest(variant)) { "no merged manifest for $variant" }

    private fun observe(fixture: FixtureProject, onClasspath: List<Extension>, variant: String = "debug"): Observed {
        val manifest = manifestOf(fixture, variant)
        // "Stripped" = the merged manifest (after the plugin) no longer has a provider the layout put on the classpath.
        val stripped = onClasspath.filter { !manifest.contains(it.providerFqn) }.map { it.providerFqn }.toSet()
        val dex = dexStrings(fixture.projectDir, variant)
        val injected = FakeBugseeRepo.ALL.filter { FakeBugseeRepo.registerCall(it) in dex }.map { it.name }.toSet()
        return Observed(stripped, injected)
    }

    private fun dexStrings(root: File, variant: String): String =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "dex" && it.path.contains("/app/build/intermediates/") && it.path.contains(variant, ignoreCase = true) }
            .joinToString("\n") { String(it.readBytes(), Charsets.ISO_8859_1) }
}
