package com.bugsee.android.gradle.config

import com.bugsee.android.gradle.AppTokenProvider
import com.bugsee.android.gradle.BugseePluginExtension
import com.bugsee.android.gradle.upload.AppTokenResolver
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The placeholder app token (`00000000-0000-4000-8000-000000000000`) is treated as a missing token. */
class PlaceholderTokenTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val project: Project = ProjectBuilder.builder().build()
    private val logger = RecordingLogger()
    private val placeholder = AppTokenResolver.PLACEHOLDER_TOKEN
    private val ctx = VariantContext("stagingRelease", listOf("staging"), "release")

    private fun newExtension(): BugseePluginExtension =
        project.objects.newInstance(BugseePluginExtension::class.java)

    private fun resolver(global: BugseePluginExtension, props: String = "") =
        SettingsResolver(global, SettingsFileParser.parse(props, logger), project.objects, logger)

    private fun token(global: BugseePluginExtension, props: String = "") =
        resolver(global, props).appTokenFor(ctx, debug = false)

    @Test
    fun placeholderLiteralIsTheDocumentedValue() {
        assertEquals("00000000-0000-4000-8000-000000000000", placeholder)
    }

    @Test
    fun dslDefaultPlaceholderIsAbsent() {
        val global = newExtension()
        global.appToken(placeholder)
        assertNull(token(global))
        assertTrue(logger.infos.any { it.contains("placeholder") }, "infos: ${logger.infos}")
        assertTrue(logger.warnings.isEmpty(), "unexpected warnings: ${logger.warnings}")
    }

    @Test
    fun dslClosureProviderAndVariantBlockPlaceholdersAreAbsent() {
        val closure = newExtension().apply { appToken { placeholder } }
        assertNull(token(closure))

        val provider = newExtension().apply {
            appToken(object : AppTokenProvider {
                override fun getAppToken(variantName: String) = placeholder
            })
        }
        assertNull(token(provider))

        val block = newExtension().apply { variant("staging") { it.appToken(placeholder) } }
        assertNull(token(block))
    }

    @Test
    fun propertiesRootPlaceholderIsAbsent() {
        assertNull(token(newExtension(), "app_token=$placeholder\n"))
    }

    @Test
    fun propertiesVariantPlaceholderFallsThroughToRealRoot() {
        val props = "app_token=real-root-token\nvariant.staging.app_token=$placeholder\n"
        assertEquals("real-root-token", token(newExtension(), props))
    }

    @Test
    fun propertiesVariantAndRootPlaceholdersAreAbsent() {
        val props = "app_token=$placeholder\nvariant.staging.app_token=$placeholder\n"
        assertNull(token(newExtension(), props))
    }

    @Test
    fun dslPlaceholderFallsThroughToRealFileToken() {
        val global = newExtension()
        global.appToken(placeholder)
        assertEquals("real-file-token", token(global, "app_token=real-file-token\n"))
    }

    @Test
    fun legacyPropertiesReaderTreatsPlaceholderAsAbsent() {
        File(tmp.root, "bugsee.properties").writeText("app_token=$placeholder\n")
        assertNull(AppTokenResolver.resolveFromPropertiesFile(tmp.root, logger, debug = false))
    }

    @Test
    fun manifestPlaceholderIsAbsent() {
        assertNull(AppTokenResolver.resolveFromManifest(manifest(placeholder), emptyList(), logger, debug = false))
        assertTrue(logger.infos.any { it.contains("placeholder") }, "infos: ${logger.infos}")
    }

    @Test
    fun manifestStringResourcePlaceholderIsAbsent() {
        val strings = File(tmp.root, "strings.xml").apply {
            writeText("<resources><string name=\"bugsee_token\">$placeholder</string></resources>")
        }
        val result = AppTokenResolver.resolveFromManifest(
            manifest("@string/bugsee_token"), listOf(strings), logger, debug = false,
        )
        assertNull(result)
    }

    @Test
    fun realTokensStillWork() {
        val dsl = newExtension().apply { appToken("real-dsl-token") }
        assertEquals("real-dsl-token", token(dsl))
        assertEquals("real-file-token", token(newExtension(), "app_token=real-file-token\n"))
        assertEquals(
            "real-manifest-token",
            AppTokenResolver.resolveFromManifest(manifest("real-manifest-token"), emptyList(), logger, debug = false),
        )
        assertTrue(logger.infos.none { it.contains("placeholder") })
    }

    private fun manifest(tokenValue: String): File = File(tmp.root, "AndroidManifest.xml").apply {
        writeText(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="p">
                 <application><meta-data android:name="com.bugsee.android.APP_TOKEN" android:value="$tokenValue"/></application>
               </manifest>""",
        )
    }
}
