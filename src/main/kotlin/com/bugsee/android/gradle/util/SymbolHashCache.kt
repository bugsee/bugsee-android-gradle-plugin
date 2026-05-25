package com.bugsee.android.gradle.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * LRU cache for native symbol SHA-1 hashes.
 * Avoids redundant zip + upload when symbols haven't changed between builds.
 *
 * Cache is stored as a JSON file, typically under `.gradle/bugsee/`.
 */
internal object SymbolHashCache {

    private const val MAX_ENTRIES = 20

    /**
     * Returns `true` if the cache contains an entry for [key] with matching [hash].
     */
    fun isCached(cacheFile: File, key: String, hash: String): Boolean {
        val entries = readEntries(cacheFile)
        return entries.any { it.key == key && it.hash == hash }
    }

    /**
     * Stores [hash] for [key], evicting the least-recently-used entry
     * if the cache exceeds [MAX_ENTRIES].
     */
    fun put(cacheFile: File, key: String, hash: String) {
        val entries = readEntries(cacheFile).toMutableList()

        // Remove existing entry for this key (will be re-added with fresh timestamp)
        entries.removeAll { it.key == key }

        entries.add(CacheEntry(key, hash, System.currentTimeMillis()))

        // Enforce LRU limit — drop oldest entries first
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.minByOrNull { it.timestamp }
            if (oldest != null) entries.remove(oldest) else break
        }

        writeEntries(cacheFile, entries)
    }

    private fun readEntries(cacheFile: File): List<CacheEntry> {
        if (!cacheFile.exists()) return emptyList()
        return try {
            val json = JSONObject(cacheFile.readText(Charsets.UTF_8))
            val array = json.optJSONArray("entries") ?: return emptyList()
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                CacheEntry(
                    key = obj.getString("key"),
                    hash = obj.getString("hash"),
                    timestamp = obj.getLong("timestamp")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeEntries(cacheFile: File, entries: List<CacheEntry>) {
        cacheFile.parentFile?.mkdirs()
        val array = JSONArray()
        for (entry in entries) {
            array.put(JSONObject().apply {
                put("key", entry.key)
                put("hash", entry.hash)
                put("timestamp", entry.timestamp)
            })
        }
        val json = JSONObject().apply {
            put("entries", array)
        }
        // Atomic-move via NIO instead of `File.renameTo`. The
        // previous `File.renameTo` was documented as "platform-
        // dependent" and SILENTLY FAILED on Windows when the
        // destination already existed — two concurrent variant
        // tasks writing the cache could corrupt it or lose
        // writes. Files.move with REPLACE_EXISTING + ATOMIC_MOVE
        // gives the strong guarantee we want: either the
        // destination atomically updates to the new bytes, or
        // the move throws (and we catch + propagate cleanly).
        //
        // `Files.createTempFile` produces a name with extra
        // randomness so concurrent writers don't trample each
        // other's tmp file (the prior fixed `.tmp` suffix could
        // overlap on parallel variant builds).
        val parent = cacheFile.parentFile?.toPath()
            ?: error("cache file '${cacheFile.path}' has no parent directory; refusing to write")
        val tmp = Files.createTempFile(parent, cacheFile.nameWithoutExtension, ".tmp.json")
        try {
            Files.writeString(tmp, json.toString(2), Charsets.UTF_8)
            try {
                Files.move(
                    tmp, cacheFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Some filesystems (e.g. cross-device renames on
                // certain Linux configurations, network mounts)
                // refuse ATOMIC_MOVE. Fall back to a
                // REPLACE_EXISTING move, which is non-atomic but
                // still strictly better than the prior
                // `File.renameTo` behavior — and concurrent-writer
                // corruption is bounded to the FS rather than
                // silently producing duplicate-cache state.
                Files.move(
                    tmp, cacheFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            // If the move succeeded the tmp file was already moved.
            // If anything threw, clean up the orphan so subsequent
            // builds don't accumulate `.tmp.json` files in the
            // gradle directory.
            Files.deleteIfExists(tmp)
        }
    }

    private data class CacheEntry(
        val key: String,
        val hash: String,
        val timestamp: Long
    )
}
