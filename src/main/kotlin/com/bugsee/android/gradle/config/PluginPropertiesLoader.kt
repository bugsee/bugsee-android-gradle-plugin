package com.bugsee.android.gradle.config

import org.gradle.api.logging.Logger
import java.io.IOException
import java.util.Properties

/**
 * Parses already-read `bugsee.properties` content into the subset of
 * keys whose names start with `plugin.` (the prefix is stripped).
 *
 * Pure-logic: this object does NOT perform file I/O. The file read is
 * the [PluginPropertiesApplier]'s job, because the applier integrates
 * with Gradle's `providers.fileContents(...)` to register the file as
 * a configuration-cache input. Splitting the responsibilities lets
 * unit tests pin the parser without spinning up a Gradle Project.
 *
 * The same `bugsee.properties` file is already used by
 * [com.bugsee.android.gradle.upload.AppTokenResolver] for the app
 * token. Plugin options share the file but live under a distinct
 * `plugin.` namespace so the two surfaces don't collide.
 *
 * Returns an empty map when:
 *  - [text] is null (file absent — the common case);
 *  - [text] is present but contains no `plugin.*` keys;
 *  - [text] is malformed; in that case a warning is logged once and
 *    the loader falls back to empty so the build keeps going on
 *    defaults.
 */
internal object PluginPropertiesLoader {

    private const val KEY_PREFIX = "plugin."

    /**
     * Parse `plugin.*` keys out of `bugsee.properties` content.
     *
     * @param text File contents as a String. Pass `null` when the file
     *        is absent — the loader treats that as "no overrides" with
     *        no log noise.
     * @param logger Gradle logger; only used to warn once on malformed
     *        content. Successful loads are silent.
     * @return Map of stripped key (e.g. `buildInfo.enabled`) to raw
     *        string value. Empty when [text] is null/blank/has no
     *        `plugin.*` keys/is malformed.
     */
    fun load(text: String?, logger: Logger): Map<String, String> {
        if (text == null) return emptyMap()
        if (text.isBlank()) return emptyMap()

        // Strip a leading UTF-8 BOM (U+FEFF). Without this, Windows
        // Notepad / VS Code editors that save with BOM-on prepend the
        // BOM character to the first line — `Properties.load` then
        // treats `﻿plugin.endpoint` as the key name, which our
        // prefix filter does NOT match. The first key would silently
        // route to the unknown-key log and the user would see no
        // effect from their config.
        val normalized = text.removePrefix("﻿")

        val raw = try {
            val props = Properties()
            props.load(normalized.reader())
            props
        } catch (e: IOException) {
            // Properties.load wraps unicode-escape failures as IOException
            // (or surface them as IllegalArgumentException — JDK version
            // dependent). A malformed file is a user error, but failing
            // the build for it would be hostile — the DSL surface still
            // works without the file. Surface once and fall back.
            logger.warn(malformedMessage(e))
            return emptyMap()
        } catch (e: IllegalArgumentException) {
            logger.warn(malformedMessage(e))
            return emptyMap()
        }

        if (raw.isEmpty) return emptyMap()

        // Snapshot into a plain Kotlin map so the returned object has
        // no live connection to the Properties instance (which the JDK
        // exposes as a Hashtable — synchronised, with rawtypes).
        // Plain HashMap, not LinkedHashMap, since Properties iteration
        // order is unspecified (it extends Hashtable) so we don't have
        // a meaningful order to preserve anyway.
        val out = HashMap<String, String>(raw.size)
        for ((rawKey, rawValue) in raw) {
            val key = rawKey?.toString() ?: continue
            if (!key.startsWith(KEY_PREFIX)) continue
            val stripped = key.substring(KEY_PREFIX.length)
            if (stripped.isEmpty()) continue
            out[stripped] = rawValue?.toString().orEmpty()
        }
        return out
    }

    private fun malformedMessage(cause: Throwable): String =
        "Bugsee: failed to parse bugsee.properties; falling back to DSL and " +
            "defaults — ${cause.message}"
}
