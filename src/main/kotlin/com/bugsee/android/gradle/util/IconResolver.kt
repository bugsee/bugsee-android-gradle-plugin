package com.bugsee.android.gradle.util

import java.io.File

internal object IconResolver {

    private const val MIPMAP_RESOURCE_START = "@mipmap/"
    private const val DRAWABLE_RESOURCE_START = "@drawable/"

    /**
     * Resolves the app icon file from a pre-resolved list of `res/`
     * source files. Prefers xxhdpi (144x144); falls back to the
     * largest available raster icon. Skips XML resources (selectors,
     * adaptive-icon configs, etc.).
     *
     * Takes the source-file list as `Iterable<File>` rather than
     * `Project` so the caller can wire it from
     * `android.sourceSets.main.res.sourceFiles` into a
     * configuration-cache-friendly task input at registration time.
     * Reading the Android extension at execution time would be a CC
     * violation — `project` is explicitly not available when a task
     * replays from the CC-stored graph. (Mirrors the same pattern
     * `AppTokenResolver.resolveFromManifest` uses for the
     * `@string/foo`-fallback path.)
     */
    fun getIcon(stringResourceFiles: Iterable<File>, resourceIdString: String): File? {
        val resourceStart = when {
            resourceIdString.startsWith(MIPMAP_RESOURCE_START) -> MIPMAP_RESOURCE_START
            resourceIdString.startsWith(DRAWABLE_RESOURCE_START) -> DRAWABLE_RESOURCE_START
            else -> return null
        }

        val resourceId = resourceIdString.substring(resourceStart.length)
        if (resourceId.isEmpty()) return null

        // e.g. "mipmap" or "drawable"
        val resourceFolderType = resourceStart.substring(1, resourceStart.length - 1)

        val iconFiles = stringResourceFiles.filter { file ->
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
