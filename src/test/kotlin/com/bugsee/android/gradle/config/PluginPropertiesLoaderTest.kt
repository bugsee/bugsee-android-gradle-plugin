package com.bugsee.android.gradle.config

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pin the parse contract of [PluginPropertiesLoader]:
 *  - filters to `plugin.*` keys only;
 *  - strips the `plugin.` prefix from each key;
 *  - null/blank input returns empty silently (file-absent case);
 *  - malformed input warns once with a contextful message and
 *    returns empty (build keeps going);
 *  - non-`plugin.` keys in the SAME file (e.g. the existing
 *    `app_token=` for AppTokenResolver) are ignored — not stripped,
 *    not re-namespaced.
 *
 * The contract matters because this loader is the join point between
 * the bugsee.properties surface and the DSL — a regression that
 * accidentally returned all keys (or none, or stale values) would
 * silently break user configuration.
 *
 * Loader is text-based (no file I/O), so these tests pass strings
 * directly. File reading + configuration-cache wiring lives in
 * [PluginPropertiesApplier]; see [PluginPropertiesApplierTest] for the
 * file-I/O coverage.
 */
class PluginPropertiesLoaderTest {

    private val logger = RecordingLogger()

    @Test
    fun load_nullInput_returnsEmptyMap_silently() {
        // Null is the file-absent signal from the applier's
        // `providers.fileContents(...).asText.orNull`. MUST be silent —
        // the file is optional and a warn on every build would be
        // hostile for the common case where no config file exists.
        val map = PluginPropertiesLoader.load(null, logger)

        assertTrue(map.isEmpty(), "expected empty map, got $map")
        assertEquals(emptyList(), logger.warnings)
    }

    @Test
    fun load_blankInput_returnsEmptyMap_silently() {
        val map = PluginPropertiesLoader.load("   \n\t  \n", logger)

        assertTrue(map.isEmpty())
        assertEquals(emptyList(), logger.warnings)
    }

    @Test
    fun load_pluginPrefix_strippedAndValueRetained() {
        val map = PluginPropertiesLoader.load(
            """
            plugin.endpoint=https://api.example.com
            plugin.debug=true
            """.trimIndent(),
            logger,
        )

        assertEquals("https://api.example.com", map["endpoint"])
        assertEquals("true", map["debug"])
        assertEquals(2, map.size)
    }

    @Test
    fun load_nonPluginKeys_ignored_notReNamespaced() {
        // The same bugsee.properties already hosts non-plugin keys —
        // most notably `app_token=` for AppTokenResolver. The loader
        // MUST leave those alone: not return them, not strip a prefix
        // they don't have, not crash on them.
        val map = PluginPropertiesLoader.load(
            """
            app_token=secret-token-do-not-leak
            plugin.debug=false
            some_other_tool.option=42
            """.trimIndent(),
            logger,
        )

        // Only the prefixed key survives, stripped.
        assertEquals(mapOf("debug" to "false"), map)
        // Defensive: the loader didn't accidentally expose the app
        // token under a different name.
        assertNull(map["app_token"])
        assertNull(map["plugin.app_token"])
    }

    @Test
    fun load_emptyValue_preservedAsEmptyString() {
        // A key like `plugin.endpoint=` (deliberately cleared during
        // editing) MUST survive to the applier — the empty-value
        // policy is decided at the type-coercion layer, not here.
        // (For Strings, the applier rejects empty-trimmed values; for
        // numerics, the parse fails. Either way the value reaches the
        // applier so it can be evaluated.)
        val map = PluginPropertiesLoader.load("plugin.endpoint=", logger)

        assertEquals(mapOf("endpoint" to ""), map)
    }

    @Test
    fun load_pluginKeyWithEmptyTail_skipped() {
        // The literal key `plugin.` (no tail) is meaningless and
        // would produce an empty key string in the output map — drop
        // it rather than emit a weird empty entry.
        val map = PluginPropertiesLoader.load(
            """
            plugin.=meaningless
            plugin.debug=true
            """.trimIndent(),
            logger,
        )

        assertEquals(mapOf("debug" to "true"), map)
    }

    @Test
    fun load_deeplyNestedKey_preserved() {
        // The applier later splits on dots; the loader's job is to
        // pass the dotted tail through verbatim.
        val map = PluginPropertiesLoader.load(
            "plugin.buildInfo.sizeCheck.warningPercent=12.5",
            logger,
        )

        assertEquals(mapOf("buildInfo.sizeCheck.warningPercent" to "12.5"), map)
    }

    @Test
    fun load_malformedContent_returnsEmpty_andWarnsOnce_withContextualMessage() {
        // Java's Properties.load is fairly tolerant, but a stray
        // unicode-escape that the parser refuses is one of the few
        // shapes that throws.
        val map = PluginPropertiesLoader.load(
            "plugin.endpoint=https://x.test\\uZZZZ",
            logger,
        )

        assertTrue(map.isEmpty(), "malformed input must fall back to empty map; got $map")
        assertEquals(
            1, logger.warnings.size,
            "expected exactly one warning, got ${logger.warnings}",
        )
        // The warning must name the file so a user staring at a long
        // log knows which file to fix. A regression that logged a
        // generic "parse failed" with no file context would slip past
        // a size-only assertion.
        assertTrue(
            logger.warnings[0].contains("bugsee.properties"),
            "warning must mention the file name; got: ${logger.warnings[0]}",
        )
    }

    @Test
    fun load_duplicateKey_lastValueWins_perPropertiesContract() {
        // java.util.Properties keeps the last value for duplicate
        // keys. The loader doesn't try to be clever — it inherits
        // that contract. A defensive caller can grep for duplicates
        // separately if needed.
        val map = PluginPropertiesLoader.load(
            """
            plugin.debug=false
            plugin.debug=true
            """.trimIndent(),
            logger,
        )

        assertEquals(mapOf("debug" to "true"), map)
    }

    @Test
    fun load_utf8BomPrefix_isStripped_andFirstKeyMatches() {
        // Windows Notepad and (older) VS Code save UTF-8 files with a
        // leading BOM character (U+FEFF). Without explicit BOM
        // stripping, `Properties.load` treats it as part of the first
        // key — so `﻿plugin.endpoint` is parsed and our prefix
        // filter rejects it (it starts with U+FEFF, not 'p'). The
        // user sees no error and no effect.
        val bom = "﻿"
        val map = PluginPropertiesLoader.load(
            "${bom}plugin.endpoint=https://bom.example.com\nplugin.debug=true",
            logger,
        )

        assertEquals(
            "https://bom.example.com", map["endpoint"],
            "BOM-prefixed first key must be matched the same as a BOM-less " +
                "one — otherwise users editing the file in Windows tooling " +
                "would see silent config failures",
        )
        assertEquals("true", map["debug"])
    }

    @Test
    fun load_pluginPrefix_isCaseSensitive() {
        // The contract is `plugin.` lowercase. `Plugin.Debug=true`
        // must NOT be matched (no case-insensitive auto-conversion
        // that could collide with future, real case-sensitive keys).
        val map = PluginPropertiesLoader.load(
            """
            Plugin.Debug=true
            PLUGIN.DEBUG=true
            plugin.debug=true
            """.trimIndent(),
            logger,
        )

        // Only the lowercase-prefixed key survives. The other two are
        // silently ignored (non-`plugin.*` from the loader's view).
        assertEquals(mapOf("debug" to "true"), map)
    }

}
