package com.bugsee.android.gradle

import org.gradle.api.Action
import org.gradle.api.provider.Property

/**
 * DSL marker for the `bugsee { }` extension and its `variant { }` blocks.
 *
 * Inside a `variant("x") { }` block the Kotlin compiler refuses implicit access
 * to the enclosing [BugseePluginExtension] receiver, so global-only settings
 * cannot be referenced there by accident. Nested blocks such as `ndk { }` do not
 * carry the marker and keep reaching outer members as before.
 */
@DslMarker
annotation class BugseeDsl

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
 * variant): `feedback`, `leak`, `sdkAutoLoad`, `warnDebugOnlySdk`, `warnLibraryModule`, `cliPath`, `cliVersion`,
 * `cliAutoUpdate`, `uploader`, and the `variant(...)` / `preferSettingsFile`
 * entry points themselves (blocks cannot be nested).
 *
 * Note: `instrumentation.excludes` is ignored inside a variant block; set it
 * on the top-level `bugsee { instrumentation { ... } }` block instead.
 *
 * Enforcement: in the Kotlin DSL, referencing a global-only setting inside a
 * `variant { }` block is a compile error (the [BugseeDsl] marker hides the outer
 * receiver). In Groovy there is no such protection, and the setting does not reach
 * the global value either: Gradle runs the closure with DELEGATE_FIRST against the
 * block, which is a full [BugseePluginExtension], so `feedback true`, `leak { }`,
 * `appToken { }` (closure form) or a nested `variant(...)` inside a block write the
 * block's own copy and are SILENTLY IGNORED.
 */
@BugseeDsl
interface BugseeVariantSettings {
    /** Bugsee API endpoint URL. See [BugseePluginExtension.endpoint]. */
    val endpoint: Property<String>

    /** Verbose plugin logging. See [BugseePluginExtension.debug]. */
    val debug: Property<Boolean>

    /** Fail the build when an upload does not complete. See [BugseePluginExtension.failOnUploadError]. */
    val failOnUploadError: Property<Boolean>

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
