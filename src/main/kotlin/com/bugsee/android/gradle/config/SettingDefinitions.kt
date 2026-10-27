package com.bugsee.android.gradle.config

import com.bugsee.android.gradle.BugseePluginExtension
import com.bugsee.android.gradle.StartupTier
import com.bugsee.android.gradle.upload.UploaderStrategy
import org.gradle.api.provider.Property
import java.io.File

/** Whether a setting may differ per variant or is project-wide. */
internal enum class SettingScope { GLOBAL, VARIANT }

/**
 * A file-settable plugin setting. [key] is the path after `plugin.`
 * (e.g. `buildInfo.sizeCheck.enabled`); [parse] throws
 * [IllegalArgumentException] with a user-facing message on bad input.
 */
internal class SettingDef<T : Any>(
    val key: String,
    val scope: SettingScope,
    val property: (BugseePluginExtension) -> Property<T>,
    /** A string setting holding a filesystem path: a relative file value resolves against the settings file's directory. */
    val pathLike: Boolean = false,
    val parse: (String) -> T,
) {
    /**
     * [parse] of a value read from `bugsee.properties`. A relative [pathLike] value is made absolute
     * against [fileDir] (the directory of the settings file) so every module sees the same path;
     * a null [fileDir] leaves it as written.
     */
    fun parseFileValue(raw: String, fileDir: File?): T {
        val value = parse(raw)
        if (!pathLike || fileDir == null || value !is String) return value
        @Suppress("UNCHECKED_CAST")
        return SettingDefinitions.resolvePath(value, fileDir) as T
    }
}

/** Single registry of settings that `bugsee.properties` can bind. */
internal object SettingDefinitions {

    val all: List<SettingDef<*>> = listOf(
        stringDef("endpoint", SettingScope.VARIANT) { it.endpoint },
        stringDef("cliPath", SettingScope.GLOBAL, pathLike = true) { it.cliPath },
        stringDef("cliVersion", SettingScope.GLOBAL) { it.cliVersion },
        boolDef("cliAutoUpdate", SettingScope.GLOBAL) { it.cliAutoUpdate },
        enumDef("uploader", SettingScope.GLOBAL, UploaderStrategy::class.java) { it.uploader },
        boolDef("debug", SettingScope.VARIANT) { it.debug },
        boolDef("failOnUploadError", SettingScope.VARIANT) { it.failOnUploadError },
        boolDef("feedback", SettingScope.GLOBAL) { it.feedback },
        boolDef("optimizeExtensionsLoading", SettingScope.VARIANT) { it.optimizeExtensionsLoading },
        boolDef("sdkAutoLoad", SettingScope.GLOBAL) { it.sdkAutoLoad },
        boolDef("ndk.enabled", SettingScope.VARIANT) { it.ndk.enabled },
        boolDef("ndk.allBuildTypes", SettingScope.VARIANT) { it.ndk.allBuildTypes },
        boolDef("ndk.forceDebugSymbolsUpload", SettingScope.VARIANT) { it.ndk.forceDebugSymbolsUpload },
        boolDef("ndk.useMergedNativeLibs", SettingScope.VARIANT) { it.ndk.useMergedNativeLibs },
        boolDef("leak.enabled", SettingScope.GLOBAL) { it.leak.enabled },
        boolDef("buildInfo.enabled", SettingScope.VARIANT) { it.buildInfo.enabled },
        boolDef("buildInfo.allBuildTypes", SettingScope.VARIANT) { it.buildInfo.allBuildTypes },
        boolDef("buildInfo.sizeAnalysis.enabled", SettingScope.VARIANT) { it.buildInfo.sizeAnalysis.enabled },
        stringDef("buildInfo.sizeAnalysis.buildConfiguration", SettingScope.VARIANT) { it.buildInfo.sizeAnalysis.buildConfiguration },
        boolDef("buildInfo.sizeAnalysis.chunkedUpload", SettingScope.VARIANT) { it.buildInfo.sizeAnalysis.chunkedUpload },
        boolDef("buildInfo.sizeCheck.enabled", SettingScope.VARIANT) { it.buildInfo.sizeCheck.enabled },
        doubleDef("buildInfo.sizeCheck.warningPercent", SettingScope.VARIANT) { it.buildInfo.sizeCheck.warningPercent },
        doubleDef("buildInfo.sizeCheck.failPercent", SettingScope.VARIANT) { it.buildInfo.sizeCheck.failPercent },
        longDef("buildInfo.sizeCheck.warningBytes", SettingScope.VARIANT) { it.buildInfo.sizeCheck.warningBytes },
        longDef("buildInfo.sizeCheck.failBytes", SettingScope.VARIANT) { it.buildInfo.sizeCheck.failBytes },
        boolDef("buildInfo.dependencies.enabled", SettingScope.VARIANT) { it.buildInfo.dependencies.enabled },
        stringDef("buildInfo.dependencies.scope", SettingScope.VARIANT) { it.buildInfo.dependencies.scope },
        boolDef("buildInfo.dependencies.includeSelectedReason", SettingScope.VARIANT) { it.buildInfo.dependencies.includeSelectedReason },
        intDef("buildInfo.dependencies.maxCount", SettingScope.VARIANT) { it.buildInfo.dependencies.maxCount },
        boolDef("buildInfo.timings.enabled", SettingScope.VARIANT) { it.buildInfo.timings.enabled },
        boolDef("instrumentation.enabled", SettingScope.VARIANT) { it.instrumentation.enabled },
        boolDef("instrumentation.okhttp", SettingScope.VARIANT) { it.instrumentation.okhttp },
        boolDef("instrumentation.httpEngine", SettingScope.VARIANT) { it.instrumentation.httpEngine },
        boolDef("instrumentation.log", SettingScope.VARIANT) { it.instrumentation.log },
        boolDef("instrumentation.thread", SettingScope.VARIANT) { it.instrumentation.thread },
        boolDef("instrumentation.mainThreadMisuse", SettingScope.VARIANT) { it.instrumentation.mainThreadMisuse },
        boolDef("instrumentation.operationDispatch", SettingScope.VARIANT) { it.instrumentation.operationDispatch },
        boolDef("instrumentation.compose", SettingScope.VARIANT) { it.instrumentation.compose },
        boolDef("instrumentation.composeSecure", SettingScope.VARIANT) { it.instrumentation.composeSecure },
        boolDef("instrumentation.composeInput", SettingScope.VARIANT) { it.instrumentation.composeInput },
        boolDef("instrumentation.ktor", SettingScope.VARIANT) { it.instrumentation.ktor },
        boolDef("instrumentation.cronet", SettingScope.VARIANT) { it.instrumentation.cronet },
        enumDef("instrumentation.startupTier", SettingScope.VARIANT, StartupTier::class.java) { it.instrumentation.startupTier },
    )

    private val byKey: Map<String, SettingDef<*>> = all.associateBy { it.key }

    fun byKey(key: String): SettingDef<*>? = byKey[key]

    /** The boolean setting [key]; throws when there is none or it is not boolean. */
    fun booleanByKey(key: String): SettingDef<Boolean> {
        val def = requireNotNull(byKey[key]) { "no setting $key" }
        // A boolean def parses "true" to a Boolean; any other type yields another type or throws.
        require(runCatching { def.parse("true") }.getOrNull() is Boolean) { "setting $key is not boolean" }
        @Suppress("UNCHECKED_CAST")
        return def as SettingDef<Boolean>
    }

    fun variantScoped(): List<SettingDef<*>> = all.filter { it.scope == SettingScope.VARIANT }

    /**
     * Lenient boolean parse: accepts the canonical `true`/`false` plus
     * common aliases users might reach for in a properties file.
     * Returns null when the value is neither.
     */
    fun parseBooleanOrNull(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "true", "yes", "on", "1" -> true
        "false", "no", "off", "0" -> false
        else -> null
    }

    // ── Type parsers ───────────────────────────────────────────────

    private fun boolDef(key: String, scope: SettingScope, property: (BugseePluginExtension) -> Property<Boolean>) =
        SettingDef(key, scope, property) { raw ->
            parseBooleanOrNull(raw) ?: throw IllegalArgumentException(
                "expected one of true/false (also accepted: yes/no, on/off, 1/0)"
            )
        }

    /**
     * [raw] unchanged when blank or absolute, else [base] + [raw]. Plain `java.io.File` on purpose: no
     * `..` normalisation (symlinks stay valid) and no URI parsing of the value.
     */
    fun resolvePath(raw: String, base: File): String =
        if (raw.isBlank() || File(raw).isAbsolute) raw else File(base, raw).path

    private fun stringDef(
        key: String,
        scope: SettingScope,
        pathLike: Boolean = false,
        property: (BugseePluginExtension) -> Property<String>,
    ) =
        SettingDef(key, scope, property, pathLike) { raw ->
            // Trim incidental whitespace; the value itself may contain
            // spaces. An empty value is a user error (half-deleted
            // line): the default holds rather than an empty endpoint
            // failing downstream with a less pointed error.
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                throw IllegalArgumentException(
                    "value is empty — leave the key out of bugsee.properties " +
                        "to use the default"
                )
            }
            trimmed
        }

    private fun intDef(key: String, scope: SettingScope, property: (BugseePluginExtension) -> Property<Int>) =
        SettingDef(key, scope, property) { raw ->
            raw.trim().toIntOrNull() ?: throw IllegalArgumentException("expected integer")
        }

    private fun longDef(key: String, scope: SettingScope, property: (BugseePluginExtension) -> Property<Long>) =
        SettingDef(key, scope, property) { raw ->
            raw.trim().toLongOrNull() ?: throw IllegalArgumentException("expected long integer")
        }

    private fun doubleDef(key: String, scope: SettingScope, property: (BugseePluginExtension) -> Property<Double>) =
        SettingDef(key, scope, property) { raw ->
            raw.trim().toDoubleOrNull() ?: throw IllegalArgumentException("expected decimal number")
        }

    private fun <E : Enum<E>> enumDef(
        key: String,
        scope: SettingScope,
        type: Class<E>,
        property: (BugseePluginExtension) -> Property<E>,
    ) = SettingDef(key, scope, property) { raw ->
        val trimmed = raw.trim()
        type.enumConstants.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "expected one of ${type.enumConstants.joinToString { it.name }} (case-insensitive)"
            )
    }
}
