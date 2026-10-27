package com.bugsee.android.gradle.config

import com.bugsee.android.gradle.BugseePluginExtension
import org.gradle.api.Project
import org.gradle.api.logging.Logger

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
        applyText(text, extension, project.logger, project.rootProject.layout.projectDirectory.asFile)
    }

    /**
     * Test seam: apply already-loaded text against the extension with
     * an explicit logger. Production goes through [applyTo] which
     * resolves text via `providers.fileContents(...)` and uses the
     * project's logger. Tests inject a recording logger here to pin
     * log-level + message-content contracts that would otherwise
     * disappear into the real Gradle logger.
     */
    internal fun applyText(text: String?, extension: BugseePluginExtension, logger: Logger, fileDir: java.io.File? = null) {
        val props = PluginPropertiesLoader.load(text, logger)
        if (props.isEmpty()) return

        // Resolve the loader's own verbosity from the file itself —
        // `plugin.debug=true` controls whether successful applies are
        // logged. If the key isn't in the file, default to quiet.
        val verbose = parseBooleanOrNull(props["debug"]) == true

        val bindings = bindings(extension, fileDir)
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
     * Bindings are derived from [SettingDefinitions] (the single
     * registry of file-settable keys). Each applies its parsed value as
     * a Property *convention*, so DSL `.set(...)` still wins.
     */
    private class Binding(val key: String, val apply: (String) -> Unit)

    private fun bindings(ext: BugseePluginExtension, fileDir: java.io.File?): List<Binding> =
        SettingDefinitions.all.map { def -> bind(def, ext, fileDir) }

    private fun <T : Any> bind(def: SettingDef<T>, ext: BugseePluginExtension, fileDir: java.io.File?) =
        Binding(def.key) { raw -> def.property(ext).convention(def.parseFileValue(raw, fileDir)) }

    private fun parseBooleanOrNull(raw: String?): Boolean? = SettingDefinitions.parseBooleanOrNull(raw)
}
