package com.bugsee.android.gradle.config

import com.bugsee.android.gradle.BugseePluginExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test
import kotlin.test.assertEquals

/**
 * With no per-variant input (no `variant { }` block, no `variant.*` key) and no
 * `preferSettingsFile`, the resolver must use the global properties exactly as the
 * code before per-variant settings did: linked or read with plain `get()`/`orNull`,
 * never probed (which reads and rewrites conventions) and never read early.
 */
class SettingsResolverFastPathTest {

    private val project: Project = ProjectBuilder.builder().build()
    private val logger = RecordingLogger()
    private val ctx = VariantContext("stagingRelease", listOf("staging"), "release")

    private val debug = SettingDefinitions.booleanByKey("debug")
    private val ndkEnabled = SettingDefinitions.booleanByKey("ndk.enabled")
    private val okhttp = SettingDefinitions.booleanByKey("instrumentation.okhttp")

    // (a) finalized global properties

    @Test
    fun finalizedGlobalProperties_noVariantInput_doNotThrow() {
        val global = applied("plugin.ndk.enabled=true\n")
        global.endpoint.set("https://final.example.com")
        global.endpoint.finalizeValueOnRead()
        global.debug.set(true)
        global.debug.disallowChanges()
        global.ndk.enabled.finalizeValueOnRead()
        global.instrumentation.okhttp.set(false)
        global.instrumentation.okhttp.disallowChanges()
        val resolver = resolver(global, "plugin.ndk.enabled=true\n")

        val effective = resolver.effectiveFor(ctx)

        assertEquals("https://final.example.com", effective.endpoint.get())
        assertEquals(true, effective.debug.get())
        assertEquals(true, effective.ndk.enabled.get())
        assertEquals(true, resolver.globalValue(debug))
        assertEquals(true, resolver.anyScopeValue(ndkEnabled))
        assertEquals(false, resolver.anyScopeValue(okhttp))
    }

    @Test
    fun finalizedGlobalProperty_withCompetingVariantFileKey_doesNotThrow() {
        val text = "variant.staging.plugin.endpoint=https://staging-file.example.com\n"
        val global = applied(text)
        global.endpoint.set("https://final.example.com")
        global.endpoint.disallowChanges()

        val effective = resolver(global, text).effectiveFor(ctx)

        assertEquals("https://final.example.com", effective.endpoint.get(), "explicit DSL value still wins")
    }

    // (b) no early reads

    @Test
    fun noVariantInput_globalPropertiesAreNotQueriedEarly() {
        var queries = 0
        val global = applied(null)
        global.cliPath.set(project.provider { queries++; "/opt/bugsee-cli" })
        global.endpoint.set(project.provider { queries++; "https://lazy.example.com" })
        global.instrumentation.okhttp.set(project.provider { queries++; true })
        val resolver = resolver(global, null)

        val effective = resolver.effectiveFor(ctx)
        resolver.globalValue(debug)
        resolver.globalValueOrNull(ndkEnabled)

        assertEquals(0, queries, "nothing reads the lazy values while wiring")
        assertEquals("/opt/bugsee-cli", effective.cliPath.get())
        assertEquals(1, queries, "read only when the consumer reads it")
    }

    // (c) a user convention set after the file was layered in

    @Test
    fun userConventionAfterFile_winsAndIsNotRewritten() {
        val text = "plugin.endpoint=https://root-file.example.com\n"
        val global = applied(text)
        var conventionQueries = 0
        global.endpoint.convention(project.provider { conventionQueries++; "https://user-convention.example.com" })
        val resolver = resolver(global, text)

        val effective = resolver.effectiveFor(ctx)
        val endpoint = SettingDefinitions.byKey("endpoint")!!

        assertEquals(0, conventionQueries, "the convention is neither read nor frozen while wiring")
        assertEquals("https://user-convention.example.com", effective.endpoint.get(), "the later convention wins (old behaviour)")
        assertEquals("https://user-convention.example.com", resolver.globalValue(endpoint))

        global.endpoint.convention("https://later.example.com")
        assertEquals("https://later.example.com", effective.endpoint.get(), "still the live global convention")
    }

    // ── Helpers ─────────────────────────────────────────────────────

    /** A global extension with [text]'s root keys layered in, as `PluginPropertiesApplier` does at apply time. */
    private fun applied(text: String?): BugseePluginExtension =
        project.objects.newInstance(BugseePluginExtension::class.java).also {
            PluginPropertiesApplier.applyText(text, it, RecordingLogger())
        }

    private fun resolver(global: BugseePluginExtension, text: String?) =
        SettingsResolver(global, SettingsFileParser.parse(text, RecordingLogger()), project.objects, logger)
}
