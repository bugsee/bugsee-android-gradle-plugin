package com.bugsee.android.gradle

/**
 * Plugin constants that the AGP-free entry class ([BugseePlugin]) needs. They live in their own object,
 * not on [BugseePluginImpl], so that reading them never initializes (and so never links) the AGP-aware
 * implementation class (#161).
 */
internal object PluginMetadata {
    /** The Kotlin compiler-plugin id (also the id of the bundled compiler-plugin artifacts). */
    const val COMPILER_PLUGIN_ID = "com.bugsee.compose.compiler"

    /** This plugin's version, read from the packaged resource. */
    val VERSION: String by lazy {
        PluginMetadata::class.java.getResourceAsStream("/bugsee-plugin-version.txt")
            ?.bufferedReader()?.readText()?.trim()
            ?: "4.99.118-SNAPSHOT"
    }
}
