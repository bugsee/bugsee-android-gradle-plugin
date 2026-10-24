package com.bugsee.android.gradle.config

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsFileParserTest {

    private val logger = RecordingLogger()

    @Test
    fun rootTokenAndPlugin() {
        val f = SettingsFileParser.parse("app_token= abc \nplugin.debug=true\nplugin.ndk.enabled = false", logger)
        assertEquals("abc", f.rootAppToken)
        assertEquals(mapOf("debug" to "true", "ndk.enabled" to "false"), f.rootPlugin)
        assertTrue(f.variants.isEmpty())
        assertTrue(logger.warnings.isEmpty())
    }

    @Test
    fun variantTokenAndPlugin() {
        val f = SettingsFileParser.parse(
            "variant.staging.app_token= tok \nvariant.staging.plugin.ndk.enabled=true",
            logger,
        )
        assertEquals(VariantFileEntries("tok", mapOf("ndk.enabled" to "true")), f.variants["staging"])
        assertNull(f.rootAppToken)
        assertTrue(logger.warnings.isEmpty())
    }

    @Test
    fun emptyVariantToken_isNullAndWarns() {
        val f = SettingsFileParser.parse("variant.staging.app_token=", logger)
        assertNull(f.variants["staging"]?.appToken)
        assertEquals(1, logger.warnings.size)
        assertTrue(logger.warnings[0].contains("variant.staging.app_token"))
        assertTrue(logger.warnings[0].contains("empty value"))
    }

    @Test
    fun whitespaceVariantToken_isNull() {
        val f = SettingsFileParser.parse("variant.staging.app_token=    ", logger)
        assertNull(f.variants["staging"]?.appToken)
        assertEquals(1, logger.warnings.size)
    }

    @Test
    fun emptyRootToken_isNullAndWarns() {
        val f = SettingsFileParser.parse("app_token=", logger)
        assertNull(f.rootAppToken)
        assertEquals(1, logger.warnings.size)
        assertTrue(logger.warnings[0].contains("app_token"))
    }

    @Test
    fun globalOnlyVariantKey_warnsAndDropped() {
        val f = SettingsFileParser.parse("variant.staging.plugin.feedback=true", logger)
        assertTrue(f.variants["staging"]?.plugin.orEmpty().isEmpty())
        assertEquals(1, logger.warnings.size)
        assertTrue(logger.warnings[0].contains("feedback"))
    }

    @Test
    fun unknownVariantKey_infoAndDropped() {
        val f = SettingsFileParser.parse(
            "variant.staging.plugin.bogus=1\nvariant.=x\nvariant.staging=x\nvariant.staging.other=x\nvariant.staging.=x",
            logger,
        )
        assertTrue(f.variants.values.all { it.plugin.isEmpty() && it.appToken == null })
        assertEquals(5, logger.infos.size)
        assertTrue(logger.warnings.isEmpty())
    }

    @Test
    fun rootUnknownPluginKey_notLoggedHere() {
        val f = SettingsFileParser.parse("plugin.bogus=1", logger)
        assertEquals(mapOf("bogus" to "1"), f.rootPlugin)
        assertTrue(logger.infos.isEmpty())
    }

    @Test
    fun bomOnFirstLine() {
        val f = SettingsFileParser.parse("﻿variant.a.app_token=t", logger)
        assertEquals("t", f.variants["a"]?.appToken)
    }

    @Test
    fun nullTextIsEmpty() {
        assertEquals(SettingsFile.EMPTY, SettingsFileParser.parse(null, logger))
        assertTrue(logger.warnings.isEmpty())
    }

    @Test
    fun malformedFileFallsBackToEmptyAndWarnsOnce() {
        val f = SettingsFileParser.parse("app_token=\\uZZZZ", logger)
        assertEquals(SettingsFile.EMPTY, f)
        assertEquals(1, logger.warnings.size)
        assertTrue(logger.warnings[0].contains("failed to parse bugsee.properties"))
    }

    @Test
    fun multipleVariantsIndependent() {
        val f = SettingsFileParser.parse(
            "variant.a.app_token=ta\nvariant.b.plugin.debug=true\nvariant.b.app_token=tb",
            logger,
        )
        assertEquals(VariantFileEntries("ta", emptyMap()), f.variants["a"])
        assertEquals(VariantFileEntries("tb", mapOf("debug" to "true")), f.variants["b"])
    }
}
