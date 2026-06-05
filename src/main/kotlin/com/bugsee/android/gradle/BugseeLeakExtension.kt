package com.bugsee.android.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * DSL block for the **leak-detection** integration. When enabled, the plugin
 * auto-adds `com.bugsee:bugsee-android-leak` to the consuming app's
 * `implementation` configuration — mirroring the [BugseeNdkExtension] `ndk`
 * lane, but with a single concern (there is no per-build artifact to upload,
 * only the runtime dependency).
 *
 * Including the leak module is itself the opt-in: once present, the SDK runs
 * memory-leak detection by default (in the production-safe `Fast` mode; thread
 * leaks stay opt-in via the `DetectThreadLeaks` SDK option).
 *
 * ```kotlin
 * bugsee {
 *     leak {
 *         enabled.set(true)
 *     }
 * }
 * ```
 *
 * Settable via `plugin.leak.enabled` in `<rootProject>/bugsee.properties`;
 * DSL `.set(…)` wins.
 */
abstract class BugseeLeakExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Master gate for the leak lane. When `true`, the plugin auto-adds
     * `com.bugsee:bugsee-android-leak` to the consuming app's `implementation`
     * configuration so the SDK can detect memory (and, when enabled, thread)
     * leaks.
     *
     * Skipped if the app already declares the `bugsee-android-leak` artifact
     * (no double-add).
     *
     * Default: `false`
     */
    val enabled: Property<Boolean> =
        objects.property(Boolean::class.javaObjectType).convention(false)
}
