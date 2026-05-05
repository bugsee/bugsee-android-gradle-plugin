package com.bugsee.android.gradle

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * DSL block for configuring build size-analysis uploads.
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
abstract class BugseeSizeAnalysisExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Enable APK/AAB upload for size analysis.
     *
     * When `true`, the plugin uploads the build artifact (APK or AAB) to the
     * Bugsee backend for size tracking and comparison across builds.
     *
     * Default: `false`
     */
    val enabled: Property<Boolean> = objects.property(Boolean::class.javaObjectType).convention(false)

    /**
     * Override the build configuration label used for comparison grouping.
     *
     * Builds with the same configuration label are compared against each other
     * in the size-analysis dashboard. Defaults to the Gradle variant name
     * (e.g. `"release"`, `"freeRelease"`) when not set.
     */
    val buildConfiguration: Property<String> = objects.property(String::class.java)

    /**
     * In-build size-check configuration. See [BugseeSizeCheckExtension]
     * for thresholds and the env-var fallback contract. The check
     * piggybacks on the size-analysis upload so it's only available
     * when [enabled] is `true`.
     */
    val sizeCheck: BugseeSizeCheckExtension =
        objects.newInstance(BugseeSizeCheckExtension::class.java)

    /**
     * Configure the in-build size-check via a DSL block.
     *
     * ```kotlin
     * bugsee {
     *     sizeAnalysis {
     *         enabled.set(true)
     *         sizeCheck {
     *             enabled.set(true)
     *             warningPercent.set(5.0)
     *             failPercent.set(10.0)
     *         }
     *     }
     * }
     * ```
     */
    fun sizeCheck(action: Action<BugseeSizeCheckExtension>) {
        action.execute(sizeCheck)
    }
}
