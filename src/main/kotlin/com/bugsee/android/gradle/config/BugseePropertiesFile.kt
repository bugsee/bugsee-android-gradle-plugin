package com.bugsee.android.gradle.config

/**
 * Single source of truth for the configuration-file name shared
 * between `AppTokenResolver` (which reads the `app_token` key) and
 * the plugin-options layer (which reads `plugin.*` keys). Both
 * surfaces target the SAME file at the root project; the namespacing
 * is by key prefix, not by file.
 */
internal const val BUGSEE_PROPERTIES_FILENAME: String = "bugsee.properties"
