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

    // ── atomic-move write path ───────────────────────────────────
    //
    // The previous implementation used `File.renameTo` for the
    // tmp → cache file rename, which is documented as platform-
    // dependent and silently fails on Windows if the destination
    // already exists. Two parallel variant builds writing the
    // same cache could lose writes or accumulate `.tmp` orphans.
    // The fix routes through `Files.move` with REPLACE_EXISTING +
    // ATOMIC_MOVE (falling back to non-atomic REPLACE_EXISTING
    // only on AtomicMoveNotSupportedException) and uses
    // `Files.createTempFile` for unique tmp names so concurrent
    // writers don't trample each other.

    @Test fun `put leaves no temp-file orphans in the cache directory`() {
        SymbolHashCache.put(cacheFile, "k1", "hash-a")
        SymbolHashCache.put(cacheFile, "k1", "hash-b")
        SymbolHashCache.put(cacheFile, "k2", "hash-c")

        val orphans = cacheFile.parentFile.listFiles()
            .orEmpty()
            .filter {
                // Match the unique-prefix shape Files.createTempFile
                // produces for the in-progress write tmp files —
                // and the older fixed `.tmp` shape we don't write
                // any more (defense against a partial revert).
                (it.name.contains(".tmp.json") || it.name.endsWith(".tmp")) &&
                    it != cacheFile
            }
        assertTrue(
            "no tmp-file orphans must remain after successful put; found: $orphans",
            orphans.isEmpty(),
        )
    }

    @Test fun `concurrent puts from many threads do not corrupt the cache`() {
        // The motivating fix scenario: two parallel variant tasks
        // both write the cache. Atomic-move semantics mean each
        // write is observed as a single replace. With the prior
        // renameTo on Windows, writes could be lost silently; on
        // POSIX it was probably fine but not contractually so.
        //
        // Pin the JDK-portable Files.move behavior: the final
        // cache is valid JSON, parseable on read, and contains at
        // least one of the concurrent writes (we don't pin WHICH —
        // the linearization order is filesystem-dependent — only
        // that no write was corrupted into a half-state).
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 8).map { i ->
                java.util.concurrent.CompletableFuture.runAsync(
                    {
                        for (j in 0 until 10) {
                            SymbolHashCache.put(cacheFile, "k-$i-$j", "hash-$i-$j")
                        }
                    },
                    pool,
                )
            }
            java.util.concurrent.CompletableFuture.allOf(*futures.toTypedArray())
                .get(30, java.util.concurrent.TimeUnit.SECONDS)

            // The cache file must be parseable JSON. If a write
            // corrupted it mid-flight, isCached would silently
            // return false for every probe (the catch-all returns
            // emptyList on bad JSON). Use that as the corruption
            // canary in BOTH directions:
            //  - the unknown-key probe returns false (which is
            //    consistent with both "valid JSON, no such entry"
            //    AND "corrupted JSON, all probes false"); not
            //    sufficient on its own.
            //  - at least one of the written entries must return
            //    true — proving the read path actually found
            //    something, i.e. the cache is valid JSON with at
            //    least one of the concurrent writes intact.
            val anyWriteSurvived = (0 until 8).any { i ->
                (0 until 10).any { j ->
                    SymbolHashCache.isCached(cacheFile, "k-$i-$j", "hash-$i-$j")
                }
            }
            assertTrue(
                "at least one concurrent write must have landed; got empty/corrupted cache",
                anyWriteSurvived,
            )
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test fun `put creates intermediate directories for a deep cache path`() {
        // The cache file may live many levels deep under
        // `.gradle/bugsee/`. Defensive: the helper's parent.mkdirs
        // call must work for paths that don't yet exist.
        val deep = File(
            cacheFile.parentFile,
            "a/b/c/d/symbol-cache.json",
        )
        SymbolHashCache.put(deep, "k1", "hash-a")
        assertTrue(
            "put must create intermediate directories; deep.path=${deep.path}",
            deep.isFile,
        )
        assertTrue(SymbolHashCache.isCached(deep, "k1", "hash-a"))
        // Cleanup so tearDown's single-file delete isn't confused.
        deep.delete()
        deep.parentFile.delete()  // d
        deep.parentFile.parentFile.delete()  // c
        deep.parentFile.parentFile.parentFile.delete()  // b
        deep.parentFile.parentFile.parentFile.parentFile.delete()  // a
    }

    @Test fun `put replaces atomically rather than appending`() {
        // After put, the cache file's bytes are exactly the new
        // serialised state — not a concatenation of old + new.
        // Pin by comparing the byte content after re-key.
        SymbolHashCache.put(cacheFile, "k1", "hash-a")
        val firstBytes = cacheFile.readBytes()

        SymbolHashCache.put(cacheFile, "k1", "hash-b")
        val secondBytes = cacheFile.readBytes()

        assertFalse(
            "second put with new value must change bytes; otherwise the write was lost",
            firstBytes.contentEquals(secondBytes),
        )
        // Sanity: the new bytes contain the new hash and NOT the
        // old hash anywhere in the file (no append/leak).
        val text = secondBytes.toString(Charsets.UTF_8)
        assertTrue("new file must mention the new hash", text.contains("hash-b"))
        assertFalse("new file must NOT contain the replaced hash", text.contains("hash-a"))
    }
}
