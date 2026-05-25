package com.bugsee.android.compose.compiler

import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BugseeComposeCommandLineProcessor].
 *
 * Pin the CLI-option contract that Kotlin's compiler driver and the
 * Kotlin Gradle Plugin (KGP) both rely on:
 *  - The plugin ID is the constant the KGP-side
 *    [com.bugsee.android.gradle.BugseePlugin] hands to the
 *    `kotlinCompilerPluginClasspath` declaration. A drift between
 *    the KGP and compiler sides would silently disable the plugin
 *    in published builds.
 *  - The two option names (`enabled`, `secure`) flow through to
 *    [BugseeComposeConfigKeys]'s typed [org.jetbrains.kotlin.config.CompilerConfigurationKey]s.
 *  - The default-when-unparseable behavior (`true`) matches the
 *    KGP DSL's "default-on" stance.
 *
 * Prior to this file, `compose-compiler-plugin/src/test/` did not
 * exist — the compiler-plugin half of the bugsee Compose
 * integration had zero unit coverage. These tests establish the
 * test infrastructure baseline.
 */
@OptIn(ExperimentalCompilerApi::class)
class BugseeComposeCommandLineProcessorTest {

    private val processor = BugseeComposeCommandLineProcessor()

    @Test fun `plugin ID matches the public constant used by the gradle plugin`() {
        // KGP wires the compiler plugin via this exact ID. Drift
        // between the value here and the value the gradle-plugin
        // module hands to `SubpluginOption(...)` would silently
        // disable Compose instrumentation in published builds.
        assertEquals("com.bugsee.compose.compiler", processor.pluginId)
        assertEquals(
            "BugseeComposeCommandLineProcessor.PLUGIN_ID must equal pluginId",
            BugseeComposeCommandLineProcessor.PLUGIN_ID,
            processor.pluginId,
        )
    }

    @Test fun `exposes exactly two CLI options matching the public-facing names`() {
        val options = processor.pluginOptions.filterIsInstance<CliOption>()
        // Pin name AND count — a future refactor that added a third
        // option without updating the KGP-side `SubpluginOption`
        // mapping would silently fail to wire that option through.
        assertEquals(
            "expected exactly two CLI options; got ${options.map { it.optionName }}",
            2, options.size,
        )
        val names = options.map { it.optionName }.toSet()
        assertEquals(
            setOf(
                BugseeComposeCommandLineProcessor.OPTION_ENABLED,
                BugseeComposeCommandLineProcessor.OPTION_SECURE,
            ),
            names,
        )
        // Both must be non-required so missing options on legacy
        // KGP versions don't fail compilation.
        for (opt in options) {
            assertFalse(
                "${opt.optionName} must NOT be required — KGP versions older than the " +
                    "introduction of the new option must still compile",
                opt.required,
            )
        }
    }

    @Test fun `processOption maps 'enabled' to TAG_INJECTION_ENABLED with the parsed boolean`() {
        val cfg = CompilerConfiguration()
        val enabledOpt = processor.pluginOptions.first { (it as CliOption).optionName == "enabled" }

        processor.processOption(enabledOpt, "true", cfg)
        assertEquals(true, cfg.get(BugseeComposeConfigKeys.TAG_INJECTION_ENABLED))

        // Re-process with `false` — pin the override path so a
        // future user that adds `-Pbugsee.compose.enabled=false`
        // in their gradle.properties actually gets the disable
        // they asked for.
        val cfg2 = CompilerConfiguration()
        processor.processOption(enabledOpt, "false", cfg2)
        assertEquals(false, cfg2.get(BugseeComposeConfigKeys.TAG_INJECTION_ENABLED))
    }

    @Test fun `processOption maps 'secure' to SECURE_INJECTION_ENABLED with the parsed boolean`() {
        val cfg = CompilerConfiguration()
        val secureOpt = processor.pluginOptions.first { (it as CliOption).optionName == "secure" }

        processor.processOption(secureOpt, "true", cfg)
        assertEquals(true, cfg.get(BugseeComposeConfigKeys.SECURE_INJECTION_ENABLED))

        val cfg2 = CompilerConfiguration()
        processor.processOption(secureOpt, "false", cfg2)
        assertEquals(false, cfg2.get(BugseeComposeConfigKeys.SECURE_INJECTION_ENABLED))
    }

    @Test fun `processOption falls back to true when the value is not a valid boolean`() {
        // The KDoc + KGP-side mapping say "default-on". The actual
        // implementation uses `toBooleanStrictOrNull() ?: true` —
        // pin this so a refactor to `toBoolean()` (which silently
        // turns "garbage" into false) would break tests.
        val cfg = CompilerConfiguration()
        val enabledOpt = processor.pluginOptions.first { (it as CliOption).optionName == "enabled" }

        processor.processOption(enabledOpt, "garbage", cfg)
        assertEquals(
            "unparseable value must fall back to default-on (true)",
            true,
            cfg.get(BugseeComposeConfigKeys.TAG_INJECTION_ENABLED),
        )
    }

    @Test fun `processOption ignores unknown option names`() {
        // Defensive: if KGP ever hands us an option name we don't
        // recognize (forward-compat with future KGP versions), we
        // must NOT poison the CompilerConfiguration with random
        // entries. The implementation's `when` falls through silently
        // on unrecognized names — pin that.
        val cfg = CompilerConfiguration()
        val unknownOpt = CliOption(
            optionName = "unknown.option",
            valueDescription = "x",
            description = "y",
            required = false,
        )

        // Must not throw.
        processor.processOption(unknownOpt, "true", cfg)

        assertEquals(
            "unknown option must not populate TAG_INJECTION_ENABLED",
            null,
            cfg.get(BugseeComposeConfigKeys.TAG_INJECTION_ENABLED),
        )
        assertEquals(
            "unknown option must not populate SECURE_INJECTION_ENABLED",
            null,
            cfg.get(BugseeComposeConfigKeys.SECURE_INJECTION_ENABLED),
        )
    }
}
