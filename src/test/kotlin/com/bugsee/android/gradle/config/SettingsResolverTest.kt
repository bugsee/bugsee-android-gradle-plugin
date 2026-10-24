package com.bugsee.android.gradle.config

import com.bugsee.android.gradle.BugseePluginExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsResolverTest {

    private val project: Project = ProjectBuilder.builder().build()
    private val logger = RecordingLogger()

    private val endpoint = def<String>("endpoint")
    private val debug = def<Boolean>("debug")
    private val ndkEnabled = def<Boolean>("ndk.enabled")
    private val feedback = def<Boolean>("feedback")

    private val stagingRelease = ctx("stagingRelease", listOf("staging"), "release")

    // ── Default mode ────────────────────────────────────────────────

    @Test
    fun dslVariantBeatsDslGlobal() {
        val global = newExtension()
        global.endpoint.set("https://global.example.com")
        global.variant("staging") { it.endpoint.set("https://staging-dsl.example.com") }

        val r = resolver(global).resolve(endpoint, stagingRelease)

        assertEquals(Resolved("https://staging-dsl.example.com", SettingSource.DSL_VARIANT), r)
    }

    @Test
    fun dslGlobalBeatsFileVariant() {
        val global = newExtension()
        global.endpoint.set("https://global.example.com")
        val file = file(variants = mapOf("staging" to mapOf("endpoint" to "https://staging-file.example.com")))

        val r = resolver(global, file).resolve(endpoint, stagingRelease)

        assertEquals(Resolved("https://global.example.com", SettingSource.DSL_GLOBAL), r)
    }

    @Test
    fun fileVariantBeatsFileRoot() {
        val file = file(
            root = mapOf("endpoint" to "https://root-file.example.com"),
            variants = mapOf("staging" to mapOf("endpoint" to "https://staging-file.example.com")),
        )

        val r = resolver(newExtension(), file).resolve(endpoint, stagingRelease)

        assertEquals(Resolved("https://staging-file.example.com", SettingSource.FILE_VARIANT), r)
    }

    @Test
    fun fileRootBeatsDefault() {
        val file = file(root = mapOf("endpoint" to "https://root-file.example.com"))
        val resolver = resolver(newExtension(), file)

        assertEquals(
            Resolved("https://root-file.example.com", SettingSource.FILE_ROOT),
            resolver.resolve(endpoint, stagingRelease),
        )
        assertEquals("https://root-file.example.com", resolver.effectiveFor(stagingRelease).endpoint.get())
    }

    @Test
    fun nothingSet_resolveIsNullAndEffectiveKeepsDefault() {
        val resolver = resolver(newExtension())

        assertNull(resolver.resolve(endpoint, stagingRelease))
        assertNull(resolver.resolve(endpoint, null))
        val effective = resolver.effectiveFor(stagingRelease)
        assertEquals("https://api.bugsee.com", effective.endpoint.get())
        assertEquals(false, effective.debug.get())
        assertNull(effective.instrumentation.okhttp.orNull)
    }

    // ── preferSettingsFile ──────────────────────────────────────────

    @Test
    fun fileVariantBeatsDslVariant() {
        val global = newExtension()
        global.preferSettingsFile.set(true)
        global.variant("staging") { it.endpoint.set("https://staging-dsl.example.com") }
        val file = file(variants = mapOf("staging" to mapOf("endpoint" to "https://staging-file.example.com")))

        val r = resolver(global, file).resolve(endpoint, stagingRelease)

        assertEquals(Resolved("https://staging-file.example.com", SettingSource.FILE_VARIANT), r)
    }

    @Test
    fun fileRootBeatsDslGlobal() {
        val global = newExtension()
        global.preferSettingsFile.set(true)
        global.endpoint.set("https://global.example.com")
        val file = file(root = mapOf("endpoint" to "https://root-file.example.com"))
        val resolver = resolver(global, file)

        val expected = Resolved("https://root-file.example.com", SettingSource.FILE_ROOT)
        assertEquals(expected, resolver.resolve(endpoint, stagingRelease))
        assertEquals(expected, resolver.resolve(endpoint, null))
    }

    @Test
    fun fileLacksKey_dslValueUsed() {
        val global = newExtension()
        global.preferSettingsFile.set(true)
        global.variant("staging") { it.endpoint.set("https://staging-dsl.example.com") }
        val file = file(
            root = mapOf("debug" to "true"),
            variants = mapOf("staging" to mapOf("debug" to "true")),
        )

        val r = resolver(global, file).resolve(endpoint, stagingRelease)

        assertEquals(Resolved("https://staging-dsl.example.com", SettingSource.DSL_VARIANT), r)
    }

    @Test
    fun preferSettingsFileIsReadOnEveryCall() {
        val global = newExtension()
        global.endpoint.set("https://global.example.com")
        val resolver = resolver(global, file(root = mapOf("endpoint" to "https://root-file.example.com")))

        assertEquals(SettingSource.DSL_GLOBAL, resolver.resolve(endpoint, null)?.source)
        global.preferSettingsFile.set(true)
        assertEquals(SettingSource.FILE_ROOT, resolver.resolve(endpoint, null)?.source)
    }

    // ── Lookup order ────────────────────────────────────────────────

    @Test
    fun variantNameBeatsFlavorBeatsBuildType() {
        assertEquals(listOf("stagingRelease", "staging", "release"), stagingRelease.lookupNames)

        // File.
        val all = mapOf(
            "stagingRelease" to mapOf("endpoint" to "https://variant.example.com"),
            "staging" to mapOf("endpoint" to "https://flavor.example.com"),
            "release" to mapOf("endpoint" to "https://buildtype.example.com"),
        )
        assertEquals(
            "https://variant.example.com",
            resolver(newExtension(), file(variants = all)).resolve(endpoint, stagingRelease)?.value,
        )
        assertEquals(
            "https://flavor.example.com",
            resolver(newExtension(), file(variants = all - "stagingRelease")).resolve(endpoint, stagingRelease)?.value,
        )
        assertEquals(
            "https://buildtype.example.com",
            resolver(newExtension(), file(variants = all - "stagingRelease" - "staging"))
                .resolve(endpoint, stagingRelease)?.value,
        )

        // DSL.
        fun dsl(vararg names: String): BugseePluginExtension = newExtension().also { ext ->
            for (n in names) ext.variant(n) { it.endpoint.set("https://dsl-$n.example.com") }
        }
        // Declaration order must not matter — only lookup order does.
        assertEquals(
            Resolved("https://dsl-stagingRelease.example.com", SettingSource.DSL_VARIANT),
            resolver(dsl("release", "staging", "stagingRelease")).resolve(endpoint, stagingRelease),
        )
        assertEquals(
            "https://dsl-staging.example.com",
            resolver(dsl("release", "staging")).resolve(endpoint, stagingRelease)?.value,
        )
        assertEquals(
            "https://dsl-release.example.com",
            resolver(dsl("release")).resolve(endpoint, stagingRelease)?.value,
        )
    }

    @Test
    fun nameThatIsFlavorAndBuildType_firstMatchWins() {
        // Variant "release" with build type "release" (no flavors): one name, looked up once.
        val release = ctx("release", emptyList(), "release")
        assertEquals(listOf("release"), release.lookupNames)

        val file = file(
            root = mapOf("debug" to "true"),
            variants = mapOf("release" to mapOf("endpoint" to "https://release.example.com")),
        )
        val resolver = resolver(newExtension(), file)
        assertEquals(
            Resolved("https://release.example.com", SettingSource.FILE_VARIANT),
            resolver.resolve(endpoint, release),
        )
        // A key the variant entry lacks falls through to root, per setting.
        assertEquals(Resolved(true, SettingSource.FILE_ROOT), resolver.resolve(debug, release))

        // Two matching entries: the earlier name wins outright; the later is not merged in.
        val both = file(
            variants = mapOf(
                "staging" to mapOf("endpoint" to "https://staging.example.com"),
                "release" to mapOf(
                    "endpoint" to "https://release.example.com",
                    "debug" to "true",
                ),
            ),
        )
        val r2 = resolver(newExtension(), both)
        assertEquals("https://staging.example.com", r2.resolve(endpoint, stagingRelease)?.value)
        // Per-setting: debug only exists on "release", so it is found there.
        assertEquals(Resolved(true, SettingSource.FILE_VARIANT), r2.resolve(debug, stagingRelease))
    }

    @Test
    fun multiFlavorUsesDimensionOrder() {
        val c = ctx("freeStagingRelease", listOf("free", "staging"), "release")
        assertEquals(listOf("freeStagingRelease", "free", "staging", "release"), c.lookupNames)

        val both = file(
            variants = mapOf(
                "staging" to mapOf("endpoint" to "https://staging.example.com"),
                "free" to mapOf("endpoint" to "https://free.example.com"),
            ),
        )
        assertEquals("https://free.example.com", resolver(newExtension(), both).resolve(endpoint, c)?.value)

        val stagingOnly = file(variants = mapOf("staging" to mapOf("endpoint" to "https://staging.example.com")))
        assertEquals("https://staging.example.com", resolver(newExtension(), stagingOnly).resolve(endpoint, c)?.value)
    }

    @Test
    fun unmatchedVariantName_affectsNoOtherVariant() {
        val file = file(variants = mapOf("stagin" to mapOf("endpoint" to "https://typo.example.com")))
        val resolver = resolver(newExtension(), file)

        assertNull(resolver.resolve(endpoint, stagingRelease))
        assertNull(resolver.resolve(endpoint, ctx("stagingDebug", listOf("staging"), "debug")))
        assertNull(resolver.resolve(endpoint, null))
        assertEquals("https://api.bugsee.com", resolver.effectiveFor(stagingRelease).endpoint.get())
    }

    // ── Variant block present but setting unset ────────────────────

    @Test
    fun blockWithoutSetting_fileVariantStillWins() {
        val global = newExtension()
        global.variant("staging") { it.debug.set(true) } // endpoint carries only its default
        val file = file(variants = mapOf("staging" to mapOf("endpoint" to "https://staging-file.example.com")))
        val resolver = resolver(global, file)

        assertEquals(
            Resolved("https://staging-file.example.com", SettingSource.FILE_VARIANT),
            resolver.resolve(endpoint, stagingRelease),
        )
        assertEquals(Resolved(true, SettingSource.DSL_VARIANT), resolver.resolve(debug, stagingRelease))
    }

    @Test
    fun blockWithoutSetting_dslGlobalWins() {
        val global = newExtension()
        global.endpoint.set("https://global.example.com")
        global.variant("staging") { it.debug.set(true) }

        assertEquals(
            Resolved("https://global.example.com", SettingSource.DSL_GLOBAL),
            resolver(global).resolve(endpoint, stagingRelease),
        )
    }

    @Test
    fun blockWithoutSetting_preferMode_fallsThroughToDslGlobal() {
        val global = newExtension()
        global.preferSettingsFile.set(true)
        global.endpoint.set("https://global.example.com")
        global.variant("staging") { it.debug.set(true) }
        // File has an entry for the variant, but not for endpoint.
        val file = file(variants = mapOf("staging" to mapOf("debug" to "false")))
        val resolver = resolver(global, file)

        assertEquals(
            Resolved("https://global.example.com", SettingSource.DSL_GLOBAL),
            resolver.resolve(endpoint, stagingRelease),
        )
        // Nothing explicit anywhere for endpoint below the block: no global DSL either.
        val noGlobal = newExtension().also {
            it.preferSettingsFile.set(true)
            it.variant("staging") { b -> b.debug.set(true) }
        }
        assertNull(resolver(noGlobal, file).resolve(endpoint, stagingRelease))
    }

    // ── Malformed file values ───────────────────────────────────────

    @Test
    fun malformedFileValue_warnsOnceAndIsSkipped() {
        val file = file(
            root = mapOf("debug" to "true"),
            variants = mapOf("staging" to mapOf("debug" to "maybe")),
        )
        val resolver = resolver(newExtension(), file)

        assertEquals(Resolved(true, SettingSource.FILE_ROOT), resolver.resolve(debug, stagingRelease))
        resolver.resolve(debug, stagingRelease)

        assertEquals(
            listOf("Bugsee: ignoring variant.staging.plugin.debug=maybe — " +
                "expected one of true/false (also accepted: yes/no, on/off, 1/0)"),
            logger.warnings,
        )
    }

    @Test
    fun malformedRootValue_notWarnedByResolver() {
        // PluginPropertiesApplier already reports malformed root values.
        val resolver = resolver(newExtension(), file(root = mapOf("debug" to "maybe")))

        assertNull(resolver.resolve(debug, stagingRelease))
        assertNull(resolver.resolve(debug, null))
        resolver.effectiveFor(stagingRelease)

        assertEquals(emptyList(), logger.warnings)
    }

    // ── Scope ───────────────────────────────────────────────────────

    @Test
    fun globalOnlyDefsCopiedFromGlobal() {
        val global = newExtension()
        global.cliPath.set("/opt/bugsee-cli")
        global.feedback.set(true)
        global.instrumentation.excludes.add("com.example.Skip")

        val effective = resolver(global).effectiveFor(stagingRelease)

        assertEquals("/opt/bugsee-cli", effective.cliPath.get())
        assertEquals(true, effective.feedback.get())
        assertEquals(setOf("com.example.Skip"), effective.instrumentation.excludes.get())
    }

    @Test
    fun variantFileKeyOnGlobalOnlyDefIsNotApplied() {
        // The parser already drops these; the resolver must not apply one either.
        val file = file(variants = mapOf("staging" to mapOf("feedback" to "true")))
        val resolver = resolver(newExtension(), file)

        assertNull(resolver.resolve(feedback, stagingRelease))
        assertEquals(false, resolver.effectiveFor(stagingRelease).feedback.get())
    }

    // ── Shadow warning ──────────────────────────────────────────────

    @Test
    fun shadowWarningOncePerKey() {
        val global = newExtension()
        global.endpoint.set("https://global.example.com")
        val file = file(
            root = mapOf("endpoint" to "https://root-file.example.com"),
            variants = mapOf("staging" to mapOf("endpoint" to "https://staging-file.example.com")),
        )
        val resolver = resolver(global, file)

        resolver.resolve(endpoint, stagingRelease)
        resolver.resolve(endpoint, stagingRelease)
        resolver.resolve(endpoint, ctx("stagingDebug", listOf("staging"), "debug"))
        resolver.resolve(endpoint, null)
        resolver.resolve(endpoint, null)

        assertEquals(
            listOf(
                "Bugsee: variant.staging.plugin.endpoint ignored: endpoint is set in the DSL " +
                    "(set preferSettingsFile to let the settings file win).",
            ),
            logger.warnings,
        )
    }

    @Test
    fun noShadowWarningForRootKey() {
        // DSL > root plugin.<key> is the long-standing documented precedence: no new noise.
        val global = newExtension()
        global.endpoint.set("https://global.example.com")
        global.cliPath.set("/opt/bugsee-cli")
        val file = file(root = mapOf("endpoint" to "https://root-file.example.com", "cliPath" to "/root/cli"))
        val resolver = resolver(global, file)

        resolver.resolve(endpoint, stagingRelease)
        resolver.resolve(endpoint, null)
        resolver.effectiveFor(stagingRelease)

        assertEquals(emptyList(), logger.warnings)
    }

    // ── Laziness ────────────────────────────────────────────────────

    @Test
    fun effectiveFollowsLateDslChanges() {
        val global = newExtension()
        global.endpoint.set("https://early.example.com")
        global.variant("staging") { it.debug.set(true) }
        val resolver = resolver(global, file(variants = mapOf("staging" to mapOf("ndk.enabled" to "true"))))
        val effective = resolver.effectiveFor(stagingRelease)

        // Changed after effectiveFor (e.g. a user's afterEvaluate running after AGP's onVariants).
        global.endpoint.set("https://late.example.com")
        global.cliVersion.set("9.9.9")
        global.variantBlocks.getValue("staging").debug.set(false)
        global.instrumentation.excludes.add("com.example.Late")

        assertEquals("https://late.example.com", effective.endpoint.get(), "DSL_GLOBAL is linked")
        assertEquals("9.9.9", effective.cliVersion.get(), "GLOBAL-scope setting is linked")
        assertEquals(false, effective.debug.get(), "DSL_VARIANT is linked to the block")
        assertEquals(setOf("com.example.Late"), effective.instrumentation.excludes.get())
        assertEquals(true, effective.ndk.enabled.get(), "FILE_VARIANT is a static file value")
        assertNull(effective.instrumentation.okhttp.orNull, "absent stays absent")
    }

    @Test
    fun effectiveFollowsLateDslOverRootFileConvention() {
        val text = "plugin.endpoint=https://root-file.example.com\n"
        val global = newExtension()
        PluginPropertiesApplier.applyText(text, global, RecordingLogger())
        val effective = resolver(global, SettingsFileParser.parse(text, RecordingLogger())).effectiveFor(stagingRelease)
        assertEquals("https://root-file.example.com", effective.endpoint.get())

        global.endpoint.set("https://late-dsl.example.com")

        assertEquals("https://late-dsl.example.com", effective.endpoint.get())
    }

    @Test
    fun preferMode_rootFileValueIsCopied() {
        val text = "plugin.endpoint=https://root-file.example.com\n"
        val global = newExtension()
        PluginPropertiesApplier.applyText(text, global, RecordingLogger())
        global.preferSettingsFile.set(true)
        global.endpoint.set("https://dsl.example.com")
        val effective = resolver(global, SettingsFileParser.parse(text, RecordingLogger())).effectiveFor(stagingRelease)

        global.endpoint.set("https://late-dsl.example.com")

        assertEquals("https://root-file.example.com", effective.endpoint.get())
    }

    @Test
    fun noShadowWarningInPreferMode() {
        val global = newExtension()
        global.preferSettingsFile.set(true)
        global.endpoint.set("https://global.example.com")
        global.variant("staging") { it.endpoint.set("https://staging-dsl.example.com") }
        val file = file(
            root = mapOf("endpoint" to "https://root-file.example.com"),
            variants = mapOf("staging" to mapOf("endpoint" to "https://staging-file.example.com")),
        )
        val resolver = resolver(global, file)

        resolver.resolve(endpoint, stagingRelease)
        resolver.resolve(endpoint, null)
        resolver.effectiveFor(stagingRelease)

        assertEquals(emptyList(), logger.warnings)
    }

    @Test
    fun noShadowWarningWhenDslNotSet() {
        val file = file(
            root = mapOf("endpoint" to "https://root-file.example.com"),
            variants = mapOf("staging" to mapOf("endpoint" to "https://staging-file.example.com")),
        )
        val resolver = resolver(newExtension(), file)

        resolver.resolve(endpoint, stagingRelease)
        resolver.resolve(endpoint, null)
        resolver.effectiveFor(stagingRelease)

        assertEquals(emptyList(), logger.warnings)
    }

    // ── anyScopeValue ───────────────────────────────────────────────

    @Test
    fun nullWhenNowhere() {
        assertNull(resolver(newExtension()).anyScopeValue(ndkEnabled))
    }

    @Test
    fun trueWhenOnlyAVariantEnables() {
        val viaFile = file(variants = mapOf("staging" to mapOf("ndk.enabled" to "true")))
        assertEquals(true, resolver(newExtension(), viaFile).anyScopeValue(ndkEnabled))

        val viaDsl = newExtension().also { ext -> ext.variant("release") { it.ndk.enabled.set(true) } }
        assertEquals(true, resolver(viaDsl).anyScopeValue(ndkEnabled))
    }

    @Test
    fun falseWhenAllSetFalse() {
        val global = newExtension()
        global.ndk.enabled.set(false)
        global.variant("release") { it.ndk.enabled.set(false) }
        val file = file(
            root = mapOf("ndk.enabled" to "false"),
            variants = mapOf("staging" to mapOf("ndk.enabled" to "false")),
        )

        assertEquals(false, resolver(global, file).anyScopeValue(ndkEnabled))
    }

    // ── globalValue ─────────────────────────────────────────────────

    @Test
    fun globalValueUsesFileRootInPreferMode() {
        val file = file(root = mapOf("endpoint" to "https://root-file.example.com"))

        val defaultMode = newExtension().also { it.endpoint.set("https://global.example.com") }
        assertEquals("https://global.example.com", resolver(defaultMode, file).globalValue(endpoint))

        val preferMode = newExtension().also {
            it.endpoint.set("https://global.example.com")
            it.preferSettingsFile.set(true)
        }
        assertEquals("https://root-file.example.com", resolver(preferMode, file).globalValue(endpoint))

        // Nothing set anywhere: the global property's own default.
        assertEquals(false, resolver(newExtension()).globalValue(debug))
    }

    // ── Backward compatibility ──────────────────────────────────────

    @Test
    fun noVariantInputs_effectiveMatchesGlobalForEverySetting() {
        val contexts = listOf(
            stagingRelease,
            ctx("debug", emptyList(), "debug"),
            ctx("freeStagingRelease", listOf("free", "staging"), "release"),
        )

        // Scenario A: no bugsee.properties, no DSL.
        assertEffectiveMatchesGlobal(newExtension(), SettingsFile.EMPTY, contexts)

        // Scenario B: root-only bugsee.properties (applied as conventions, exactly as
        // the plugin does today) plus DSL sets — some overlapping the file.
        val text = listOf(
            "app_token=ROOT_TOKEN",
            "plugin.endpoint=https://from-file.example.com",
            "plugin.debug=true",
            "plugin.feedback=true",
            "plugin.cliVersion=9.9.9",
            "plugin.ndk.enabled=true",
            "plugin.buildInfo.sizeCheck.warningPercent=12.5",
            "plugin.buildInfo.dependencies.maxCount=250",
            "plugin.instrumentation.okhttp=false",
            "plugin.instrumentation.startupTier=full",
        ).joinToString("\n", postfix = "\n")
        val global = newExtension()
        PluginPropertiesApplier.applyText(text, global, RecordingLogger())
        val file = SettingsFileParser.parse(text, RecordingLogger())
        assertTrue(file.variants.isEmpty())
        // User's bugsee { } block runs after apply.
        global.endpoint.set("https://from-dsl.example.com") // overlaps file
        global.ndk.enabled.set(false) // overlaps file
        global.cliPath.set("/opt/bugsee-cli")
        global.buildInfo.sizeCheck.failBytes.set(2_097_152L)
        global.instrumentation.enabled.set(false)
        global.instrumentation.excludes.add("com.example.Skip")

        assertEffectiveMatchesGlobal(global, file, contexts)
        // Sanity: the scenario exercises both layers.
        assertEquals("https://from-dsl.example.com", global.endpoint.get())
        assertEquals(true, global.debug.get())
    }

    private fun assertEffectiveMatchesGlobal(
        global: BugseePluginExtension,
        file: SettingsFile,
        contexts: List<VariantContext>,
    ) {
        val resolver = SettingsResolver(global, file, project.objects, RecordingLogger())
        for (c in contexts) {
            val effective = resolver.effectiveFor(c)
            for (def in SettingDefinitions.all) {
                assertEquals(
                    def.property(global).orNull,
                    def.property(effective).orNull,
                    "setting '${def.key}' differs for ${c.variantName}",
                )
            }
            assertEquals(
                global.instrumentation.excludes.orNull,
                effective.instrumentation.excludes.orNull,
                "instrumentation.excludes differs for ${c.variantName}",
            )
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun newExtension(): BugseePluginExtension =
        project.objects.newInstance(BugseePluginExtension::class.java)

    private fun resolver(global: BugseePluginExtension, file: SettingsFile = SettingsFile.EMPTY) =
        SettingsResolver(global, file, project.objects, logger)

    private fun file(
        root: Map<String, String> = emptyMap(),
        variants: Map<String, Map<String, String>> = emptyMap(),
    ) = SettingsFile(
        rootAppToken = null,
        rootPlugin = root,
        variants = variants.mapValues { (_, plugin) -> VariantFileEntries(null, plugin) },
    )

    private fun ctx(name: String, flavors: List<String>, buildType: String?) =
        VariantContext(name, flavors, buildType)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> def(key: String): SettingDef<T> =
        requireNotNull(SettingDefinitions.byKey(key)) { "no setting $key" } as SettingDef<T>
}
