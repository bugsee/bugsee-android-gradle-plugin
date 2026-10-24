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

class SettingsResolverTokenTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val project: Project = ProjectBuilder.builder().build()
    private val logger = RecordingLogger()

    private val stagingRelease = VariantContext("stagingRelease", listOf("staging"), "release")

    // ── Default mode ────────────────────────────────────────────────

    @Test
    fun closureBeatsEverything() {
        val global = newExtension()
        global.appToken { "closure-token" }
        global.appToken(provider("provider-token"))
        global.appToken("default-token")
        global.variant("staging") { it.appToken("block-token") }
        val file = tokens(root = "file-root-token", variants = mapOf("staging" to "file-staging-token"))

        assertEquals("closure-token", resolver(global, file).appTokenFor(stagingRelease, debug = false))
    }

    @Test
    fun closureNullFallsThrough() {
        val global = newExtension()
        global.appToken { null }
        global.appToken(provider("provider-token"))

        assertEquals("provider-token", resolver(global).appTokenFor(stagingRelease, debug = false))

        val emptyClosure = newExtension()
        emptyClosure.appToken { "" }
        emptyClosure.appToken("default-token")

        assertEquals("default-token", resolver(emptyClosure).appTokenFor(stagingRelease, debug = false))
    }

    @Test
    fun providerBeatsDslBlock() {
        val global = newExtension()
        global.appToken(provider("provider-token"))
        global.variant("staging") { it.appToken("block-token") }

        assertEquals("provider-token", resolver(global).appTokenFor(stagingRelease, debug = false))
    }

    @Test
    fun dslBlockBeatsDefaultAppToken() {
        val global = newExtension()
        global.appToken("default-token")
        global.variant("staging") { it.appToken("staging-block-token") }
        global.variant("release") { it.appToken("release-block-token") }

        // Flavor block comes before the build-type block in lookup order.
        assertEquals("staging-block-token", resolver(global).appTokenFor(stagingRelease, debug = false))

        // The variant-name block comes first of all.
        global.variant("stagingRelease") { it.appToken("variant-block-token") }
        assertEquals("variant-block-token", resolver(global).appTokenFor(stagingRelease, debug = false))
    }

    @Test
    fun defaultAppTokenBeatsFile() {
        val global = newExtension()
        global.appToken("default-token")
        val file = tokens(root = "file-root-token", variants = mapOf("staging" to "file-staging-token"))

        assertEquals("default-token", resolver(global, file).appTokenFor(stagingRelease, debug = false))
    }

    @Test
    fun fileVariantBeatsFileRoot() {
        val file = tokens(
            root = "file-root-token",
            variants = mapOf("staging" to "file-staging-token", "release" to "file-release-token"),
        )

        assertEquals("file-staging-token", resolver(newExtension(), file).appTokenFor(stagingRelease, debug = false))
    }

    @Test
    fun fileRootTokenWhenNoVariantEntry() {
        val file = tokens(root = "file-root-token", variants = mapOf("other" to "file-other-token"))

        assertEquals("file-root-token", resolver(newExtension(), file).appTokenFor(stagingRelease, debug = false))
    }

    @Test
    fun nothingSetIsNull() {
        assertNull(resolver(newExtension()).appTokenFor(stagingRelease, debug = false))
        assertTrue(logger.warnings.isEmpty(), "unexpected warnings: ${logger.warnings}")
    }

    /**
     * Backward compatibility: with no variant input (no DSL blocks, no file `variant.*` entries)
     * the new chain must return exactly what the legacy pair returns —
     * `resolveFromExtension` (closure, provider, defaultAppToken) then `resolveFromPropertiesFile`.
     */
    @Test
    fun noVariantInputs_chainMatchesLegacyOrder() {
        val closures: List<Pair<String, ((String) -> String?)?>> = listOf(
            "closure unset" to null,
            "closure returns null" to { _: String -> null },
            "closure returns empty" to { _: String -> "" },
            "closure returns token" to { _: String -> "closure-token" },
        )
        val providers: List<Pair<String, AppTokenProvider?>> = listOf(
            "provider unset" to null,
            "provider returns null" to provider(null),
            "provider returns token" to provider("provider-token"),
        )
        val defaults: List<String?> = listOf(null, "", "default-token")
        val rootTokens: List<String?> = listOf(null, "file-root-token")
        val ctx = VariantContext("release", emptyList(), "release")
        var combinations = 0

        for ((closureLabel, closure) in closures) {
            for ((providerLabel, prov) in providers) {
                for (default in defaults) {
                    for (rootToken in rootTokens) {
                        val global = newExtension()
                        closure?.let { global.appToken(it) }
                        prov?.let { global.appToken(it) }
                        default?.let { global.appToken(it) }

                        val dir = tmp.newFolder()
                        if (rootToken != null) File(dir, BUGSEE_PROPERTIES_FILENAME).writeText("app_token=$rootToken\n")
                        val legacy = AppTokenResolver.resolveFromExtension(global, ctx.variantName, logger, false)
                            ?: AppTokenResolver.resolveFromPropertiesFile(dir, logger, false)

                        val actual = resolver(global, tokens(root = rootToken)).appTokenFor(ctx, debug = false)

                        assertEquals(
                            legacy,
                            actual,
                            "$closureLabel, $providerLabel, default=$default, rootToken=$rootToken",
                        )
                        combinations++
                    }
                }
            }
        }
        assertEquals(72, combinations)
    }

    // ── preferSettingsFile ──────────────────────────────────────────

    @Test
    fun fileBeatsClosure() {
        val global = newExtension()
        global.preferSettingsFile.set(true)
        global.appToken { "closure-token" }
        global.appToken("default-token")
        val file = tokens(root = "file-root-token", variants = mapOf("staging" to "file-staging-token"))

        assertEquals("file-staging-token", resolver(global, file).appTokenFor(stagingRelease, debug = false))
        assertTrue(logger.warnings.isEmpty(), "no shadow warning in prefer mode: ${logger.warnings}")
    }

    @Test
    fun fileLacksToken_closureUsed() {
        val global = newExtension()
        global.preferSettingsFile.set(true)
        global.appToken { "closure-token" }
        val file = SettingsFile(null, mapOf("endpoint" to "https://file.example.com"), emptyMap())

        assertEquals("closure-token", resolver(global, file).appTokenFor(stagingRelease, debug = false))
    }

    // ── Review focus ────────────────────────────────────────────────

    @Test
    fun emptyVariantFileToken_fallsThroughToRoot() {
        val file = SettingsFileParser.parse(
            "app_token=file-root-token\nvariant.staging.app_token=\n",
            logger,
        )

        assertEquals("file-root-token", resolver(newExtension(), file).appTokenFor(stagingRelease, debug = false))
    }

    @Test
    fun tokenShadowWarningOnlyWhenDslReturnedValue() {
        val file = tokens(root = "file-root-token", variants = mapOf("staging" to "file-staging-token"))

        // A closure returning null is not a DSL value: the file wins, no warning.
        val nullClosure = newExtension()
        nullClosure.appToken { null }
        assertEquals("file-staging-token", resolver(nullClosure, file).appTokenFor(stagingRelease, debug = false))
        assertTrue(logger.warnings.isEmpty(), "unexpected warnings: ${logger.warnings}")

        // A DSL token shadows the file key that would have won; warned once per resolver.
        val dsl = newExtension()
        dsl.appToken("default-token")
        val r = resolver(dsl, file)
        assertEquals("default-token", r.appTokenFor(stagingRelease, debug = false))
        assertEquals("default-token", r.appTokenFor(stagingRelease, debug = false))
        assertEquals(
            listOf(
                "Bugsee: variant.staging.app_token ignored: an app token is set in the DSL " +
                    "(set preferSettingsFile to let the settings file win).",
            ),
            logger.warnings,
        )

        // Root key is named when no variant entry matches.
        logger.warnings.clear()
        resolver(dsl, tokens(root = "file-root-token")).appTokenFor(stagingRelease, debug = false)
        assertEquals(
            listOf(
                "Bugsee: app_token ignored: an app token is set in the DSL " +
                    "(set preferSettingsFile to let the settings file win).",
            ),
            logger.warnings,
        )

        // DSL token but no file token: no warning.
        logger.warnings.clear()
        resolver(dsl).appTokenFor(stagingRelease, debug = false)
        assertTrue(logger.warnings.isEmpty(), "unexpected warnings: ${logger.warnings}")
    }

    @Test
    fun debugLogsMaskTokenValues() {
        val global = newExtension()
        global.variant("staging") { it.appToken("block-token-1234") }

        resolver(global).appTokenFor(stagingRelease, debug = true)

        assertEquals(listOf("Bugsee: Using variant(\"staging\") appToken: bloc...1234"), logger.warnings)
        assertTrue(logger.warnings.none { it.contains("block-token-1234") })
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun newExtension(): BugseePluginExtension =
        project.objects.newInstance(BugseePluginExtension::class.java)

    private fun resolver(global: BugseePluginExtension, file: SettingsFile = SettingsFile.EMPTY) =
        SettingsResolver(global, file, project.objects, logger)

    private fun tokens(root: String? = null, variants: Map<String, String> = emptyMap()) = SettingsFile(
        rootAppToken = root,
        rootPlugin = emptyMap(),
        variants = variants.mapValues { (_, token) -> VariantFileEntries(token, emptyMap()) },
    )

    private fun provider(token: String?) = object : AppTokenProvider {
        override fun getAppToken(variantName: String): String? = token
    }
}
