package com.bugsee.android.gradle.config

import org.gradle.api.logging.Logger

/** Per-variant entries parsed from `variant.<name>.*` keys. */
internal data class VariantFileEntries(
    val appToken: String?,
    val plugin: Map<String, String>,
)

/**
 * Parsed `bugsee.properties`: root `app_token` / `plugin.*` plus
 * `variant.<name>.app_token` / `variant.<name>.plugin.<path>` entries.
 */
internal data class SettingsFile(
    val rootAppToken: String?,
    val rootPlugin: Map<String, String>,
    val variants: Map<String, VariantFileEntries>,
) {
    companion object {
        val EMPTY = SettingsFile(null, emptyMap(), emptyMap())
    }
}

internal object SettingsFileParser {

    private const val TOKEN_KEY = "app_token"
    private const val PLUGIN_PREFIX = "plugin."
    private const val VARIANT_PREFIX = "variant."

    /**
     * @param reportMalformedFile false when [PluginPropertiesApplier] has already reported a
     *        malformed file for this build, so the warning is not repeated.
     */
    fun parse(text: String?, logger: Logger, reportMalformedFile: Boolean = true): SettingsFile {
        val all = PluginPropertiesLoader.readAll(text, logger, reportMalformedFile)
        if (all.isEmpty()) return SettingsFile.EMPTY

        var rootToken: String? = null
        val rootPlugin = HashMap<String, String>()
        val tokens = HashMap<String, String?>()
        val plugins = HashMap<String, MutableMap<String, String>>()

        for ((key, rawValue) in all) {
            val value = rawValue.trim()
            when {
                key == TOKEN_KEY -> rootToken = tokenOrNull(key, value, logger)
                key.startsWith(PLUGIN_PREFIX) -> {
                    val stripped = key.substring(PLUGIN_PREFIX.length)
                    if (stripped.isNotEmpty()) rootPlugin[stripped] = value
                }
                key.startsWith(VARIANT_PREFIX) -> {
                    val rest = key.substring(VARIANT_PREFIX.length)
                    val dot = rest.indexOf('.')
                    val name = if (dot > 0) rest.substring(0, dot) else ""
                    val remainder = if (dot > 0) rest.substring(dot + 1) else ""
                    when {
                        name.isEmpty() || remainder.isEmpty() ->
                            logger.info("Bugsee: ignoring malformed key '$key' in bugsee.properties.")
                        remainder == TOKEN_KEY -> {
                            tokens[name] = tokenOrNull(key, value, logger)
                            plugins.getOrPut(name) { HashMap() }
                        }
                        remainder.startsWith(PLUGIN_PREFIX) -> {
                            val path = remainder.substring(PLUGIN_PREFIX.length)
                            val def = SettingDefinitions.byKey(path)
                            when {
                                def == null ->
                                    logger.info("Bugsee: ignoring unknown setting '$key' in bugsee.properties.")
                                def.scope == SettingScope.GLOBAL ->
                                    logger.warn(
                                        "Bugsee: '$key' ignored — plugin.$path is a project-wide setting " +
                                            "and cannot be set per variant. Use plugin.$path instead.",
                                    )
                                else -> plugins.getOrPut(name) { HashMap() }[path] = value
                            }
                        }
                        else ->
                            logger.info("Bugsee: ignoring malformed key '$key' in bugsee.properties.")
                    }
                }
            }
        }

        val variants = HashMap<String, VariantFileEntries>()
        for (name in tokens.keys + plugins.keys) {
            variants[name] = VariantFileEntries(tokens[name], plugins[name].orEmpty())
        }
        return SettingsFile(rootToken, rootPlugin, variants)
    }

    private fun tokenOrNull(key: String, value: String, logger: Logger): String? {
        if (value.isNotEmpty()) return value
        logger.warn(
            "Bugsee: bugsee.properties declares $key with an empty value — falling through to next resolver source.",
        )
        return null
    }
}
