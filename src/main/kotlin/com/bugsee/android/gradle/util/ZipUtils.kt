package com.bugsee.android.gradle.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object ZipUtils {

    fun zipDirectory(sourceDirPath: String, zipFilePath: String) {
        val sourcePath = Paths.get(sourceDirPath)
        ZipOutputStream(FileOutputStream(zipFilePath)).use { zos ->
            Files.walk(sourcePath).use { stream ->
                stream.forEach { path ->
                    val entryName = sourcePath.relativize(path).toString().replace("\\", "/")
                    if (Files.isDirectory(path)) {
                        if (entryName.isNotEmpty()) {
                            zos.putNextEntry(ZipEntry("$entryName/"))
                            zos.closeEntry()
                        }
                    } else {
                        zos.putNextEntry(ZipEntry(entryName))
                        Files.copy(path, zos)
                        zos.closeEntry()
                    }
                }
            }
        }
    }

    fun createMappingZip(mappingFile: File, iconFile: File?, buildUUID: String): File {
        val zipTemp = File.createTempFile(buildUUID, ".zip")
        zipTemp.deleteOnExit()
        ZipOutputStream(FileOutputStream(zipTemp)).use { zos ->
            // Add mapping file
            zos.putNextEntry(ZipEntry("mapping.txt"))
            FileInputStream(mappingFile).use { fis -> fis.copyTo(zos) }
            zos.closeEntry()

            // Add icon file if present
            if (iconFile != null && iconFile.exists()) {
                val extension = iconFile.extension
                zos.putNextEntry(ZipEntry("icon.$extension"))
                FileInputStream(iconFile).use { fis -> fis.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipTemp
    }
}
