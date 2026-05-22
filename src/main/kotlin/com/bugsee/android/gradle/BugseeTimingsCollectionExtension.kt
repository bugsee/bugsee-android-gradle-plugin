package com.bugsee.android.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * DSL block for **build-timings collection** — the default-on sub-feature
 * of `buildInfo` that observes Gradle task completion events, classifies
 * each task into one of five categories (`managed_code` / `native` /
 * `resources` / `packaging` / `other`), and emits both an inline summary
 * (under `build_metadata.timings` in the POST body) and a gzipped
 * per-task detail blob (PUT to a presigned URL when the server returns
 * one in response to `request_timings_upload: true`).
 *
 * Always lives under `buildInfo` — there is no standalone timings path.
 * When `buildInfo.enabled = false`, timings collection is also skipped
 * regardless of this block's `enabled` value.
 *
 * ```kotlin
 * bugsee {
 *     buildInfo {
 *         timings {
 *             // enabled.set(true)   // default — disable to opt out
 *         }
 *     }
 * }
 * ```
 *
 * `enabled` defaults to true. Opt-out cases:
 *   - Privacy-sensitive shops that don't want per-task task names on
 *     external servers (e.g. internal-target names that reveal product
 *     codenames).
 *   - Builds where the listener overhead is measurable (sub-millisecond
 *     per task, but still — pathologically large multi-module builds).
 *
 * Mirrors [BugseeDependenciesCollectionExtension] in shape and intent so
 * the two sub-features can be reasoned about together.
 */
abstract class BugseeTimingsCollectionExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Master gate for timings collection. When `true` (default),
     * every variant that runs `buildInfo` registration also captures
     * per-task timings, sends the scalar summary inline under
     * `build_metadata.timings`, and uploads the full per-task list
     * via a second presigned PUT URL the server returns when
     * `request_timings_upload: true` is set on the metadata POST.
     *
     * Default: `true`
     */
    val enabled: Property<Boolean> =
        objects.property(Boolean::class.javaObjectType).convention(true)
}
