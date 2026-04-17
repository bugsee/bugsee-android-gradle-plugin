package com.bugsee.android.gradle.util

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SymbolHashCacheTest {

    private lateinit var cacheFile: File

    @Before
    fun setUp() {
        cacheFile = File.createTempFile("symbol-cache-test", ".json")
        cacheFile.delete() // start with no file on disk
    }

    @After
    fun tearDown() {
        cacheFile.delete()
    }

    // ── isCached ─────────────────────────────────────────────────

    @Test fun `isCached returns false for empty cache`() {
        assertFalse(SymbolHashCache.isCached(cacheFile, "key", "abc123"))
    }

    @Test fun `isCached returns true after put with same hash`() {
        SymbolHashCache.put(cacheFile, "key", "abc123")
        assertTrue(SymbolHashCache.isCached(cacheFile, "key", "abc123"))
    }

    @Test fun `isCached returns false when hash differs`() {
        SymbolHashCache.put(cacheFile, "key", "abc123")
        assertFalse(SymbolHashCache.isCached(cacheFile, "key", "def456"))
    }

    @Test fun `isCached returns false when key differs`() {
        SymbolHashCache.put(cacheFile, "key1", "abc123")
        assertFalse(SymbolHashCache.isCached(cacheFile, "key2", "abc123"))
    }

    // ── put / update ─────────────────────────────────────────────

    @Test fun `put overwrites previous hash for same key`() {
        SymbolHashCache.put(cacheFile, "key", "old-hash")
        SymbolHashCache.put(cacheFile, "key", "new-hash")

        assertFalse(SymbolHashCache.isCached(cacheFile, "key", "old-hash"))
        assertTrue(SymbolHashCache.isCached(cacheFile, "key", "new-hash"))
    }

    @Test fun `put stores multiple keys independently`() {
        SymbolHashCache.put(cacheFile, "release", "hash-r")
        SymbolHashCache.put(cacheFile, "debug", "hash-d")

        assertTrue(SymbolHashCache.isCached(cacheFile, "release", "hash-r"))
        assertTrue(SymbolHashCache.isCached(cacheFile, "debug", "hash-d"))
    }

    // ── LRU eviction ─────────────────────────────────────────────

    @Test fun `oldest entry is evicted when cache exceeds max size`() {
        // Fill cache beyond the 20-entry limit
        for (i in 1..21) {
            SymbolHashCache.put(cacheFile, "key-$i", "hash-$i")
        }

        // key-1 was the first (oldest) entry and should have been evicted
        assertFalse(SymbolHashCache.isCached(cacheFile, "key-1", "hash-1"))
        // key-2 through key-21 should still be present
        assertTrue(SymbolHashCache.isCached(cacheFile, "key-2", "hash-2"))
        assertTrue(SymbolHashCache.isCached(cacheFile, "key-21", "hash-21"))
    }

    @Test fun `updating an existing key refreshes its timestamp and prevents eviction`() {
        // Insert 20 entries: key-1 .. key-20
        for (i in 1..20) {
            SymbolHashCache.put(cacheFile, "key-$i", "hash-$i")
        }

        // Touch key-1 so it's no longer the oldest
        SymbolHashCache.put(cacheFile, "key-1", "hash-1-v2")

        // Add one more to trigger eviction — key-2 is now the oldest
        SymbolHashCache.put(cacheFile, "key-21", "hash-21")

        assertTrue(SymbolHashCache.isCached(cacheFile, "key-1", "hash-1-v2"))
        assertFalse(SymbolHashCache.isCached(cacheFile, "key-2", "hash-2"))
        assertTrue(SymbolHashCache.isCached(cacheFile, "key-21", "hash-21"))
    }

    // ── resilience ───────────────────────────────────────────────

    @Test fun `corrupted cache file is treated as empty`() {
        cacheFile.writeText("not valid json")

        assertFalse(SymbolHashCache.isCached(cacheFile, "key", "hash"))

        // put should overwrite the corrupted file
        SymbolHashCache.put(cacheFile, "key", "hash")
        assertTrue(SymbolHashCache.isCached(cacheFile, "key", "hash"))
    }

    @Test fun `missing cache file is handled gracefully`() {
        assertFalse(cacheFile.exists())
        assertFalse(SymbolHashCache.isCached(cacheFile, "key", "hash"))

        // put should create the file
        SymbolHashCache.put(cacheFile, "key", "hash")
        assertTrue(cacheFile.exists())
        assertTrue(SymbolHashCache.isCached(cacheFile, "key", "hash"))
    }
}
