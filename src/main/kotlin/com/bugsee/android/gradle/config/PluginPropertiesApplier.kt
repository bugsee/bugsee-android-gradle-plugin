package com.bugsee.android.gradle.config

import com.bugsee.android.gradle.BugseePluginExtension
import com.bugsee.android.gradle.StartupTier
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property

/**
 * Applies `<rootProject>/bugsee.properties` `plugin.*` overrides to a
 * [BugseePluginExtension] tree as Gradle Property *conventions*.
 *
 * Why conventions: every DSL property in the extension tree is already
 * initialised via `objects.property(...).convention(default)`. A second
 * `.convention(propsValue)` call supersedes the default convention,
 * but is itself superseded by any explicit `.set(...)` from the user's
 * `bugsee { ... }` DSL block. That gives the exact priority order:
 *
 *   DSL `.set(...)`  >  bugsee.properties `plugin.X`  >  built-in default
 *
 * for free, without changing a single existing extension class.
 *
 * Run from [com.bugsee.android.gradle.BugseePlugin.apply] right after
 * `project.extensions.create(...)` builds the extension tree but
 * BEFORE the user's `bugsee { ... }` block evaluates. Gradle invokes
 * the user's DSL after the plugin's apply returns, so our conventions
 * land first.
 */
internal object PluginPropertiesApplier {

    /**
     * Load `bugsee.properties` `plugin.*` keys and apply them as
     * conventions on the matching DSL properties.
     *
     * Successful applies: silent unless `plugin.debug=true` was set in
     * the same file (we read the file once and use its own `debug`
     * value to decide whether to log applies; this avoids the chicken-
     * and-egg of needing the DSL-resolved `debug` value before we've
     * applied the properties layer).
     *
     * Malformed values: warn unconditionally — that's a user error
     * worth surfacing always.
     *
     * Unknown `plugin.*` keys: logged at `info` so `--info` builds can
     * spot typos without spamming normal builds.
     *
     * Idempotent: safe to call multiple times on the same extension.
     * Each invocation replaces the conventions on the bound properties;
     * any user `.set(…)` from the DSL is sticky and beats both the
     * default convention AND any properties-supplied convention.
     */
    fun applyTo(project: Project, extension: BugseePluginExtension) {
        // `providers.fileContents(...)` registers the file as a
        // configuration-cache input — without this wrapper, a file
        // read at apply time would NOT invalidate CC when the user
        // edits bugsee.properties, leading to stale config from the
        // cache. `.asText.orNull` returns null when the file is
        // absent (no log noise — that's the common case).
        val file = project.rootProject.layout.projectDirectory
            .file(BUGSEE_PROPERTIES_FILENAME)
        val text = project.providers.fileContents(file).asText.orNull
        applyText(text, extension, project.logger)
    }

    /**
     * Test seam: apply already-loaded text against the extension with
     * an explicit logger. Production goes through [applyTo] which
     * resolves text via `providers.fileContents(...)` and uses the
     * project's logger. Tests inject a recording logger here to pin
     * log-level + message-content contracts that would otherwise
     * disappear into the real Gradle logger.
     */
    internal fun applyText(text: String?, extension: BugseePluginExtension, logger: Logger) {
        val props = PluginPropertiesLoader.load(text, logger)
        if (props.isEmpty()) return

        // Resolve the loader's own verbosity from the file itself —
        // `plugin.debug=true` controls whether successful applies are
        // logged. If the key isn't in the file, default to quiet.
        val verbose = parseBooleanOrNull(props["debug"]) == true

        val bindings = bindings(extension)
        val knownKeys = bindings.mapTo(HashSet()) { it.key }

        for (binding in bindings) {
            val raw = props[binding.key] ?: continue
            try {
                binding.apply(raw)
                if (verbose) {
                    logger.warn(
                        "Bugsee: bugsee.properties applied plugin.${binding.key}=$raw"
                    )
                }
            } catch (e: Throwable) {
                logger.warn(
                    "Bugsee: ignoring plugin.${binding.key}=$raw — ${e.message}"
                )
            }
        }

        // Surface typos at --info so users can `./gradlew assemble
        // --info | grep Bugsee` to debug their config without normal
        // builds spamming stderr.
        for (key in props.keys) {
            if (key !in knownKeys) {
                logger.info(
                    "Bugsee: unknown key plugin.$key in bugsee.properties — ignoring"
                )
            }
        }
    }

    // ── Binding registry ────────────────────────────────────────────

    /**
     * A single key-to-property binding plus its type coercer.
     *
     * Maintained as an explicit list (not reflection-driven) so a
     * future renamed property surfaces as a missing-binding at PR
     * review time rather than a silently-dropped config value at
     * user-runtime.
     */
    private class Binding(val key: String, val apply: (String) -> Unit)

    private fun bindings(ext: BugseePluginExtension): List<Binding> = listOf(
        // ── Root extension ──────────────────────────────────────────
        stringBinding("endpoint", ext.endpoint),
        boolBinding("debug", ext.debug),
        boolBinding("feedback", ext.feedback),
        boolBinding("optimizeExtensionsLoading", ext.optimizeExtensionsLoading),

        // ── ndk { … } ───────────────────────────────────────────────
        boolBinding("ndk.enabled", ext.ndk.enabled),
        boolBinding("ndk.forceDebugSymbolsUpload", ext.ndk.forceDebugSymbolsUpload),

        // ── buildInfo { … } ─────────────────────────────────────────
        boolBinding("buildInfo.enabled", ext.buildInfo.enabled),
        boolBinding("buildInfo.allBuildTypes", ext.buildInfo.allBuildTypes),

        // ── buildInfo.sizeAnalysis { … } ────────────────────────────
        boolBinding("buildInfo.sizeAnalysis.enabled", ext.buildInfo.sizeAnalysis.enabled),
        stringBinding("buildInfo.sizeAnalysis.buildConfiguration", ext.buildInfo.sizeAnalysis.buildConfiguration),
        boolBinding("buildInfo.sizeAnalysis.chunkedUpload", ext.buildInfo.sizeAnalysis.chunkedUpload),

        // ── buildInfo.sizeCheck { … } ───────────────────────────────
        boolBinding("buildInfo.sizeCheck.enabled", ext.buildInfo.sizeCheck.enabled),
        doubleBinding("buildInfo.sizeCheck.warningPercent", ext.buildInfo.sizeCheck.warningPercent),
        doubleBinding("buildInfo.sizeCheck.failPercent", ext.buildInfo.sizeCheck.failPercent),
        longBinding("buildInfo.sizeCheck.warningBytes", ext.buildInfo.sizeCheck.warningBytes),
        longBinding("buildInfo.sizeCheck.failBytes", ext.buildInfo.sizeCheck.failBytes),

        // ── buildInfo.dependencies { … } ────────────────────────────
        boolBinding("buildInfo.dependencies.enabled", ext.buildInfo.dependencies.enabled),
        stringBinding("buildInfo.dependencies.scope", ext.buildInfo.dependencies.scope),
        boolBinding("buildInfo.dependencies.includeSelectedReason", ext.buildInfo.dependencies.includeSelectedReason),
        intBinding("buildInfo.dependencies.maxCount", ext.buildInfo.dependencies.maxCount),

        // ── buildInfo.timings { … } ─────────────────────────────────
        boolBinding("buildInfo.timings.enabled", ext.buildInfo.timings.enabled),

        // ── instrumentation { … } ───────────────────────────────────
        boolBinding("instrumentation.enabled", ext.instrumentation.enabled),
        boolBinding("instrumentation.okhttp", ext.instrumentation.okhttp),
        boolBinding("instrumentation.httpEngine", ext.instrumentation.httpEngine),
        boolBinding("instrumentation.log", ext.instrumentation.log),
        boolBinding("instrumentation.thread", ext.instrumentation.thread),
        boolBinding("instrumentation.mainThreadMisuse", ext.instrumentation.mainThreadMisuse),
        boolBinding("instrumentation.operationDispatch", ext.instrumentation.operationDispatch),
        boolBinding("instrumentation.compose", ext.instrumentation.compose),
        boolBinding("instrumentation.composeSecure", ext.instrumentation.composeSecure),
        boolBinding("instrumentation.composeInput", ext.instrumentation.composeInput),
        boolBinding("instrumentation.ktor", ext.instrumentation.ktor),
        boolBinding("instrumentation.cronet", ext.instrumentation.cronet),
        enumBinding("instrumentation.startupTier", ext.instrumentation.startupTier, StartupTier::class.java),
    )

    // ── Type coercers ──────────────────────────────────────────────

    private fun boolBinding(key: String, prop: Property<Boolean>) = Binding(key) { raw ->
        val v = parseBooleanOrNull(raw) ?: throw IllegalArgumentException(
            "expected one of true/false (also accepted: yes/no, on/off, 1/0)"
        )
        prop.convention(v)
    }

    private fun stringBinding(key: String, prop: Property<String>) = Binding(key) { raw ->
        // Trim incidental surrounding whitespace; the value itself can
        // contain spaces (e.g. an endpoint URL with a path).
        val trimmed = raw.trim()
        // Empty trimmed string is treated as user error (e.g. someone
        // half-deleted a value during editing). Skip the apply and
        // warn — the default holds, matching the numeric coercers'
        // posture on malformed input. Without this, `convention("")`
        // would silently produce a broken value (e.g. an empty
        // endpoint URL) that fails downstream with a less-pointed
        // error.
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException(
                "value is empty — leave the key out of bugsee.properties " +
                    "to use the default"
            )
        }
        prop.convention(trimmed)
    }

    private fun intBinding(key: String, prop: Property<Int>) = Binding(key) { raw ->
        val v = raw.trim().toIntOrNull()
            ?: throw IllegalArgumentException("expected integer")
        prop.convention(v)
    }

    private fun longBinding(key: String, prop: Property<Long>) = Binding(key) { raw ->
        val v = raw.trim().toLongOrNull()
            ?: throw IllegalArgumentException("expected long integer")
        prop.convention(v)
    }

    private fun doubleBinding(key: String, prop: Property<Double>) = Binding(key) { raw ->
        val v = raw.trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("expected decimal number")
        prop.convention(v)
    }

    private fun <E : Enum<E>> enumBinding(
        key: String,
        prop: Property<E>,
        type: Class<E>,
    ) = Binding(key) { raw ->
        val trimmed = raw.trim()
        val match = type.enumConstants.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "expected one of ${type.enumConstants.joinToString { it.name }} (case-insensitive)"
            )
        prop.convention(match)
    }

    /**
     * Lenient boolean parse: accepts the canonical `true`/`false` plus
     * common aliases users might reach for in a properties file.
     * Returns null when the value is neither, so callers can build a
     * specific error message.
     */
    private fun parseBooleanOrNull(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "true", "yes", "on", "1" -> true
        "false", "no", "off", "0" -> false
        else -> null
    }
}
