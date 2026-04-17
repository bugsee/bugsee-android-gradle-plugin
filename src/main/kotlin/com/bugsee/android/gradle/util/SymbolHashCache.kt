package com.bugsee.android.gradle.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
        // Write to a temp file then atomically rename to prevent corruption
        // if two variant tasks write the cache concurrently.
        val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        tmp.writeText(json.toString(2), Charsets.UTF_8)
        tmp.renameTo(cacheFile)
    }

    private data class CacheEntry(
        val key: String,
        val hash: String,
        val timestamp: Long
    )
}
