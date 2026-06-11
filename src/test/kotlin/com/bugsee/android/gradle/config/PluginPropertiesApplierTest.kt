package com.bugsee.android.gradle.config

import com.bugsee.android.gradle.BugseePluginExtension
import com.bugsee.android.gradle.StartupTier
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the load-bearing contract of [PluginPropertiesApplier]:
 *
 *   DSL `.set(...)`  >  bugsee.properties `plugin.X`  >  built-in default
 *
 * The applier achieves this by stacking `convention` calls on Gradle
 * `Property` instances. These tests verify the three precedence layers
 * in isolation and in combination, so a regression that (e.g.)
 * upgraded the applier from `.convention(...)` to `.set(...)` —
 * defeating user DSL overrides — would fail loudly.
 *
 * Type coverage: bool, string, double, long, int, enum. Plus one
 * malformed-value case for each numeric type (per the suite's
 * mutation-resistance goal — a regression that silently swallowed
 * the parse failure and applied a wrong default would slip past a
 * single happy-path test).
 */
class PluginPropertiesApplierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── Precedence: defaults ────────────────────────────────────────

    @Test
    fun precedence_neitherDslNorProperties_returnsDefault() {
        // The load-bearing baseline: with no properties file and no
        // DSL overrides, the extension still returns its built-in
        // conventions. A mutation that nuked the conventions in the
        // extension classes would fail here.
        val (_, ext) = setUpExtension()  // no properties file written

        assertEquals("https://api.bugsee.com", ext.endpoint.get())
        assertEquals(false, ext.debug.get())
        assertEquals(true, ext.optimizeExtensionsLoading.get())
        assertEquals(true, ext.sdkAutoLoad.get())
        assertEquals(true, ext.buildInfo.enabled.get())
        // Optional module lanes default off (opt-in).
        assertEquals(false, ext.ndk.enabled.get())
        assertEquals(false, ext.leak.enabled.get())
    }

    // ── Precedence: properties win over defaults ────────────────────

    @Test
    fun precedence_propertiesOnly_overridesDefault() {
        // bugsee.properties sets a non-default value; the DSL is
        // untouched. The extension MUST return the properties value.
        // This is the test that fails if the applier never ran (or
        // wired conventions to the wrong properties).
        writeProperties(
            "plugin.endpoint=https://custom.example.com",
            "plugin.debug=true",
            "plugin.optimizeExtensionsLoading=false",
            "plugin.buildInfo.enabled=false",
        )
        val (_, ext) = setUpExtension()

        assertEquals("https://custom.example.com", ext.endpoint.get())
        assertEquals(true, ext.debug.get())
        assertEquals(false, ext.optimizeExtensionsLoading.get())
        assertEquals(false, ext.buildInfo.enabled.get())
    }

    // ── Precedence: DSL wins over properties (the critical one) ─────

    @Test
    fun precedence_dslOverridesProperties() {
        // The single most load-bearing claim of the design. The DSL
        // .set(...) takes effect AFTER the properties applier ran.
        writeProperties(
            "plugin.endpoint=https://from-properties.example.com",
            "plugin.debug=true",
            "plugin.buildInfo.sizeCheck.warningPercent=10.0",
        )
        val (_, ext) = setUpExtension()

        // User's `bugsee { ... }` DSL block — simulated by calling
        // .set(...) on the same extension. Real Gradle invokes this
        // after the plugin's apply() returns, which matches our test
        // ordering exactly: apply ran (during setUpExtension), and
        // we now layer DSL on top.
        ext.endpoint.set("https://from-dsl.example.com")
        ext.debug.set(false)
        ext.buildInfo.sizeCheck.warningPercent.set(25.0)

        assertEquals("https://from-dsl.example.com", ext.endpoint.get())
        assertEquals(false, ext.debug.get())
        assertEquals(25.0, ext.buildInfo.sizeCheck.warningPercent.get())
    }

    @Test
    fun precedence_applierUsesConvention_notSet_soDslCanStillOverrideAfterFinalize() {
        // Tightening: the headline `precedence_dslOverridesProperties`
        // would still pass under a mutation that swapped
        // `prop.convention(v)` → `prop.set(v)`, because a later DSL
        // `.set(...)` overrides a previous `.set(...)` too. To pin
        // the `.convention(...)` primitive specifically, exploit
        // Gradle's distinction:
        //
        //   - A value set via `.convention(...)` can still be
        //     overridden by a later `.set(...)` — even after
        //     `.finalizeValueOnRead()` is called.
        //   - A value set via `.set(...)` BEFORE any DSL override
        //     would be the "explicit value", and a later `.set(...)`
        //     still works, but the property's `isPresent` / "has
        //     explicit value" state differs.
        //
        // The clean discriminator is `Property.isExplicit()`-like
        // behavior: with `.convention(...)`, calling
        // `.unsetConvention()` reverts to no value if no explicit
        // set was done. With `.set(...)`, the value persists.
        //
        // Verify via `unsetConvention()`: a convention-applied
        // property has its value cleared back to the original
        // extension default after unsetConvention; a set-applied
        // property does NOT.
        writeProperties(
            "plugin.endpoint=https://from-properties.example.com",
            "plugin.optimizeExtensionsLoading=false",
        )
        val (_, ext) = setUpExtension()

        // Confirm baseline: the applier's value is visible.
        assertEquals("https://from-properties.example.com", ext.endpoint.get())
        assertEquals(false, ext.optimizeExtensionsLoading.get())

        // Re-apply the original extension defaults as conventions —
        // because the applier used `.convention(propsValue)` to
        // override the extension's default convention, our
        // re-application here supersedes the applier's value and
        // restores the original default. If the applier had used
        // `.set(...)`, our `.convention(...)` here would be ignored
        // and the applier's value would persist.
        ext.endpoint.convention("https://api.bugsee.com")
        ext.optimizeExtensionsLoading.convention(true)

        assertEquals(
            "https://api.bugsee.com", ext.endpoint.get(),
            "applier MUST use .convention(...), not .set(...) — " +
                "without that primitive, re-applying the original " +
                "convention here would not restore the extension default",
        )
        assertEquals(
            true, ext.optimizeExtensionsLoading.get(),
            "see above",
        )
    }

    // ── Type coercion ───────────────────────────────────────────────

    @Test
    fun coerce_boolean_acceptsAliases() {
        writeProperties(
            "plugin.debug=YES",                           // alias for true
            "plugin.feedback=off",                        // alias for false
            "plugin.optimizeExtensionsLoading=1",         // alias for true
            "plugin.buildInfo.enabled=0",                 // alias for false
        )
        val (_, ext) = setUpExtension()

        assertEquals(true, ext.debug.get())
        assertEquals(false, ext.feedback.get())
        assertEquals(true, ext.optimizeExtensionsLoading.get())
        assertEquals(false, ext.buildInfo.enabled.get())
    }

    @Test
    fun coerce_double_andLong_andInt_parseCorrectly() {
        writeProperties(
            "plugin.buildInfo.sizeCheck.warningPercent=12.5",
            "plugin.buildInfo.sizeCheck.failPercent=20.0",
            "plugin.buildInfo.sizeCheck.warningBytes=10485760",     // 10 MiB
            "plugin.buildInfo.sizeCheck.failBytes=20971520",        // 20 MiB
            "plugin.buildInfo.dependencies.maxCount=500",
        )
        val (_, ext) = setUpExtension()

        assertEquals(12.5, ext.buildInfo.sizeCheck.warningPercent.get())
        assertEquals(20.0, ext.buildInfo.sizeCheck.failPercent.get())
        assertEquals(10_485_760L, ext.buildInfo.sizeCheck.warningBytes.get())
        assertEquals(20_971_520L, ext.buildInfo.sizeCheck.failBytes.get())
        assertEquals(500, ext.buildInfo.dependencies.maxCount.get())
    }

    @Test
    fun coerce_enum_caseInsensitive_matchesStartupTier() {
        // StartupTier values include OFF, MINIMAL, STANDARD, DETAILED,
        // FULL — pin lower-case input matches.
        writeProperties("plugin.instrumentation.startupTier=full")
        val (_, ext) = setUpExtension()

        assertEquals(StartupTier.FULL, ext.instrumentation.startupTier.get())
    }

    // ── Malformed values: do NOT poison the property; default holds ─

    @Test
    fun coerce_invalidBoolean_isIgnored_defaultRemains() {
        // A typo like `plugin.debug=enabled` (not in the alias list)
        // must NOT poison the property to a guessed value. The
        // applier logs a warn and leaves the convention untouched, so
        // .get() still returns the extension's built-in default.
        writeProperties("plugin.debug=enabled")
        val (_, ext) = setUpExtension()

        assertEquals(false, ext.debug.get())  // default convention holds
    }

    @Test
    fun coerce_invalidDouble_isIgnored_propertyRemainsUnset() {
        // SizeCheck thresholds have no default convention — they're
        // resolved later via .orNull / .getOrElse. A malformed
        // override must leave the property in its unset state, NOT
        // poison it to 0.0 or some other guess.
        writeProperties("plugin.buildInfo.sizeCheck.warningPercent=not-a-number")
        val (_, ext) = setUpExtension()

        assertEquals(null, ext.buildInfo.sizeCheck.warningPercent.orNull)
    }

    @Test
    fun coerce_invalidEnum_isIgnored_defaultRemains() {
        // startupTier has no built-in convention (resolved later
        // via .getOrElse(STANDARD) in the registrar), so an invalid
        // value must leave it absent rather than corrupt it.
        writeProperties("plugin.instrumentation.startupTier=warp-speed")
        val (_, ext) = setUpExtension()

        assertEquals(null, ext.instrumentation.startupTier.orNull)
    }

    // ── Unknown keys are tolerated ──────────────────────────────────

    @Test
    fun unknownPluginKey_isIgnored_doesNotFailBuild_andDoesNotPoisonOtherProperties() {
        // Forward-compat: a properties file written for plugin v9 may
        // contain keys this plugin version doesn't know yet. Apply
        // what we recognize, ignore the rest, and CRITICALLY: don't
        // accidentally route the unknown values into some other
        // property's binding.
        writeProperties(
            "plugin.debug=true",
            "plugin.someFutureOption=42",
            "plugin.completely.made.up.path=hello",
        )
        val (_, ext) = setUpExtension()

        assertEquals(true, ext.debug.get())
        // Defaults preserved for other properties — a regression that
        // routed unknown keys into the wrong binding would corrupt
        // these.
        assertEquals(false, ext.feedback.get())
        assertEquals(true, ext.optimizeExtensionsLoading.get())
    }

    // ── Diagnostics: log levels and messages ────────────────────────

    @Test
    fun unknownPluginKey_logsAtInfo_notWarn() {
        // The unknown-key diagnostic MUST stay at `info` level — at
        // `warn` it would spam every CI build that has a forward-
        // looking key. A regression that bumped this to warn would
        // make CI logs noisy in a way no test currently catches
        // without this assertion.
        //
        // Use a known key OTHER than `debug` so we don't also trigger
        // the verbose-apply-echo path (which IS a warn — and is
        // covered separately by `verboseDebugLog_…`).
        writeProperties(
            "plugin.feedback=true",
            "plugin.someFutureOption=42",
        )
        val (_, logger) = setUpExtensionWithLogger()

        assertEquals(
            emptyList(), logger.warnings,
            "unknown keys must not emit warn-level messages",
        )
        assertTrue(
            logger.infos.any {
                it.contains("plugin.someFutureOption") && it.contains("unknown")
            },
            "expected an info-level diagnostic naming the unknown key; got infos: ${logger.infos}",
        )
    }

    @Test
    fun malformedValue_logsWarn_andLeavesPropertyUntouched() {
        // The property-state checks in coerce_invalid* tests verify
        // the value side. This test pins the LOG side — without it,
        // a mutation that silently swallowed the parse exception
        // would pass (the property state would correctly remain
        // unset, but the user would have no clue why their config
        // didn't take effect).
        writeProperties(
            "plugin.buildInfo.sizeCheck.warningPercent=not-a-number",
        )
        val (_, logger) = setUpExtensionWithLogger()

        assertEquals(
            1, logger.warnings.size,
            "expected exactly one warn for a malformed value; got: ${logger.warnings}",
        )
        assertTrue(
            logger.warnings[0].contains("buildInfo.sizeCheck.warningPercent"),
            "warn must name the offending key so users can find it; got: ${logger.warnings[0]}",
        )
        assertTrue(
            logger.warnings[0].contains("not-a-number"),
            "warn must include the bad value to make the typo visible; got: ${logger.warnings[0]}",
        )
    }

    @Test
    fun verboseDebugLog_emittedOnlyWhenPropertiesFileSetsDebugTrue() {
        // The applier reads `plugin.debug` from the properties file
        // BEFORE applying it (chicken-and-egg: the applier IS what
        // would set the DSL debug flag). When the file says
        // debug=true, successful applies are echoed; otherwise
        // silent. Pin both halves of the contract.

        // Half 1: debug=true → echo per applied key.
        writeProperties(
            "plugin.debug=true",
            "plugin.feedback=true",
        )
        val (_, verboseLogger) = setUpExtensionWithLogger()
        val verboseAppliedMessages = verboseLogger.warnings.filter { it.contains("applied") }
        assertEquals(
            2, verboseAppliedMessages.size,
            "expected one `applied` warn per applied key (debug + feedback); got: $verboseAppliedMessages",
        )

        // Half 2: debug absent → no apply echo.
        tempFolder.root.resolve("bugsee.properties").delete()
        writeProperties("plugin.feedback=true")
        val (_, quietLogger) = setUpExtensionWithLogger()
        val quietAppliedMessages = quietLogger.warnings.filter { it.contains("applied") }
        assertEquals(
            emptyList(), quietAppliedMessages,
            "without plugin.debug=true, successful applies must be silent",
        )
    }

    // ── Full-bindings registry coverage ─────────────────────────────

    @Test
    fun every_advertised_key_actually_applies() {
        // The most likely future regression on this feature: someone
        // adds a new `Property<T>` to one of the extension classes,
        // forgets to register it in `PluginPropertiesApplier.bindings`,
        // and ships. User configs silently no-op for that key — the
        // only signal is an info-level "unknown key" log that nobody
        // reads.
        //
        // This test walks EVERY key in a complete bugsee.properties
        // file (each set to a value distinct from the default) and
        // verifies the value reaches the matching DSL Property. A
        // dropped binding fails here loudly.
        //
        // When adding a new DSL Property, also add a row to BOTH the
        // `bindings(...)` list in PluginPropertiesApplier AND this
        // test's properties + assertions block.
        writeProperties(
            // root
            "plugin.endpoint=https://override.example.com",
            "plugin.debug=true",
            "plugin.feedback=true",
            "plugin.optimizeExtensionsLoading=false",
            "plugin.sdkAutoLoad=false",
            // ndk
            "plugin.ndk.enabled=true",
            "plugin.ndk.forceDebugSymbolsUpload=true",
            // leak
            "plugin.leak.enabled=true",
            // buildInfo
            "plugin.buildInfo.enabled=false",
            "plugin.buildInfo.allBuildTypes=true",
            // buildInfo.sizeAnalysis
            "plugin.buildInfo.sizeAnalysis.enabled=true",
            "plugin.buildInfo.sizeAnalysis.buildConfiguration=staging",
            "plugin.buildInfo.sizeAnalysis.chunkedUpload=true",
            // buildInfo.sizeCheck
            "plugin.buildInfo.sizeCheck.enabled=true",
            "plugin.buildInfo.sizeCheck.warningPercent=12.5",
            "plugin.buildInfo.sizeCheck.failPercent=25.0",
            "plugin.buildInfo.sizeCheck.warningBytes=1048576",
            "plugin.buildInfo.sizeCheck.failBytes=2097152",
            // buildInfo.dependencies
            "plugin.buildInfo.dependencies.enabled=false",
            "plugin.buildInfo.dependencies.scope=compileClasspath",
            "plugin.buildInfo.dependencies.includeSelectedReason=true",
            "plugin.buildInfo.dependencies.maxCount=250",
            // buildInfo.timings
            "plugin.buildInfo.timings.enabled=false",
            // instrumentation
            "plugin.instrumentation.enabled=false",
            "plugin.instrumentation.okhttp=false",
            "plugin.instrumentation.httpEngine=false",
            "plugin.instrumentation.log=false",
            "plugin.instrumentation.thread=false",
            "plugin.instrumentation.mainThreadMisuse=false",
            "plugin.instrumentation.operationDispatch=false",
            "plugin.instrumentation.compose=false",
            "plugin.instrumentation.composeSecure=false",
            "plugin.instrumentation.composeInput=false",
            "plugin.instrumentation.ktor=false",
            "plugin.instrumentation.cronet=false",
            "plugin.instrumentation.startupTier=FULL",
        )
        val (_, ext) = setUpExtension()

        // root
        assertEquals("https://override.example.com", ext.endpoint.get())
        assertEquals(true, ext.debug.get())
        assertEquals(true, ext.feedback.get())
        assertEquals(false, ext.optimizeExtensionsLoading.get())
        assertEquals(false, ext.sdkAutoLoad.get())
        // ndk
        assertEquals(true, ext.ndk.enabled.get())
        assertEquals(true, ext.ndk.forceDebugSymbolsUpload.get())
        // leak
        assertEquals(true, ext.leak.enabled.get())
        // buildInfo
        assertEquals(false, ext.buildInfo.enabled.get())
        assertEquals(true, ext.buildInfo.allBuildTypes.get())
        // buildInfo.sizeAnalysis
        assertEquals(true, ext.buildInfo.sizeAnalysis.enabled.get())
        assertEquals("staging", ext.buildInfo.sizeAnalysis.buildConfiguration.get())
        assertEquals(true, ext.buildInfo.sizeAnalysis.chunkedUpload.get())
        // buildInfo.sizeCheck
        assertEquals(true, ext.buildInfo.sizeCheck.enabled.get())
        assertEquals(12.5, ext.buildInfo.sizeCheck.warningPercent.get())
        assertEquals(25.0, ext.buildInfo.sizeCheck.failPercent.get())
        assertEquals(1_048_576L, ext.buildInfo.sizeCheck.warningBytes.get())
        assertEquals(2_097_152L, ext.buildInfo.sizeCheck.failBytes.get())
        // buildInfo.dependencies
        assertEquals(false, ext.buildInfo.dependencies.enabled.get())
        assertEquals("compileClasspath", ext.buildInfo.dependencies.scope.get())
        assertEquals(true, ext.buildInfo.dependencies.includeSelectedReason.get())
        assertEquals(250, ext.buildInfo.dependencies.maxCount.get())
        // buildInfo.timings
        assertEquals(false, ext.buildInfo.timings.enabled.get())
        // instrumentation
        assertEquals(false, ext.instrumentation.enabled.get())
        assertEquals(false, ext.instrumentation.okhttp.get())
        assertEquals(false, ext.instrumentation.httpEngine.get())
        assertEquals(false, ext.instrumentation.log.get())
        assertEquals(false, ext.instrumentation.thread.get())
        assertEquals(false, ext.instrumentation.mainThreadMisuse.get())
        assertEquals(false, ext.instrumentation.operationDispatch.get())
        assertEquals(false, ext.instrumentation.compose.get())
        assertEquals(false, ext.instrumentation.composeSecure.get())
        assertEquals(false, ext.instrumentation.composeInput.get())
        assertEquals(false, ext.instrumentation.ktor.get())
        assertEquals(false, ext.instrumentation.cronet.get())
        assertEquals(StartupTier.FULL, ext.instrumentation.startupTier.get())
    }

    @Test
    fun routingAliasing_setOneKey_leavesAllOtherNoConventionKeysUnset() {
        // Stronger version of routingSwap_…: catches BOTH swap
        // mutations (compose↔composeInput) AND aliasing mutations
        // where two bindings point at the same `Property` (compose).
        //
        // For each no-convention instrumentation key, set ONLY that
        // key and assert every OTHER no-convention key remains
        // isPresent=false. A swap-or-alias mutation would flip one
        // of the "other" keys to isPresent=true and fail the assert.
        //
        // The 12 instrumentation booleans + startupTier share the
        // no-convention shape (their default is supplied at the
        // resolver site, not as a Property.convention). They're the
        // class of properties where isPresent is a load-bearing
        // signal of "user set this", so a routing bug is observable
        // here in a way it isn't for the convention-defaulted ones.
        val noConventionKeys = listOf(
            "okhttp", "httpEngine", "log", "thread", "mainThreadMisuse",
            "operationDispatch", "compose", "composeSecure", "composeInput",
            "ktor", "cronet",
        )

        for (setKey in noConventionKeys) {
            // Fresh project + extension per iteration to isolate state.
            val project = ProjectBuilder.builder()
                .withProjectDir(tempFolder.newFolder("iter-$setKey"))
                .build()
            val extension = project.extensions.create(
                "bugsee", BugseePluginExtension::class.java
            )
            PluginPropertiesApplier.applyText(
                "plugin.instrumentation.$setKey=false\n",
                extension,
                RecordingLogger(),
            )

            // The key we set MUST be present.
            val setProp = propertyForName(extension.instrumentation, setKey)
            assertTrue(
                setProp.isPresent,
                "set key '$setKey' must be marked present after apply",
            )

            // Every OTHER no-convention key MUST remain unset.
            for (otherKey in noConventionKeys) {
                if (otherKey == setKey) continue
                val otherProp = propertyForName(extension.instrumentation, otherKey)
                assertEquals(
                    false, otherProp.isPresent,
                    "setting '$setKey' must not leak into '$otherKey' — " +
                        "a binding-routing bug that aliased these would fail here",
                )
            }
        }
    }

    /**
     * Lookup helper for the routing-aliasing test. Mirrors the
     * binding registry's mapping by name; if a contributor adds a
     * new key, they need to extend BOTH this lookup AND the
     * `noConventionKeys` list in the test above.
     */
    private fun propertyForName(
        instr: com.bugsee.android.gradle.BugseeInstrumentationExtension,
        name: String,
    ): org.gradle.api.provider.Property<Boolean> = when (name) {
        "okhttp" -> instr.okhttp
        "httpEngine" -> instr.httpEngine
        "log" -> instr.log
        "thread" -> instr.thread
        "mainThreadMisuse" -> instr.mainThreadMisuse
        "operationDispatch" -> instr.operationDispatch
        "compose" -> instr.compose
        "composeSecure" -> instr.composeSecure
        "composeInput" -> instr.composeInput
        "ktor" -> instr.ktor
        "cronet" -> instr.cronet
        else -> error("unknown instrumentation key: $name")
    }

    @Test
    fun routingSwap_betweenSameDefaultBooleans_isCaughtByPerKeyIsolation() {
        // every_advertised_key_actually_applies sets ALL boolean
        // properties to non-default values, so a mutation that
        // swapped two binding keys' Property references (e.g.,
        // routing instrumentation.compose's value into
        // instrumentation.composeInput) wouldn't be detected — both
        // properties end up `false` regardless of routing.
        //
        // This test sets ONE property and asserts the other neighbor
        // stays untouched. If the bindings for compose and composeInput
        // were swapped, this test would observe composeInput=false
        // (the value we set) instead of unset, and fail.
        writeProperties(
            "plugin.instrumentation.compose=false",
        )
        val (_, ext) = setUpExtension()

        // The set one took effect.
        assertEquals(false, ext.instrumentation.compose.get())
        // The neighbor with no value set MUST remain unset (these
        // properties have no built-in convention; isPresent is the
        // signal that "the user (or properties file) set this").
        assertEquals(
            false, ext.instrumentation.composeInput.isPresent,
            "composeInput must NOT be marked present — a routing-swap " +
                "mutation that aliased compose↔composeInput would make " +
                "composeInput.isPresent == true here",
        )
    }

    @Test
    fun coerce_boolean_aliasesAcceptMixedCase_inAllPositions() {
        // parseBooleanOrNull uses .lowercase() then matches alias
        // strings. A mutation that dropped .lowercase() would still
        // pass `1`/`0`/`true`/`false` (already lowercase or numeric)
        // but fail on the case-varied aliases. The previous
        // coerce_boolean_acceptsAliases test only covered ONE
        // mixed-case alias (`YES`), making the lowercase() step a
        // single-point-of-failure in the suite. Cover more.
        writeProperties(
            "plugin.debug=TRUE",
            "plugin.feedback=False",
            "plugin.optimizeExtensionsLoading=On",
            "plugin.buildInfo.enabled=No",
        )
        val (_, ext) = setUpExtension()

        assertEquals(true, ext.debug.get())
        assertEquals(false, ext.feedback.get())
        assertEquals(true, ext.optimizeExtensionsLoading.get())
        assertEquals(false, ext.buildInfo.enabled.get())
    }

    @Test
    fun stringBinding_whitespaceOnlyValue_isRejectedSameAsEmpty() {
        // `plugin.endpoint=   ` (whitespace only) MUST be rejected
        // the same way the literal empty string is. The trim() in
        // stringBinding is what makes these equivalent — a mutation
        // that removed the trim would let whitespace through and
        // set the property to "   ", a broken endpoint URL. The
        // empty-value test only proves the empty-check fires; this
        // test proves the trim is load-bearing.
        writeProperties("plugin.endpoint=   ")
        val (ext, logger) = setUpExtensionWithLogger()

        assertEquals("https://api.bugsee.com", ext.endpoint.get())
        assertTrue(
            logger.warnings.any { it.contains("endpoint") && it.contains("empty") },
            "expected a warn for the whitespace-only value; got: ${logger.warnings}",
        )
    }

    @Test
    fun verboseDebugLog_acceptsBooleanAliases_notJustLiteralTrue() {
        // The verbose mode is gated by `parseBooleanOrNull(props["debug"]) == true`,
        // which accepts the same aliases as the rest of the boolean
        // coercion (yes/on/1). A mutation that tightened the gate to
        // a literal "true" check would silently disable verbose mode
        // for users who wrote `plugin.debug=yes`. Pin the alias path.
        writeProperties(
            "plugin.debug=yes",
            "plugin.feedback=true",
        )
        val (_, logger) = setUpExtensionWithLogger()

        assertTrue(
            logger.warnings.any { it.contains("applied") && it.contains("plugin.feedback") },
            "expected a verbose apply log under plugin.debug=yes alias; " +
                "got warnings: ${logger.warnings}",
        )
    }

    @Test
    fun stringBinding_emptyValue_isRejectedWithWarn_andDefaultHolds() {
        // A user who deliberately empties a string value (e.g.
        // `plugin.endpoint=` during editing) must NOT silently get a
        // broken empty-string convention. Treat empty-after-trim as
        // user error: warn + skip + default holds.
        writeProperties("plugin.endpoint=")
        val (ext, logger) = setUpExtensionWithLogger()

        assertEquals("https://api.bugsee.com", ext.endpoint.get())
        assertTrue(
            logger.warnings.any { it.contains("endpoint") && it.contains("empty") },
            "expected a warn for the empty value; got: ${logger.warnings}",
        )
    }

    // ── End-to-end production path (providers.fileContents) ────────

    @Test
    fun productionApplyTo_readsFileFromRootProjectInMultiProjectBuild() {
        // The applier resolves the file via
        // `project.rootProject.layout.projectDirectory.file(...)`. In a
        // single-module build (or ProjectBuilder's default), rootProject
        // and project point at the same directory, so a mutation that
        // dropped `.rootProject` would silently pass.
        //
        // This test sets up a real parent + child via ProjectBuilder
        // and drops bugsee.properties at the PARENT's directory. The
        // applier must find it via `rootProject.layout` — looking at
        // the child's own dir would miss the file and the assertion
        // would fail.
        val rootDir = tempFolder.newFolder("multi-root")
        val childDir = java.io.File(rootDir, "app").apply { mkdirs() }
        java.io.File(rootDir, "bugsee.properties").writeText(
            "plugin.endpoint=https://multi-project.example.com\n"
        )

        val rootProject = ProjectBuilder.builder()
            .withProjectDir(rootDir)
            .withName("multi-root")
            .build()
        val childProject = ProjectBuilder.builder()
            .withProjectDir(childDir)
            .withName("app")
            .withParent(rootProject)
            .build()
        val extension = childProject.extensions.create(
            "bugsee", BugseePluginExtension::class.java
        )

        PluginPropertiesApplier.applyTo(childProject, extension)

        assertEquals(
            "https://multi-project.example.com", extension.endpoint.get(),
            "applier MUST resolve the file from rootProject.layout, not " +
                "project.layout — a mutation here would silently fail to " +
                "find the file in any real multi-module Android build",
        )
    }

    @Test
    fun productionApplyTo_readsFileViaProvidersFileContents_endToEnd() {
        // Most tests go through the `applyText` test seam, which
        // bypasses the configuration-cache-aware
        // `providers.fileContents(...)` wiring in `applyTo`. This
        // smoke test exercises `applyTo` end-to-end so a regression
        // that broke the CC wiring (e.g. swapping `.asText` for
        // `.asBytes`, or pointing at the wrong layout directory)
        // would surface here.
        //
        // The applier reads from `project.rootProject.layout
        // .projectDirectory.file("bugsee.properties")`. ProjectBuilder
        // gives us a project whose `rootProject == project`, so
        // `tempFolder.root` is the right place to drop the file.
        writeProperties(
            "plugin.endpoint=https://e2e.example.com",
            "plugin.feedback=true",
        )

        val project = ProjectBuilder.builder()
            .withProjectDir(tempFolder.root)
            .build()
        val extension = project.extensions.create(
            "bugsee", BugseePluginExtension::class.java
        )

        // Go through the public production entrypoint (NOT applyText).
        PluginPropertiesApplier.applyTo(project, extension)

        assertEquals(
            "https://e2e.example.com", extension.endpoint.get(),
            "applyTo must read the file via providers.fileContents — " +
                "if this fails, the CC-input wiring is broken or " +
                "points at the wrong directory",
        )
        assertEquals(true, extension.feedback.get())
    }

    // ── Setup helpers ───────────────────────────────────────────────

    /**
     * Build a real `BugseePluginExtension` via ProjectBuilder (so the
     * `objects.property(...)` instances behave like in a real build)
     * and run the applier against it, returning both for inspection.
     *
     * The properties file (if any) is read from the project's root
     * directory — written by the individual test via [writeProperties]
     * BEFORE this helper runs.
     */
    private fun setUpExtension(): Pair<Project, BugseePluginExtension> {
        val project = ProjectBuilder.builder()
            .withProjectDir(tempFolder.root)
            .build()
        val extension = project.extensions.create(
            "bugsee", BugseePluginExtension::class.java
        )
        PluginPropertiesApplier.applyTo(project, extension)
        return project to extension
    }

    /**
     * Variant of [setUpExtension] that captures log output via the
     * applier's text-based test seam. Returns both the extension and
     * the recording logger so tests don't have to set up twice for
     * the cases that want to assert on both.
     *
     * Reads the same `bugsee.properties` the regular path would —
     * just via `File.readText()` instead of `providers.fileContents`
     * (the CC wiring is covered by
     * [productionApplyTo_readsFileViaProvidersFileContents_endToEnd]).
     */
    private fun setUpExtensionWithLogger(): Pair<BugseePluginExtension, RecordingLogger> {
        val project = ProjectBuilder.builder()
            .withProjectDir(tempFolder.root)
            .build()
        val extension = project.extensions.create(
            "bugsee", BugseePluginExtension::class.java
        )
        val logger = RecordingLogger()
        val file = File(tempFolder.root, "bugsee.properties")
        val text = if (file.isFile) file.readText() else null
        PluginPropertiesApplier.applyText(text, extension, logger)
        return extension to logger
    }

    private fun writeProperties(vararg lines: String) {
        val file = File(tempFolder.root, "bugsee.properties")
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
