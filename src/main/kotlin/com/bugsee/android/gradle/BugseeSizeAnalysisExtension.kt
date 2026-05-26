package com.bugsee.android.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * DSL block for configuring the optional size-analysis upload of the
 * build artefact (AAB / APK). Size analysis is a sub-feature of
 * `buildInfo` — it lives under `buildInfo.sizeAnalysis`, requires
 * `bugsee.buildInfo.enabled = true`, and piggybacks on the same
 * per-variant upload task. When enabled, the task additionally
 * requests a presigned PUT URL from the appserver and ships the
 * artefact zip for server-side tree analysis.
 *
 * ```kotlin
 * bugsee {
 *     buildInfo {
 *         sizeAnalysis {
 *             enabled.set(true)
 *             buildConfiguration.set("release")
 *         }
 *     }
 * }
 * ```
 *
 * The in-build size-check thresholds (warning / fail percent / bytes)
 * live under [BugseeBuildInfoExtension.sizeCheck], NOT here — the
 * check only needs the recorded `artifact_size` scalar (always sent
 * as part of build-info), not the full size-tree analysis.
 *
 * Each property is also settable via
 * `plugin.buildInfo.sizeAnalysis.<name>` in `bugsee.properties`.
 */
abstract class BugseeSizeAnalysisExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Enable APK/AAB upload for size analysis.
     *
     * When `true`, the plugin uploads the build artifact (APK or AAB) to the
     * Bugsee backend for size tracking and comparison across builds.
     *
     * Requires `bugsee.buildInfo.enabled = true` (the default). When
     * `buildInfo` is disabled and `sizeAnalysis` is enabled, the plugin
     * logs a warning and skips both — size analysis on its own would
     * have nothing to attach to.
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
     * Use the chunked upload protocol instead of a single PUT for the
     * size-analysis artefact bundle.
     *
     * Chunks are deduplicated across builds — CI runs that only change
     * a small fraction of the AAB / APK upload much faster on repeat
     * runs because unchanged chunks are short-circuited at the appserver.
     * The chunked path also auto-falls-back to the single-PUT path on
     * any failure, so enabling this flag never breaks an existing build.
     *
     * Disabled by default while the chunked endpoints roll out;
     * set to `true` to opt in.
     *
     * Only has effect when [enabled] is also `true` — the build-info-only
     * path (no artefact upload) has nothing to chunk.
     *
     * Default: `false`
     */
    val chunkedUpload: Property<Boolean> =
        objects.property(Boolean::class.javaObjectType).convention(false)
}
