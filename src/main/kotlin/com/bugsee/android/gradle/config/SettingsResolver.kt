package com.bugsee.android.gradle.config

import com.bugsee.android.gradle.BugseePluginExtension
import org.gradle.api.logging.Logger
import org.gradle.api.model.ObjectFactory

/**
 * The names a variant is matched by, in lookup order: the variant name, its
 * product flavors (flavor-dimension order), then its build type. Names are
 * case-sensitive.
 */
internal data class VariantContext(
    val variantName: String,
    val flavorNames: List<String>,
    val buildType: String?,
) {
    /** [variantName], [flavorNames] in order, [buildType]; de-duplicated keeping the first occurrence. */
    val lookupNames: List<String>
        get() = (listOf(variantName) + flavorNames + listOfNotNull(buildType)).distinct()
}

/** Where a resolved setting came from. */
internal enum class SettingSource { DSL_VARIANT, DSL_GLOBAL, FILE_VARIANT, FILE_ROOT }

internal data class Resolved<T>(val value: T, val source: SettingSource)

/**
 * Resolves each [SettingDef] for a variant across the two sources:
 *
 * - DSL: `variant("<name>") { }` blocks in [VariantContext.lookupNames] order, then the global DSL value.
 * - File: `variant.<name>.plugin.<key>` in the same order, then root `plugin.<key>`.
 *
 * Default: DSL > file > built-in default. With `preferSettingsFile`: file > DSL > built-in default.
 * A source is consulted as a whole and the first match wins; values are never merged.
 *
 * DSL values count only when explicitly set ([ExplicitSetProbe]); file root values are read from
 * [file], never back from the global property's convention (where `PluginPropertiesApplier` puts
 * them). Construct this only after the user's DSL has been evaluated.
 */
internal class SettingsResolver(
    private val global: BugseePluginExtension,
    private val file: SettingsFile,
    private val objects: ObjectFactory,
    private val logger: Logger,
) {

    private val warnedShadowKeys = HashSet<String>()
    private val warnedMalformedKeys = HashSet<String>()

    /**
     * The value for [def] in [ctx], or null when no source sets it (the caller falls back to the
     * built-in default). `ctx == null` is the global scope: only [SettingSource.DSL_GLOBAL] and
     * [SettingSource.FILE_ROOT] can win. GLOBAL-scoped settings always resolve at global scope.
     */
    fun <T : Any> resolve(def: SettingDef<T>, ctx: VariantContext?): Resolved<T>? =
        resolve(def, ctx, warnShadow = true)

    /** [resolve] at global scope, else the global property's own value (throws if it has none). */
    fun <T : Any> globalValue(def: SettingDef<T>): T =
        globalValueOrNull(def) ?: def.property(global).get()

    /**
     * A new extension holding [ctx]'s resolved view. VARIANT-scoped settings are set only when
     * [resolve] finds a value (otherwise the built-in default convention stays); GLOBAL-scoped
     * settings and `instrumentation.excludes` come from the global scope.
     */
    fun effectiveFor(ctx: VariantContext): BugseePluginExtension {
        val effective = objects.newInstance(BugseePluginExtension::class.java)
        for (def in SettingDefinitions.all) copyInto(effective, def, ctx)
        val excludes = global.instrumentation.excludes
        if (excludes.isPresent) effective.instrumentation.excludes.set(excludes.get())
        return effective
    }

    /**
     * Whether any scope (global, any DSL block, any file variant entry) resolves [def] to true;
     * false if some scope sets it and none resolves true; null if nothing sets it anywhere.
     * Each scope is resolved with the normal precedence, so a value shadowed in every variant
     * does not count. Emits no shadow warnings.
     */
    fun anyScopeValue(def: SettingDef<Boolean>): Boolean? {
        val names = LinkedHashSet<String>().apply {
            addAll(global.variantBlocks.keys)
            addAll(file.variants.keys)
        }
        // Any real variant's winner is also the winner for the global scope or for a variant
        // whose single lookup name is the winner's name, so these scopes cover every outcome.
        val values = (listOf<VariantContext?>(null) + names.map { VariantContext(it, emptyList(), null) })
            .mapNotNull { resolve(def, it, warnShadow = false)?.value }
        if (values.isEmpty()) return null
        return values.any { it }
    }

    // ── Internals ───────────────────────────────────────────────────

    private fun <T : Any> globalValueOrNull(def: SettingDef<T>): T? =
        resolve(def, null)?.value ?: def.property(global).orNull

    private fun <T : Any> copyInto(effective: BugseePluginExtension, def: SettingDef<T>, ctx: VariantContext) {
        val value = when (def.scope) {
            SettingScope.VARIANT -> resolve(def, ctx)?.value
            SettingScope.GLOBAL -> globalValueOrNull(def)
        }
        if (value != null) def.property(effective).set(value)
    }

    private fun <T : Any> resolve(def: SettingDef<T>, ctx: VariantContext?, warnShadow: Boolean): Resolved<T>? {
        val names = if (ctx == null || def.scope == SettingScope.GLOBAL) emptyList() else ctx.lookupNames
        val preferFile = global.preferSettingsFile.get()

        if (preferFile) {
            return fileValue(def, names) ?: dslValue(def, names)
        }
        val dsl = dslValue(def, names)
        if (dsl != null) {
            if (warnShadow) warnIfShadowing(def, names)
            return dsl
        }
        return fileValue(def, names)
    }

    private fun <T : Any> dslValue(def: SettingDef<T>, names: List<String>): Resolved<T>? {
        for (name in names) {
            val block = global.variantBlocks[name] ?: continue
            val prop = def.property(block)
            if (ExplicitSetProbe.isExplicitlySet(prop)) return Resolved(prop.get(), SettingSource.DSL_VARIANT)
        }
        val prop = def.property(global)
        if (ExplicitSetProbe.isExplicitlySet(prop)) return Resolved(prop.get(), SettingSource.DSL_GLOBAL)
        return null
    }

    private fun <T : Any> fileValue(def: SettingDef<T>, names: List<String>): Resolved<T>? {
        for (name in names) {
            val raw = file.variants[name]?.plugin?.get(def.key) ?: continue
            val value = parse(def, variantFileKey(name, def), raw) ?: continue
            return Resolved(value, SettingSource.FILE_VARIANT)
        }
        val raw = file.rootPlugin[def.key] ?: return null
        val value = parse(def, rootFileKey(def), raw) ?: return null
        return Resolved(value, SettingSource.FILE_ROOT)
    }

    private fun <T : Any> parse(def: SettingDef<T>, fileKey: String, raw: String): T? =
        try {
            def.parse(raw)
        } catch (e: IllegalArgumentException) {
            if (warnedMalformedKeys.add(fileKey)) logger.warn("Bugsee: ignoring $fileKey=$raw — ${e.message}")
            null
        }

    /** Warns about the file key that would have won for this scope had the DSL not set [def]. */
    private fun warnIfShadowing(def: SettingDef<*>, names: List<String>) {
        val shadowed = names.firstOrNull { file.variants[it]?.plugin?.containsKey(def.key) == true }
            ?.let { variantFileKey(it, def) }
            ?: rootFileKey(def).takeIf { file.rootPlugin.containsKey(def.key) }
            ?: return
        if (warnedShadowKeys.add(shadowed)) {
            logger.warn(
                "Bugsee: $shadowed ignored: ${def.key} is set in the DSL " +
                    "(set preferSettingsFile to let the settings file win).",
            )
        }
    }

    private fun variantFileKey(name: String, def: SettingDef<*>) = "variant.$name.plugin.${def.key}"

    private fun rootFileKey(def: SettingDef<*>) = "plugin.${def.key}"
}
