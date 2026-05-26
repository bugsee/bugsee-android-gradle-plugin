package com.bugsee.android.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * DSL block for the **NDK** integration — enables both the runtime
 * native-crash handler (auto-adds `com.bugsee:bugsee-android-ndk` to
 * the consuming app's `implementation` configuration) and the
 * per-variant `uploadBugsee{Variant}Native` task that zips the
 * unstripped `.so` files AGP extracts to
 * `intermediates/native_debug_metadata/{variant}/out/`, hashes the
 * zip, and PUTs it to the appserver's symbols endpoint.
 *
 * The two halves are bundled under one `enabled` flag because users
 * who don't ship native code want neither, and users who do ship
 * native code want both (catching the crash without symbols is
 * unsymbolicated; uploading symbols without the runtime handler
 * captures nothing to symbolicate).
 *
 * ```kotlin
 * bugsee {
 *     ndk {
 *         enabled.set(true)
 *         // forceDebugSymbolsUpload.set(false) // default — opt in for fresh CI runners
 *     }
 * }
 * ```
 *
 * Settable via `plugin.ndk.<name>` in `<rootProject>/bugsee.properties`;
 * DSL `.set(…)` wins. See the plugin README for the full key list.
 */
abstract class BugseeNdkExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Master gate for the NDK lane. When `true`:
     *   - The plugin auto-adds `com.bugsee:bugsee-android-ndk` to the
     *     consuming app's `implementation` configuration so the SDK
     *     can capture native crashes via the bundled Crashpad handler.
     *   - The per-variant `uploadBugsee{Variant}Native` task runs as
     *     finalizer for `assemble{Variant}` and `bundle{Variant}`,
     *     uploading the build's native debug symbols to the appserver.
     *
     * Skipped if the app already declares the `bugsee-android-ndk`
     * artifact (no double-add).
     *
     * Default: `false`
     */
    val enabled: Property<Boolean> =
        objects.property(Boolean::class.javaObjectType).convention(false)

    /**
     * Force native debug-symbol upload on every build, bypassing the
     * local SHA-1 cache.
     *
     * The upload task records the SHA-1 of the last successfully
     * uploaded symbol zip at
     * `.gradle/bugsee/native-symbol-cache.json` (keyed by
     * `sha1Hex(appToken):variantName`); a subsequent build whose zip
     * hashes the same value skips the PUT (the server already has
     * these exact symbols).
     *
     * Set this to `true` to bypass that check and always re-upload —
     * useful for fresh CI runners (no local cache to short-circuit
     * against), or recovery scenarios where the server-side store
     * has been wiped and the local cache no longer reflects reality.
     *
     * Only has effect when [enabled] is also `true`.
     *
     * Default: `false`
     */
    val forceDebugSymbolsUpload: Property<Boolean> =
        objects.property(Boolean::class.javaObjectType).convention(false)
}
