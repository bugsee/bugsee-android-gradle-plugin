package com.bugsee.android.gradle

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Main DSL extension for the Bugsee Gradle plugin.
 *
 * ```kotlin
 * bugsee {
 *     endpoint.set("https://api.bugsee.com")
 *     debug.set(false)
 *     ndk.set(false)
 *     sizeAnalysis {
 *         enabled.set(true)
 *         buildConfiguration.set("release")
 *     }
 * }
 * ```
 */

abstract class BugseePluginExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Bugsee API endpoint URL.
     *
     * Override this when using a self-hosted or proxy endpoint.
     *
     * Default: `https://api.bugsee.com`
     */
    val endpoint: Property<String> = objects.property(String::class.java).convention("https://api.bugsee.com")

    /**
     * Enable verbose debug logging from the plugin.
     *
     * When `true`, the plugin logs detailed information about manifest injection,
     * token resolution, symbol uploads, and instrumentation decisions.
     *
     * Default: `false`
     */
    val debug: Property<Boolean> = objects.property(Boolean::class.javaObjectType).convention(false)

    /**
     * Enable NDK native debug symbol upload.
     *
     * When `true`, the plugin locates native debug symbols produced by the build
     * and uploads them to the Bugsee backend for native crash symbolication.
     *
     * Default: `false`
     */
    val ndk: Property<Boolean> = objects.property(Boolean::class.javaObjectType).convention(false)

    /**
     * Force NDK symbol upload on every build, bypassing the local SHA-1 cache.
     *
     * By default the plugin caches the SHA-1 hash of uploaded native symbols and
     * skips the upload when the symbols haven't changed. Set this to `true` to
     * always upload regardless of the cache state (e.g. for CI release builds).
     *
     * Only has effect when [ndk] is also `true`.
     *
     * Default: `false`
     */
    val ndkForceUpload: Property<Boolean> = objects.property(Boolean::class.javaObjectType).convention(false)

    /**
     * Use the chunked upload protocol instead of a single PUT for size-analysis bundles.
     *
     * Chunks are deduplicated across builds, so CI runs that only change
     * a small fraction of the bundle upload much faster on repeat runs.
     * Disabled by default while the endpoint rolls out; set to `true` to opt in.
     *
     * Default: `false`
     */
    val chunkedUpload: Property<Boolean> = objects.property(Boolean::class.javaObjectType).convention(false)

    /**
     * Size analysis configuration block.
     *
     * Disabled by default. Enable via `sizeAnalysis { enabled.set(true) }`.
     */
    val sizeAnalysis: BugseeSizeAnalysisExtension = objects.newInstance(BugseeSizeAnalysisExtension::class.java)

    /**
     * Configure size analysis via a DSL block.
     *
     * ```kotlin
     * bugsee {
     *     sizeAnalysis {
     *         enabled.set(true)
     *         buildConfiguration.set("release")
     *     }
     * }
     * ```
     */
    fun sizeAnalysis(action: Action<BugseeSizeAnalysisExtension>) {
        action.execute(sizeAnalysis)
    }

    /**
     * Per-feature bytecode instrumentation configuration block.
     *
     * Individual instrumentations (OkHttp, Log, Thread, Compose, etc.) can be
     * toggled independently, or the entire instrumentation pipeline can be
     * disabled via `instrumentation { enabled.set(false) }`.
     */
    val instrumentation: BugseeInstrumentationExtension = objects.newInstance(BugseeInstrumentationExtension::class.java)

    /**
     * Configure bytecode instrumentation via a DSL block.
     *
     * ```kotlin
     * bugsee {
     *     instrumentation {
     *         enabled.set(true)
     *         okhttp.set(false) // disable OkHttp interceptor injection
     *     }
     * }
     * ```
     */
    fun instrumentation(action: Action<BugseeInstrumentationExtension>) {
        action.execute(instrumentation)
    }

    /**
     * Global instrumentation on/off switch.
     *
     * Delegates to [BugseeInstrumentationExtension.enabled] for backward compatibility.
     * Prefer using the `instrumentation { }` block for new code.
     *
     * Default: `true`
     */
    val instrumentationEnabled: Property<Boolean> get() = instrumentation.enabled

    /** Default app token, used when no variant-specific token is provided. */
    internal var defaultAppToken: String? = null

    /** Closure-style variant token resolver: `(variantName) -> token?`. */
    internal var appTokenByVariant: ((String) -> String?)? = null

    /** Strong-typed app token provider interface. */
    internal var appTokenProvider: AppTokenProvider? = null

    /**
     * Set the default Bugsee app token used for all variants.
     *
     * @param token The app token from the Bugsee dashboard.
     */
    fun appToken(token: String) {
        defaultAppToken = token
    }

    /**
     * Set a per-variant app token resolver.
     *
     * The function receives the variant name (e.g. `"release"`) and returns
     * the app token to use for that variant, or `null` to fall back to the
     * default token.
     *
     * @param tokenResolver A function mapping variant name to app token.
     */
    fun appToken(tokenResolver: (String) -> String?) {
        appTokenByVariant = tokenResolver
    }

    /**
     * Set a strong-typed [AppTokenProvider] for variant-aware token resolution.
     *
     * @param provider An implementation of [AppTokenProvider].
     */
    fun appToken(provider: AppTokenProvider) {
        appTokenProvider = provider
    }

    /**
     * Convenience method to enable or disable NDK symbol upload.
     *
     * Equivalent to `ndk.set(enabled)`.
     *
     * @param enabled `true` to enable NDK symbol upload.
     */
    fun ndk(enabled: Boolean) {
        ndk.set(enabled)
    }
}
