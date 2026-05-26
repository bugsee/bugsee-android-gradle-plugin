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
 *     appToken("your-app-token")
 *     endpoint.set("https://api.bugsee.com")
 *     debug.set(false)
 *     feedback.set(false)
 *     ndk {
 *         enabled.set(true)
 *     }
 *     buildInfo {
 *         sizeAnalysis {
 *             enabled.set(true)
 *             buildConfiguration.set("release")
 *         }
 *     }
 * }
 * ```
 *
 * Every property below is also settable via
 * `<rootProject>/bugsee.properties` using the matching `plugin.<path>`
 * key (e.g. `plugin.debug=true`, `plugin.buildInfo.sizeAnalysis.enabled=true`).
 * DSL `.set(…)` calls take precedence over the properties file, which
 * itself takes precedence over the built-in defaults. See the
 * plugin's README and [com.bugsee.android.gradle.config.PluginPropertiesApplier]
 * for the full key reference and precedence rules.
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
     * NDK integration configuration block — gates the runtime
     * native-crash handler dependency and the per-variant debug-symbol
     * upload task. See [BugseeNdkExtension] for the option surface.
     *
     * Disabled by default; opt in for apps that ship native code.
     */
    val ndk: BugseeNdkExtension = objects.newInstance(BugseeNdkExtension::class.java)

    /**
     * Configure NDK integration via a DSL block.
     *
     * ```kotlin
     * bugsee {
     *     ndk {
     *         enabled.set(true)
     *         forceDebugSymbolsUpload.set(false)
     *     }
     * }
     * ```
     */
    fun ndk(action: Action<BugseeNdkExtension>) {
        action.execute(ndk)
    }

    /**
     * Include the Bugsee in-app feedback module.
     *
     * When `true`, the plugin auto-adds `com.bugsee:bugsee-android-feedback` to the
     * consuming app's `implementation` configuration, enabling the in-app feedback /
     * report-submission UI. Skipped if the app already declares the artifact.
     *
     * Default: `false`
     */
    val feedback: Property<Boolean> = objects.property(Boolean::class.javaObjectType).convention(false)

    /**
     * Build-info configuration block. Always-on by default; registers
     * a Bugsee build record on every release variant with metadata
     * (version, build, package_id, VCS, machine, plugin/SDK
     * versions, timings, artefact file size). The in-build size-check
     * thresholds also live under this block.
     *
     * Disable via `buildInfo { enabled.set(false) }` to opt out
     * entirely (e.g. for firewalled CI).
     */
    val buildInfo: BugseeBuildInfoExtension = objects.newInstance(BugseeBuildInfoExtension::class.java)

    /**
     * Configure build-info via a DSL block.
     *
     * ```kotlin
     * bugsee {
     *     buildInfo {
     *         enabled.set(true)
     *         allBuildTypes.set(false)
     *         sizeCheck { … }
     *     }
     * }
     * ```
     */
    fun buildInfo(action: Action<BugseeBuildInfoExtension>) {
        action.execute(buildInfo)
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
     * Consolidate every Bugsee extension `ContentProvider` into the SDK's
     * single `BugseeInitProvider` to shave per-provider cold-start
     * overhead.
     *
     * When `true` (default), the plugin:
     * 1. Scans the merged `AndroidManifest.xml` for `<provider>` entries
     *    whose `android:name` matches `Bugsee*InitProvider` (excluding
     *    the core `BugseeInitProvider`).
     * 2. Removes those entries from the merged manifest.
     * 3. Rewrites `BugseeInitProvider.initializeExtensions()` to call
     *    each matching extension's `register<Name>Extension()` static
     *    method, so the registrations still happen during the normal SDK
     *    startup path — just in one process instead of one per
     *    `ContentProvider`.
     *
     * When `false`, every extension keeps its own `ContentProvider` and
     * the SDK's `initializeExtensions()` remains a no-op.
     *
     * ```kotlin
     * bugsee {
     *     optimizeExtensionsLoading.set(false)
     * }
     * ```
     */
    val optimizeExtensionsLoading: Property<Boolean> =
        objects.property(Boolean::class.javaObjectType).convention(true)

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
     * Convenience method to enable or disable the in-app feedback module.
     *
     * Equivalent to `feedback.set(enabled)`.
     *
     * @param enabled `true` to pull in the Bugsee feedback module.
     */
    fun feedback(enabled: Boolean) {
        feedback.set(enabled)
    }
}
