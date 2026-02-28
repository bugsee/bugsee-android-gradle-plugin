package com.bugsee.android.gradle.util

import org.gradle.api.Project
import java.io.File

internal object IconResolver {

    private const val MIPMAP_RESOURCE_START = "@mipmap/"
    private const val DRAWABLE_RESOURCE_START = "@drawable/"

    /**
     * Resolves the app icon file from project resources.
     * Prefers xxhdpi variant (144x144). Falls back to the largest available icon.
     * Skips XML resources (selectors, etc.).
     */
    fun getIcon(project: Project, resourceIdString: String): File? {
        val resourceStart = when {
            resourceIdString.startsWith(MIPMAP_RESOURCE_START) -> MIPMAP_RESOURCE_START
            resourceIdString.startsWith(DRAWABLE_RESOURCE_START) -> DRAWABLE_RESOURCE_START
            else -> return null
        }

        val resourceId = resourceIdString.substring(resourceStart.length)
        if (resourceId.isEmpty()) return null

        // e.g. "mipmap" or "drawable"
        val resourceFolderType = resourceStart.substring(1, resourceStart.length - 1)

        val android = project.extensions.findByName("android") ?: return null
        val sourceSets = android.javaClass.getMethod("getSourceSets").invoke(android)
        val mainSourceSet = sourceSets.javaClass.getMethod("getByName", String::class.java).invoke(sourceSets, "main")
        val res = mainSourceSet.javaClass.getMethod("getRes").invoke(mainSourceSet)
        @Suppress("UNCHECKED_CAST")
        val sourceFiles = res.javaClass.getMethod("getSourceFiles").invoke(res) as Iterable<File>

        val iconFiles = sourceFiles.filter { file ->
            val baseName = file.nameWithoutExtension
            val ext = file.extension
            baseName == resourceId
                && ext != "xml"
                && file.parent.lowercase().contains(resourceFolderType)
        }

        if (iconFiles.isEmpty()) return null

        // Prefer xxhdpi
        val xxhdpi = iconFiles.find { it.parent.contains("xxhdpi") }
        if (xxhdpi != null) return xxhdpi

        // Fall back to the largest icon
        return iconFiles.maxByOrNull { it.length() }
    }
}
