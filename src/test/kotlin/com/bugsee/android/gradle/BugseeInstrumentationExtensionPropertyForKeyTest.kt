package com.bugsee.android.gradle

import org.gradle.api.provider.Property
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test
import java.lang.reflect.Modifier
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pin the contract on [BugseeInstrumentationExtension.propertyForKey].
 *
 * The function is called by [com.bugsee.android.gradle.instrumentation.InstrumentationConfigResolver.isFeatureEnabled]
 * with an `Instrumentation.key` string. If the lookup returns `null`,
 * the resolver bypasses the typed DSL setting and falls through to
 * Gradle properties / manifest meta-data / hardcoded default — which
 * silently DISABLES the user's DSL switch on the unknown key.
 *
 * **The bug class this guards against.** An earlier validation pass
 * flagged that the function was non-exhaustive over the DSL's
 * Boolean properties: `httpEngine` (camelCase variant), `ktor`,
 * `cronet` were missing. Even though the production code paths that
 * read those DSL properties today happen to bypass `propertyForKey`
 * (the auto-install flow at `BugseePlugin.kt:131-143` calls
 * `isFeatureEnabled(property: Property<Boolean>)` directly with the
 * typed DSL property), a future bytecode instrumentation that
 * added a registrar entry with `key = "ktor"` would silently
 * disable the user's `instrumentation { ktor.set(false) }` setting.
 *
 * These tests assert:
 *  1. Every well-known `Instrumentation.key` returns the correct
 *     DSL property (positive cases per key).
 *  2. `propertyForKey` is exhaustive over the DSL's Boolean
 *     properties — a reflective walk over the extension's `Property<Boolean>`
 *     fields verifies each one has at least one key mapping. Future
 *     additions that forget to wire `propertyForKey` will fail this
 *     test rather than silently disabling the new DSL switch.
 *  3. Unknown keys return `null` (no spurious mappings).
 *
 * The HttpEngine snake_case + camelCase aliasing is also explicitly
 * pinned so a refactor that drops one form doesn't go unnoticed.
 */
class BugseeInstrumentationExtensionPropertyForKeyTest {

    private val extension: BugseeInstrumentationExtension by lazy {
        // ProjectBuilder gives us a real ObjectFactory so the
        // extension can construct its Property fields. Using
        // `objects.property(...)` inline would require mirroring
        // the @Inject ObjectFactory plumbing.
        val project = ProjectBuilder.builder().build()
        project.objects.newInstance(BugseeInstrumentationExtension::class.java)
    }

    // ── Positive cases — every documented registrar key maps to the right property ─

    @Test fun `okhttp key resolves to okhttp property`() {
        assertSame(extension.okhttp, extension.propertyForKey("okhttp"))
    }

    @Test fun `http_engine (snake_case) resolves to httpEngine property`() {
        // The Instrumentation registrar uses the snake_case form
        // for backward-compat with existing gradle.properties /
        // manifest-meta-data entries.
        assertSame(extension.httpEngine, extension.propertyForKey("http_engine"))
    }

    @Test fun `httpEngine (camelCase) ALSO resolves to httpEngine property`() {
        // The DSL exposes the property as `httpEngine`. The
        // camelCase alias means a future call site can use either
        // form without bypassing the DSL setting. Pin both so a
        // mutation that drops one breaks here.
        assertSame(extension.httpEngine, extension.propertyForKey("httpEngine"))
    }

    @Test fun `log key resolves to log property`() {
        assertSame(extension.log, extension.propertyForKey("log"))
    }

    @Test fun `thread key resolves to thread property`() {
        assertSame(extension.thread, extension.propertyForKey("thread"))
    }

    @Test fun `mainThreadMisuse key resolves to mainThreadMisuse property`() {
        assertSame(extension.mainThreadMisuse, extension.propertyForKey("mainThreadMisuse"))
    }

    @Test fun `operationDispatch key resolves to operationDispatch property`() {
        assertSame(extension.operationDispatch, extension.propertyForKey("operationDispatch"))
    }

    @Test fun `composeInput key resolves to composeInput property`() {
        assertSame(extension.composeInput, extension.propertyForKey("composeInput"))
    }

    @Test fun `compose key resolves to compose property`() {
        assertSame(extension.compose, extension.propertyForKey("compose"))
    }

    @Test fun `composeSecure key resolves to composeSecure property`() {
        assertSame(extension.composeSecure, extension.propertyForKey("composeSecure"))
    }

    @Test fun `ktor key resolves to ktor property (future-proofing)`() {
        // ktor doesn't have a registrar entry today, but the
        // mapping is wired so a future bytecode instrumentation
        // for Ktor with key="ktor" picks up the DSL switch on
        // day one rather than silently bypassing it.
        assertSame(extension.ktor, extension.propertyForKey("ktor"))
    }

    @Test fun `cronet key resolves to cronet property (future-proofing)`() {
        assertSame(extension.cronet, extension.propertyForKey("cronet"))
    }

    // ── Negative cases — unknown keys must not resolve ───────────────

    @Test fun `unknown key returns null`() {
        assertNull(extension.propertyForKey("definitely-not-a-real-key"))
    }

    @Test fun `tier-driven appStartupTracing key returns null (handled separately)`() {
        // AppStartupTracing is `isTierDriven = true` — bypasses the
        // boolean-key path in InstrumentationRegistrar. Pin the
        // intentional absence so a future addition doesn't break
        // the tier-handling contract.
        assertNull(extension.propertyForKey("appStartupTracing"))
    }

    @Test fun `tier-driven extensionsInit key returns null (handled separately)`() {
        // ExtensionsInit is also `isTierDriven = true` — same
        // reasoning. Pin the intentional absence.
        assertNull(extension.propertyForKey("extensionsInit"))
    }

    @Test fun `empty string key returns null`() {
        assertNull(extension.propertyForKey(""))
    }

    // ── Exhaustiveness — every DSL Boolean property must be reachable via at least one key ─

    /**
     * Walk every `Property<Boolean>` field declared on the
     * extension via reflection and assert each has at least one
     * mapping in `propertyForKey`. A future addition that forgets
     * to wire the new property will silently fail to honor DSL
     * settings until this test surfaces the gap.
     */
    @Test fun `every DSL Boolean property is reachable via at least one propertyForKey entry`() {
        val booleanProperties = collectBooleanProperties(extension)
        assertTrue(
            booleanProperties.isNotEmpty(),
            "reflection must find at least one Property<Boolean> on the extension",
        )

        // Collect every key the function knows about by probing it
        // with EVERY field name + every known alias. Since
        // `propertyForKey` is a closed `when`-expression on a String,
        // there's no way to introspect its full key set without
        // probing — but we can enumerate plausible keys (each
        // property's name + the known aliases) and verify every
        // Boolean property is reachable.
        val knownKeys = listOf(
            "okhttp",
            "http_engine", "httpEngine",
            "log",
            "thread",
            "mainThreadMisuse",
            "operationDispatch",
            "compose",
            "composeSecure",
            "composeInput",
            "ktor",
            "cronet",
        )
        val reached = knownKeys.mapNotNull { extension.propertyForKey(it) }.toSet()

        for ((fieldName, property) in booleanProperties) {
            assertTrue(
                property in reached,
                "DSL Property<Boolean> '$fieldName' is NOT reachable via any propertyForKey entry. " +
                    "Add a mapping in `BugseeInstrumentationExtension.propertyForKey` so that the " +
                    "key-based fallback chain " +
                    "(`InstrumentationConfigResolver.isFeatureEnabled(key)`) honors `instrumentation " +
                    "{ $fieldName.set(...) }` instead of silently bypassing it.",
            )
        }
    }

    @Test fun `enabled property is intentionally NOT in propertyForKey (global switch)`() {
        // The `enabled` property is the global switch handled
        // separately by `InstrumentationConfigResolver.isGloballyEnabled()`.
        // It must NOT be reachable via the per-feature key path —
        // otherwise a feature key colliding with the global key
        // would create surprising semantics.
        assertNull(
            extension.propertyForKey("enabled"),
            "the global `enabled` switch must NOT be reachable via propertyForKey",
        )
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Reflectively enumerate every `Property<Boolean>` field on
     * the extension instance (plus its abstract supertype, since
     * Gradle generates property fields lazily on a subclass).
     */
    @Suppress("UNCHECKED_CAST")
    private fun collectBooleanProperties(
        ext: BugseeInstrumentationExtension,
    ): List<Pair<String, Property<Boolean>>> {
        val result = mutableListOf<Pair<String, Property<Boolean>>>()
        // Walk the actual concrete class (Gradle generates a
        // decorated subclass) AND its declared superclass for the
        // abstract `val foo: Property<Boolean>` fields.
        val classes = generateSequence<Class<*>>(ext.javaClass) {
            it.superclass.takeIf { sc -> sc != Any::class.java }
        }.toList()

        val seen = HashSet<String>()
        for (cls in classes) {
            for (field in cls.declaredFields) {
                if (field.name in seen) continue
                if (Modifier.isStatic(field.modifiers)) continue
                field.isAccessible = true
                val value = try {
                    field.get(ext)
                } catch (_: Exception) {
                    continue
                }
                if (value is Property<*>) {
                    // Exclude the typed `startupTier` (StartupTier
                    // enum) and the global `enabled` — see KDoc on
                    // propertyForKey for why those don't go
                    // through this code path.
                    if (field.name == "startupTier" || field.name == "enabled") {
                        seen.add(field.name)
                        continue
                    }
                    // Best-effort probe: try setting a Boolean to
                    // confirm the runtime type. ObjectFactory-
                    // produced Property objects type-check at set
                    // time, so this acts as the runtime type
                    // discriminator.
                    val asBool = value as? Property<Boolean>
                    if (asBool != null) {
                        // Verify it's actually a Property<Boolean>
                        // by trying to call set with a sentinel.
                        try {
                            asBool.set(true)
                            asBool.set(null as Boolean?)  // unset
                        } catch (_: Throwable) {
                            // Not a Property<Boolean> — skip.
                            seen.add(field.name)
                            continue
                        }
                        result.add(field.name to asBool)
                    }
                    seen.add(field.name)
                }
            }
        }
        return result
    }
}
