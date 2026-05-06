package com.bugsee.android.gradle

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * DSL block for the **build-info** upload — the default-on path that
 * registers a Bugsee build record on every release build, sending
 * version / build / package_id / VCS / build-machine / SDK / timing
 * metadata + the artefact's file size. The record exists from the
 * moment of the upload and lets the appserver enrich crashes with
 * commit-context lookups, render build history in the dashboard, and
 * serve as a baseline for the in-build size-check.
 *
 * Size-analysis (full artefact upload + server-side tree analysis)
 * is a separate, opt-in sub-feature configured under
 * `bugsee.sizeAnalysis` — when enabled it piggybacks on this task and
 * additionally ships the artefact bytes.
 *
 * ```kotlin
 * bugsee {
 *     buildInfo {
 *         // enabled.set(true)   // default — disable explicitly to opt out
 *         // allBuildTypes.set(false) // default — only release variants register
 *         sizeCheck {
 *             enabled.set(true)
 *             warningPercent.set(5.0)
 *             failPercent.set(10.0)
 *         }
 *     }
 *     sizeAnalysis {
 *         enabled.set(true)  // optional add-on
 *     }
 * }
 * ```
 */
abstract class BugseeBuildInfoExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Master gate for build-info registration. When `true` (default),
     * every matching variant runs the build-info upload task as part
     * of `assemble{Variant}` / `bundle{Variant}` finalization.
     *
     * Set to `false` to opt out entirely — useful for firewalled CI
     * environments where outbound HTTP is not permitted, or for
     * privacy-sensitive builds where the user does not want the
     * record on Bugsee servers.
     *
     * Note: disabling `buildInfo` also disables `sizeAnalysis`
     * implicitly (the size-analysis upload has nothing to attach to
     * without a build record). The plugin logs a warning if both are
     * configured incompatibly.
     *
     * Default: `true`
     */
    val enabled: Property<Boolean> = objects.property(Boolean::class.javaObjectType).convention(true)

    /**
     * Include every build type (Debug, custom, …) in build-info
     * registration. By default only the release variant runs — that's
     * the artefact users actually ship, and registering every debug
     * rebuild would flood the Bugsee dashboard with noise the user
     * cannot meaningfully act on.
     *
     * Set to `true` to register every variant. Useful when the user
     * runs unusual build types (e.g. `staging`, `internalRelease`)
     * that aren't `Debug` but also don't match a strict `release`
     * filter.
     *
     * Default: `false`
     */
    val allBuildTypes: Property<Boolean> =
        objects.property(Boolean::class.javaObjectType).convention(false)

    /**
     * In-build size-check configuration. See [BugseeSizeCheckExtension]
     * for thresholds and the env-var fallback contract.
     *
     * Lives under `buildInfo` rather than `sizeAnalysis` because the
     * check only needs the prior build's recorded `artifact_size`
     * scalar — sent as part of build-info, regardless of whether the
     * full size-analysis upload is enabled. Users who want the check
     * but don't care about the tree-analysis can enable it without
     * also enabling `sizeAnalysis`.
     */
    val sizeCheck: BugseeSizeCheckExtension =
        objects.newInstance(BugseeSizeCheckExtension::class.java)

    /**
     * Configure the in-build size-check via a DSL block.
     *
     * ```kotlin
     * bugsee {
     *     buildInfo {
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
