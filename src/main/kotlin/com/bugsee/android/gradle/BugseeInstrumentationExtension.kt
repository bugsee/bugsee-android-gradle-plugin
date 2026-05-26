package com.bugsee.android.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * DSL block for controlling individual bytecode instrumentations.
 *
 * ```kotlin
 * bugsee {
 *     instrumentation {
 *         enabled.set(true)           // global switch
 *         okhttp.set(false)           // disable OkHttp instrumentation
 *         httpEngine.set(true)        // HttpEngine network instrumentation
 *         log.set(true)
 *         thread.set(true)
 *         mainThreadMisuse.set(true)
 *         ktor.set(true)              // Ktor HTTP client plugin auto-loading
 *         cronet.set(true)            // Cronet HTTP client plugin auto-loading
 *         startupTier.set(StartupTier.STANDARD) // App-startup bytecode tracing depth
 *     }
 * }
 * ```
 *
 * Properties without an explicit value fall back to gradle properties,
 * then manifest meta-data, then default `true`.
 *
 * Each property is also settable via `plugin.instrumentation.<name>`
 * in `<rootProject>/bugsee.properties` (see the plugin README and
 * [com.bugsee.android.gradle.config.PluginPropertiesApplier]).
 * Note: `plugin.instrumentation.*` keys set the DSL property's
 * `convention`, which makes `Property.isPresent == true` — that
 * short-circuits the legacy Gradle-property + manifest-meta-data
 * fallback chain. The user's DSL `.set(…)` still wins over both.
 */
abstract class BugseeInstrumentationExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Global switch for all bytecode instrumentation.
     *
     * Set to `false` to disable every instrumentation in one shot.
     * When unset, defaults to `true` (falls back through Gradle properties
     * and manifest meta-data).
     */
    val enabled: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /**
     * Inject `BugseeOkHttpInterceptor` into every `OkHttpClient.Builder.build()` call.
     *
     * Requires the `com.bugsee:bugsee-okhttp` dependency to be present.
     * When unset, defaults to `true`.
     */
    val okhttp: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /**
     * Instrument `HttpEngine` (Cronet) network requests for automatic network event capture.
     *
     * When unset, defaults to `true`.
     */
    val httpEngine: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /**
     * Redirect `android.util.Log` calls to `BugseeLogAdapter` for automatic log capture.
     *
     * Covers all 14 overloads of `Log.v/d/i/w/e/wtf`.
     * When unset, defaults to `true`.
     */
    val log: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /**
     * Inject `BugseeThreadAdapter.registerThread()` at the start of `Runnable.run()`
     * and `Thread.run()` to build Java-to-native thread ID mappings for native crash reporting.
     *
     * When unset, defaults to `true`.
     */
    val thread: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /**
     * Detect main-thread misuse: disk I/O, network calls, database operations,
     * and `SharedPreferences` commits on the UI thread.
     *
     * When unset, defaults to `true`.
     */
    val mainThreadMisuse: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /**
     * Track start/end of operations (disk I/O, network, DB, SharedPreferences)
     * for timeline visualization in the Bugsee dashboard.
     *
     * When unset, defaults to `true`.
     */
    val operationDispatch: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /**
     * Inject Bugsee tag metadata into Compose composables via the Kotlin compiler plugin.
     *
     * When unset, defaults to `true`.
     */
    val compose: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /**
     * Compose secure-field auto-detection via Kotlin compiler plugin.
     *
     * When enabled (default), the Bugsee compose-compiler-plugin scans
     * user-code call sites of `TextField`, `OutlinedTextField`, and
     * `BasicTextField` from `androidx.compose.material`,
     * `androidx.compose.material3`, and `androidx.compose.foundation.text`,
     * and injects `Modifier.bugseeSecure()` into calls whose
     * `visualTransformation` argument is statically detectable as
     * `PasswordVisualTransformation`. The marked composables' bounds are
     * then redacted in captured screenshots and video.
     *
     * Disable this flag if you prefer to mark sensitive composables
     * exclusively via manual `Modifier.bugseeSecure()`.
     *
     * Independent of [compose] (tag injection): each subfeature can be
     * enabled or disabled in isolation.
     */
    val composeSecure: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /**
     * Capture Compose touch input events via `AndroidComposeView` instrumentation.
     *
     * When unset, defaults to `true`.
     */
    val composeInput: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /**
     * Depth of app-startup bytecode tracing.
     *
     * Typed [StartupTier] property — the compiler enforces that the value
     * is one of [StartupTier.OFF], [StartupTier.MINIMAL],
     * [StartupTier.STANDARD], [StartupTier.DETAILED], [StartupTier.FULL].
     * Unset falls back through Gradle property
     * `bugsee.instrumentation.startupTier`, then manifest meta-data
     * `com.bugsee.android.instrumentation.startupTier`, then defaults to
     * [StartupTier.STANDARD]. Both fallback sources still accept the
     * case-insensitive tier name as a String (Gradle properties and
     * manifest meta-data are always strings).
     *
     * Example:
     * ```kotlin
     * import com.bugsee.android.gradle.StartupTier
     *
     * bugsee {
     *     instrumentation {
     *         startupTier.set(StartupTier.FULL)
     *     }
     * }
     * ```
     *
     * See [StartupTier] for what each tier wraps.
     */
    val startupTier: Property<StartupTier> = objects.property(StartupTier::class.java)

    /**
     * Auto-load the Bugsee Ktor HTTP client plugin when a Ktor dependency is detected.
     *
     * When unset, defaults to `true`.
     */
    val ktor: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /**
     * Auto-load the Bugsee Cronet HTTP client plugin when a Cronet dependency is detected.
     *
     * When unset, defaults to `true`.
     */
    val cronet: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /**
     * Returns the DSL property for the given instrumentation key, or `null`
     * if the key does not match any known boolean property.
     *
     * Tier-driven instrumentations (currently only `appStartupTracing` via
     * [startupTier]) are intentionally absent — they own their own enum-typed
     * DSL surface and bypass the boolean gate in `InstrumentationRegistrar`.
     */
    internal fun propertyForKey(key: String): Property<Boolean>? = when (key) {
        "okhttp" -> okhttp
        "http_engine" -> httpEngine
        "log" -> log
        "thread" -> thread
        "mainThreadMisuse" -> mainThreadMisuse
        "operationDispatch" -> operationDispatch
        "compose" -> compose
        "composeSecure" -> composeSecure
        "composeInput" -> composeInput
        else -> null
    }
}
