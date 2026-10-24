package com.bugsee.android.gradle.config

import com.bugsee.android.gradle.BugseePluginExtension
import com.bugsee.android.gradle.upload.AppTokenResolver
import org.gradle.api.logging.Logger
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

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
 *
 * No-variant-input fast path: the production paths ([effectiveFor], [globalValue],
 * [globalValueOrNull], [anyScopeValue]) assume `PluginPropertiesApplier` has layered the root file
 * in as conventions, so the global property already encodes DSL > root file > default. Without
 * `preferSettingsFile` and without any `variant { }` block or `variant.*` file key for the setting
 * and names at hand, they use the global property directly (linked, or read exactly as the code
 * before per-variant settings read it) and never probe or read it early. A global property is
 * probed only when a `variant.*` file key competes with it in default mode, or by [resolve].
 */
internal class SettingsResolver(
    private val global: BugseePluginExtension,
    private val file: SettingsFile,
    private val objects: ObjectFactory,
    private val logger: Logger,
) {

    private val warnedShadowKeys = HashSet<String>()
    private val warnedMalformedKeys = HashSet<String>()
    private var warnedIgnoredExcludes = false
    private var warnedRootTokenEmpty = false

    /**
     * The value for [def] in [ctx], or null when no source sets it (the caller falls back to the
     * built-in default). `ctx == null` is the global scope: only [SettingSource.DSL_GLOBAL] and
     * [SettingSource.FILE_ROOT] can win. GLOBAL-scoped settings always resolve at global scope.
     */
    fun <T : Any> resolve(def: SettingDef<T>, ctx: VariantContext?): Resolved<T>? =
        resolve(def, ctx, warnShadow = true)

    /**
     * The global-scope value of [def]: the global property's `get()` (DSL > root file convention >
     * default), or with `preferSettingsFile` the root file value first. Throws where `get()` does
     * (nothing sets [def] and it has no default convention); use [globalValueOrNull] for those.
     */
    fun <T : Any> globalValue(def: SettingDef<T>): T =
        rootFileValueIfPreferred(def) ?: def.property(global).get()

    /** As [globalValue], but `orNull` on the global property. */
    fun <T : Any> globalValueOrNull(def: SettingDef<T>): T? =
        rootFileValueIfPreferred(def) ?: def.property(global).orNull

    /**
     * A new extension holding [ctx]'s resolved view. VARIANT-scoped settings resolve for [ctx];
     * GLOBAL-scoped settings and `instrumentation.excludes` come from the global scope.
     *
     * Each effective property is LINKED to the property it resolves from (the global one, or the
     * winning `variant { }` block's), so a DSL value set or changed after this call still reaches
     * the consumers, as it did when they were wired to the global extension directly. Only a file
     * value that wins over the global property (a `variant.*` key, or the root key with
     * `preferSettingsFile`) is copied: file values are static. A setting absent in its source stays
     * absent here. With no per-variant input every property is simply linked to the global one
     * (see the class KDoc), which is the wiring the tasks had before per-variant settings.
     */
    fun effectiveFor(ctx: VariantContext): BugseePluginExtension {
        warnIgnoredVariantExcludes()
        val effective = objects.newInstance(BugseePluginExtension::class.java)
        for (def in SettingDefinitions.all) copyInto(effective, def, ctx)
        effective.instrumentation.excludes.set(global.instrumentation.excludes)
        return effective
    }

    /** `instrumentation.excludes` is global-only; say so once if a `variant { }` block sets it. */
    private fun warnIgnoredVariantExcludes() {
        if (warnedIgnoredExcludes) return
        warnedIgnoredExcludes = true
        for ((name, block) in global.variantBlocks) {
            if (block.instrumentation.excludes.getOrElse(emptySet()).isNotEmpty()) {
                logger.warn(
                    "Bugsee: variant(\"$name\") instrumentation.excludes is ignored; " +
                        "set it in the top-level bugsee { instrumentation { } } block."
                )
            }
        }
    }

    /**
     * Whether any scope (global, any DSL block, any file variant entry) resolves [def] to true;
     * false if the global scope sets it to false and none resolves true; null if the global scope
     * sets nothing and no scope resolves true (the caller's default then applies).
     * Each scope is resolved with the normal precedence, but the answer is conservative: it may
     * report true for a name that every real variant shadows or that matches no variant at all
     * (e.g. a typo). Used to decide project-wide additions, where over-adding is accepted.
     * Emits no shadow warnings.
     *
     * The global scope contributes the global property's value when present (so a default
     * convention counts as a value); without any per-variant input and without
     * `preferSettingsFile` the result is exactly `if (prop.isPresent) prop.get() else null`.
     */
    fun anyScopeValue(def: SettingDef<Boolean>): Boolean? {
        val globalProp = def.property(global)
        val names = LinkedHashSet<String>().apply {
            addAll(global.variantBlocks.keys)
            addAll(file.variants.keys)
        }
        if (!global.preferSettingsFile.get() && names.isEmpty()) {
            return if (globalProp.isPresent) globalProp.get() else null
        }
        // Any real variant's winner is also the winner for the global scope or for a variant
        // whose single lookup name is the winner's name, so these scopes cover every outcome.
        val values = ArrayList<Boolean>()
        var globalSet = false
        for (scope in listOf(emptyList<String>()) + names.map { listOf(it) }) {
            when (val c = choose(def, scope, warnShadow = false)) {
                is Choice.Fixed -> {
                    values += c.value
                    if (scope.isEmpty()) globalSet = true
                }
                is Choice.Linked ->
                    // The global property itself is the global scope's answer; count it once.
                    if (c.property !== globalProp || scope.isEmpty()) {
                        c.property.orNull?.let {
                            values += it
                            if (scope.isEmpty()) globalSet = true
                        }
                    }
            }
        }
        if (values.any { it }) return true
        // Nothing enables it. If the global scope sets nothing, the default (which callers
        // resolve) applies to every variant that does not override it, so a variant-only `false`
        // must not turn the feature off project-wide.
        if (!globalSet) return null
        return false
    }

    /**
     * The plugin-side app token for [ctx], or null when none is configured (the caller then falls
     * back to the merged manifest at execution time). Default order: `appTokenByVariant` closure,
     * `AppTokenProvider`, DSL `variant { appToken(…) }` blocks in [VariantContext.lookupNames] order,
     * global `defaultAppToken`, then the file (`variant.<name>.app_token` in lookup order, then root
     * `app_token`). With `preferSettingsFile` the file moves to the front. Empty tokens fall through.
     */
    fun appTokenFor(ctx: VariantContext, debug: Boolean): String? {
        if (global.preferSettingsFile.get()) {
            return fileToken(ctx, debug) ?: dslToken(ctx, debug)
        }
        val dsl = dslToken(ctx, debug) ?: return fileToken(ctx, debug)
        warnIfTokenShadowed(ctx)
        return dsl
    }

    // ── Internals ───────────────────────────────────────────────────

    private fun dslToken(ctx: VariantContext, debug: Boolean): String? {
        AppTokenResolver.resolveFromClosureOrProvider(global, ctx.variantName, logger, debug)?.let { return it }
        for (name in ctx.lookupNames) {
            val token = global.variantBlocks[name]?.defaultAppToken
            if (!token.isNullOrEmpty()) {
                if (debug) logger.warn("Bugsee: Using variant(\"$name\") appToken: ${AppTokenResolver.maskToken(token)}")
                return token
            }
        }
        return AppTokenResolver.resolveFromDefaultToken(global, logger, debug)
    }

    private fun fileToken(ctx: VariantContext, debug: Boolean): String? {
        val entry = fileTokenEntry(ctx)
        if (entry == null) {
            warnRootTokenEmptyOnce()
            return null
        }
        val (key, token) = entry
        if (debug) logger.warn("Bugsee: Using $BUGSEE_PROPERTIES_FILENAME:$key: ${AppTokenResolver.maskToken(token)}")
        return token
    }

    /** Warns once when the root `app_token` was consulted and is declared empty. */
    private fun warnRootTokenEmptyOnce() {
        if (!file.rootAppTokenWasEmpty || warnedRootTokenEmpty) return
        warnedRootTokenEmpty = true
        logger.warn(
            "Bugsee: $BUGSEE_PROPERTIES_FILENAME declares $TOKEN_KEY with an empty value — " +
                "falling through to next resolver source.",
        )
    }

    /** The file token for [ctx] with its key: `variant.<name>.app_token` in lookup order, then root `app_token`. */
    private fun fileTokenEntry(ctx: VariantContext): Pair<String, String>? {
        for (name in ctx.lookupNames) {
            val token = file.variants[name]?.appToken
            if (!token.isNullOrEmpty()) return "variant.$name.$TOKEN_KEY" to token
        }
        val root = file.rootAppToken
        return if (root.isNullOrEmpty()) null else TOKEN_KEY to root
    }

    /**
     * Warns once about the `variant.<name>.app_token` key that would have won for [ctx] had the DSL
     * not set a token. A root `app_token` shadowed by the DSL is not warned about.
     */
    private fun warnIfTokenShadowed(ctx: VariantContext) {
        val (key, _) = fileTokenEntry(ctx) ?: return
        if (!key.startsWith(VARIANT_KEY_PREFIX)) return
        if (warnedShadowKeys.add(key)) {
            logger.warn(
                "Bugsee: $key ignored: an app token is set in the DSL " +
                    "(set preferSettingsFile to let the settings file win).",
            )
        }
    }


    private fun <T : Any> copyInto(effective: BugseePluginExtension, def: SettingDef<T>, ctx: VariantContext) {
        val names = if (def.scope == SettingScope.VARIANT) ctx.lookupNames else emptyList()
        val target = def.property(effective)
        when (val c = choose(def, names, warnShadow = true)) {
            is Choice.Linked -> target.set(c.property)
            is Choice.Fixed -> target.set(c.value)
        }
    }

    /** What a scope's value comes from: a live property (DSL or global), or a static file value. */
    private sealed class Choice<T : Any> {
        class Linked<T : Any>(val property: Property<T>) : Choice<T>()
        class Fixed<T : Any>(val value: T) : Choice<T>()
    }

    /**
     * The source of [def] for the scope with lookup [names] (empty = global scope), with the same
     * outcome as [resolve] when `PluginPropertiesApplier` has layered the root file into the global
     * property's conventions, but probing as little as possible:
     *
     * - only `variant { }` blocks named in [names] are probed;
     * - the global property is linked rather than read, and probed only in default mode when a
     *   `variant.<name>.plugin.<key>` entry for [names] competes with it;
     * - with `preferSettingsFile`, a file value (variant, then root) wins and is copied.
     */
    private fun <T : Any> choose(def: SettingDef<T>, names: List<String>, warnShadow: Boolean): Choice<T> {
        val globalProp = def.property(global)
        if (global.preferSettingsFile.get()) {
            fileValue(def, names)?.let { return Choice.Fixed(it.value) }
            dslVariantProperty(def, names)?.let { return Choice.Linked(it) }
            return Choice.Linked(globalProp)
        }
        dslVariantProperty(def, names)?.let {
            if (warnShadow) warnIfShadowing(def, names)
            return Choice.Linked(it)
        }
        if (names.none { file.variants[it]?.plugin?.containsKey(def.key) == true }) {
            return Choice.Linked(globalProp)
        }
        if (ExplicitSetProbe.isExplicitlySet(globalProp)) {
            if (warnShadow) warnIfShadowing(def, names)
            return Choice.Linked(globalProp)
        }
        // A variant file value, or (all malformed) the root file value the global property carries.
        val fromFile = fileValue(def, names)?.takeIf { it.source == SettingSource.FILE_VARIANT }
        return if (fromFile != null) Choice.Fixed(fromFile.value) else Choice.Linked(globalProp)
    }

    /** The root file value of [def] when `preferSettingsFile` puts the file first, else null. */
    private fun <T : Any> rootFileValueIfPreferred(def: SettingDef<T>): T? =
        if (global.preferSettingsFile.get()) fileValue(def, emptyList())?.value else null

    /** The first `variant { }` block property in [names] order that was explicitly set, or null. */
    private fun <T : Any> dslVariantProperty(def: SettingDef<T>, names: List<String>): Property<T>? {
        for (name in names) {
            val block = global.variantBlocks[name] ?: continue
            val prop = def.property(block)
            if (ExplicitSetProbe.isExplicitlySet(prop)) return prop
        }
        return null
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
        dslVariantProperty(def, names)?.let { return Resolved(it.get(), SettingSource.DSL_VARIANT) }
        val prop = def.property(global)
        if (ExplicitSetProbe.isExplicitlySet(prop)) return Resolved(prop.get(), SettingSource.DSL_GLOBAL)
        return null
    }

    private fun <T : Any> fileValue(def: SettingDef<T>, names: List<String>): Resolved<T>? {
        for (name in names) {
            val raw = file.variants[name]?.plugin?.get(def.key) ?: continue
            val value = parse(def, variantFileKey(name, def), raw, warn = true) ?: continue
            return Resolved(value, SettingSource.FILE_VARIANT)
        }
        val raw = file.rootPlugin[def.key] ?: return null
        // PluginPropertiesApplier already warns about malformed root values; do not repeat it.
        val value = parse(def, rootFileKey(def), raw, warn = false) ?: return null
        return Resolved(value, SettingSource.FILE_ROOT)
    }

    private fun <T : Any> parse(def: SettingDef<T>, fileKey: String, raw: String, warn: Boolean): T? =
        try {
            def.parse(raw)
        } catch (e: IllegalArgumentException) {
            if (warn && warnedMalformedKeys.add(fileKey)) logger.warn("Bugsee: ignoring $fileKey=$raw — ${e.message}")
            null
        }

    /**
     * Warns about the `variant.<name>.plugin.<key>` file key that would have won for this scope had
     * the DSL not set [def]. A root `plugin.<key>` shadowed by the DSL is the long-standing documented
     * precedence and is not warned about.
     */
    private fun warnIfShadowing(def: SettingDef<*>, names: List<String>) {
        val shadowed = names.firstOrNull { file.variants[it]?.plugin?.containsKey(def.key) == true }
            ?.let { variantFileKey(it, def) }
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

    private companion object {
        const val TOKEN_KEY = "app_token"
        const val VARIANT_KEY_PREFIX = "variant."
    }
}
