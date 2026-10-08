package com.bugsee.android.gradle.upload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NativeSymbolCacheKeyTest {

    @Test fun `key is namespaced by the CLI floor so older-CLI no-op uploads are not trusted`() {
        val key = nativeSymbolCacheKey("token", "release")
        assertTrue(key.contains(":release:cli-${CliBinaryResolver.DEFAULT_VERSION}"), key)
        // A hash cached under the pre-floor-bump key must miss.
        assertNotEquals(key, "${com.bugsee.android.gradle.util.HashUtils.sha1Hex("token")}:release")
    }

    @Test fun `key carries the elf-force generation so unforced FULL hashes are not trusted`() {
        // Hashes cached before --force was sent for FULL zips (including the
        // ':cli-<floor>' keys written by the floor bump alone) must miss.
        val key = nativeSymbolCacheKey("token", "release")
        assertTrue(key.endsWith(":elf-force-v1"), key)
        val legacy = "${com.bugsee.android.gradle.util.HashUtils.sha1Hex("token")}:release:cli-${CliBinaryResolver.DEFAULT_VERSION}"
        assertNotEquals(key, legacy)
    }

    @Test fun `key is stable per token and variant`() {
        assertEquals(nativeSymbolCacheKey("a", "debug"), nativeSymbolCacheKey("a", "debug"))
        assertNotEquals(nativeSymbolCacheKey("a", "debug"), nativeSymbolCacheKey("a", "release"))
        assertNotEquals(nativeSymbolCacheKey("a", "debug"), nativeSymbolCacheKey("b", "debug"))
    }
}
