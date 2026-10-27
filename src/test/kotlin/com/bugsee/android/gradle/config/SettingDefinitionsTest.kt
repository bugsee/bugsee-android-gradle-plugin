package com.bugsee.android.gradle.config

import com.bugsee.android.gradle.BugseePluginExtension
import com.bugsee.android.gradle.StartupTier
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class SettingDefinitionsTest {

    private val expectedKeys = listOf(
        "endpoint", "cliPath", "cliVersion", "cliAutoUpdate", "uploader", "debug", "failOnUploadError",
        "feedback", "optimizeExtensionsLoading", "sdkAutoLoad",
        "ndk.enabled", "ndk.allBuildTypes", "ndk.forceDebugSymbolsUpload", "ndk.useMergedNativeLibs",
        "leak.enabled",
        "buildInfo.enabled", "buildInfo.allBuildTypes",
        "buildInfo.sizeAnalysis.enabled", "buildInfo.sizeAnalysis.buildConfiguration",
        "buildInfo.sizeAnalysis.chunkedUpload",
        "buildInfo.sizeCheck.enabled", "buildInfo.sizeCheck.warningPercent",
        "buildInfo.sizeCheck.failPercent", "buildInfo.sizeCheck.warningBytes",
        "buildInfo.sizeCheck.failBytes",
        "buildInfo.dependencies.enabled", "buildInfo.dependencies.scope",
        "buildInfo.dependencies.includeSelectedReason", "buildInfo.dependencies.maxCount",
        "buildInfo.timings.enabled",
        "instrumentation.enabled", "instrumentation.okhttp", "instrumentation.httpEngine",
        "instrumentation.log", "instrumentation.thread", "instrumentation.mainThreadMisuse",
        "instrumentation.operationDispatch", "instrumentation.compose",
        "instrumentation.composeSecure", "instrumentation.composeInput",
        "instrumentation.ktor", "instrumentation.cronet", "instrumentation.startupTier",
    )

    private val globalKeys = setOf(
        "cliPath", "cliVersion", "cliAutoUpdate", "uploader", "feedback", "sdkAutoLoad", "leak.enabled",
    )

    private fun def(key: String) = assertNotNull(SettingDefinitions.byKey(key), key)

    @Test
    fun allKeysMatchTodaysBindings() {
        assertEquals(expectedKeys, SettingDefinitions.all.map { it.key })
        assertNull(SettingDefinitions.byKey("instrumentation.excludes"))
        assertNull(SettingDefinitions.byKey("preferSettingsFile"))
    }

    @Test
    fun scopesMatchSpec() {
        for (d in SettingDefinitions.all) {
            val expected = if (d.key in globalKeys) SettingScope.GLOBAL else SettingScope.VARIANT
            assertEquals(expected, d.scope, d.key)
        }
        assertEquals(
            expectedKeys.filter { it !in globalKeys },
            SettingDefinitions.variantScoped().map { it.key },
        )
    }

    @Test
    fun parseBoolAliases() {
        val d = def("debug")
        for (s in listOf("true", "YES", " on ", "1")) assertEquals(true, d.parse(s), s)
        for (s in listOf("false", "No", "off", "0")) assertEquals(false, d.parse(s), s)
        val e = assertFailsWith<IllegalArgumentException> { d.parse("maybe") }
        assertEquals("expected one of true/false (also accepted: yes/no, on/off, 1/0)", e.message)
    }

    @Test
    fun parseRejectsEmptyString() {
        val e = assertFailsWith<IllegalArgumentException> { def("endpoint").parse("   ") }
        assertEquals(
            "value is empty — leave the key out of bugsee.properties to use the default",
            e.message,
        )
        assertEquals("https://x/y", def("endpoint").parse("  https://x/y "))
    }

    @Test
    fun parseEnumCaseInsensitive() {
        assertEquals(StartupTier.values().first(), def("instrumentation.startupTier")
            .parse(StartupTier.values().first().name.lowercase()))
        assertFailsWith<IllegalArgumentException> { def("instrumentation.startupTier").parse("nope") }
    }

    @Test
    fun propertyAccessorReturnsTheLiveProperty() {
        val project = ProjectBuilder.builder().build()
        val ext = project.extensions.create("bugsee", BugseePluginExtension::class.java)
        assertSame(ext.endpoint, def("endpoint").property(ext))
        assertSame(ext.buildInfo.sizeCheck.failBytes, def("buildInfo.sizeCheck.failBytes").property(ext))
    }

    @Test
    fun `cliPath file value resolves against the file dir, other strings are untouched`() {
        val dir = java.io.File("/some/root")
        val cli = def("cliPath")
        assertEquals(java.io.File(dir, "tools/cli").path, cli.parseFileValue(" tools/cli ", dir))
        assertEquals("/abs/x/../cli", cli.parseFileValue("/abs/x/../cli", dir))
        assertEquals("tools/cli", cli.parseFileValue("tools/cli", null))
        assertEquals("https://x/y", def("endpoint").parseFileValue("https://x/y", dir))
    }
}
