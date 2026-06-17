package com.bugsee.android.gradle.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object ZipUtils {

    /**
     * Fixed entry timestamp (epoch 0, the Unix epoch) stamped on EVERY zip
     * entry so the zip output is byte-reproducible across builds.
     *
     * A [ZipEntry] with no explicit time is stamped by [ZipOutputStream]
     * with `System.currentTimeMillis()`, which makes two zips of identical
     * content byte-DIFFERENT. Downstream `SymbolHashCache` keys on the
     * SHA-1 of the resulting zip, so a per-build-varying hash defeats the
     * cache entirely (the native debug-symbol upload re-runs on every
     * build) and undermines server-side dedup. Pinning the time (and a
     * stable entry order, below) restores byte-stable output so the hash
     * only changes when file CONTENT actually changes.
     */
    private const val FIXED_ENTRY_TIME_MS: Long = 0L

    fun zipDirectory(sourceDirPath: String, zipFilePath: String) {
        val sourcePath = Paths.get(sourceDirPath)
        ZipOutputStream(FileOutputStream(zipFilePath)).use { zos ->
            // Collect into a list and sort by the (forward-slash-normalized)
            // entry name so the write order is deterministic — Files.walk
            // enumeration order is unspecified and would otherwise vary the
            // zip bytes (and hash) across builds / filesystems.
            val paths = Files.walk(sourcePath).use { stream ->
                stream.collect(java.util.stream.Collectors.toList())
            }.sortedBy { path ->
                sourcePath.relativize(path).toString().replace("\\", "/")
            }
            for (path in paths) {
                val entryName = sourcePath.relativize(path).toString().replace("\\", "/")
                if (Files.isDirectory(path)) {
                    if (entryName.isNotEmpty()) {
                        zos.putNextEntry(stableEntry("$entryName/"))
                        zos.closeEntry()
                    }
                } else {
                    zos.putNextEntry(stableEntry(entryName))
                    Files.copy(path, zos)
                    zos.closeEntry()
                }
            }
        }
    }

    fun createMappingZip(mappingFile: File, iconFile: File?, buildUUID: String): File {
        val zipTemp = File.createTempFile(buildUUID, ".zip")
        zipTemp.deleteOnExit()
        ZipOutputStream(FileOutputStream(zipTemp)).use { zos ->
            // Add mapping file
            zos.putNextEntry(stableEntry("mapping.txt"))
            FileInputStream(mappingFile).use { fis -> fis.copyTo(zos) }
            zos.closeEntry()

            // Add icon file if present
            if (iconFile != null && iconFile.exists()) {
                val extension = iconFile.extension
                zos.putNextEntry(stableEntry("icon.$extension"))
                FileInputStream(iconFile).use { fis -> fis.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipTemp
    }

    /** A [ZipEntry] with a fixed timestamp for reproducible output. */
    private fun stableEntry(name: String): ZipEntry =
        ZipEntry(name).apply { time = FIXED_ENTRY_TIME_MS }
}
