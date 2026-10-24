package com.bugsee.android.gradle

import org.gradle.api.Action
import org.gradle.api.provider.Property

/**
 * The subset of [BugseePluginExtension] settings that may be overridden per
 * build variant inside a `variant("name") { ... }` block.
 *
 * ```kotlin
 * bugsee {
 *     variant("staging") {
 *         endpoint.set("https://staging.example.com")
 *         appToken("STAGING_TOKEN")
 *         ndk { enabled.set(true) }
 *     }
 * }
 * ```
 *
 * Settings that are intentionally ABSENT because they are global-only
 * (they affect project-wide dependency wiring or the uploader, not one
 * variant): `feedback`, `leak`, `sdkAutoLoad`, `cliPath`, `cliVersion`,
 * `cliAutoUpdate`, `uploader`, and the `variant(...)` / `preferSettingsFile`
 * entry points themselves (blocks cannot be nested).
 *
 * Note: `instrumentation.excludes` is ignored inside a variant block; set it
 * on the top-level `bugsee { instrumentation { ... } }` block instead.
 */
interface BugseeVariantSettings {
    /** Bugsee API endpoint URL. See [BugseePluginExtension.endpoint]. */
    val endpoint: Property<String>

    /** Verbose plugin logging. See [BugseePluginExtension.debug]. */
    val debug: Property<Boolean>

    /** Consolidate extension ContentProviders. See [BugseePluginExtension.optimizeExtensionsLoading]. */
    val optimizeExtensionsLoading: Property<Boolean>

    /** NDK integration block. See [BugseeNdkExtension]. */
    val ndk: BugseeNdkExtension

    /** Configure NDK integration via a DSL block. */
    fun ndk(action: Action<BugseeNdkExtension>)

    /** Build-info block. See [BugseeBuildInfoExtension]. */
    val buildInfo: BugseeBuildInfoExtension

    /** Configure build-info via a DSL block. */
    fun buildInfo(action: Action<BugseeBuildInfoExtension>)

    /** Bytecode instrumentation block. See [BugseeInstrumentationExtension]. */
    val instrumentation: BugseeInstrumentationExtension

    /** Configure bytecode instrumentation via a DSL block. */
    fun instrumentation(action: Action<BugseeInstrumentationExtension>)

    /** Set the app token used for this scope (all variants, or this variant's block). */
    fun appToken(token: String)
}
