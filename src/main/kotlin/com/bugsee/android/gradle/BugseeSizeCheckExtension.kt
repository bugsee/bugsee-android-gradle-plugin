package com.bugsee.android.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * In-build size-check configuration. When enabled, the plugin fetches
 * the most recent prior build's recorded artifact size from the server
 * and compares it to the freshly built artifact. Crossing the warning
 * threshold emits a Gradle warning; crossing the fail threshold throws
 * a `GradleException` and fails the upload task.
 *
 * Each threshold is independently optional. A value of `0` (or unset)
 * is treated as "disabled" — set `failPercent.set(10.0)` to fail on
 * regressions > 10% while leaving the byte-cap inactive.
 *
 * Trigger rule: FAIL if `(failPercent > 0 AND deltaPct >= failPercent)`
 * OR `(failBytes > 0 AND deltaBytes >= failBytes)`; otherwise WARN
 * under the same shape on the warning thresholds. Fail wins. Negative
 * deltas (artifact shrunk) never trigger anything.
 *
 * Each option also reads from a `BUGSEE_SIZE_CHECK_*` environment
 * variable when the DSL property is unset, so CI runs that prefer not
 * to mutate the Gradle DSL can configure the check from the build
 * environment:
 *
 *   - `BUGSEE_SIZE_CHECK_ENABLED`        ↔ [enabled]
 *   - `BUGSEE_SIZE_CHECK_WARNING_PCT`    ↔ [warningPercent]
 *   - `BUGSEE_SIZE_CHECK_FAIL_PCT`       ↔ [failPercent]
 *   - `BUGSEE_SIZE_CHECK_WARNING_BYTES`  ↔ [warningBytes]
 *   - `BUGSEE_SIZE_CHECK_FAIL_BYTES`     ↔ [failBytes]
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
 *
 * Each property is also settable via
 * `plugin.buildInfo.sizeCheck.<name>` in `bugsee.properties`.
 */
abstract class BugseeSizeCheckExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Master gate. When `false`, the check is skipped entirely and
     * thresholds have no effect. Default: `false`.
     *
     * Note: deliberately NO `.convention(false)` here, in contrast to
     * the parent `BugseeSizeAnalysisExtension.enabled`. A convention
     * value makes the property report `isPresent=true`, which would
     * short-circuit the env-var fallback chain in
     * `BugseePlugin.wireSizeCheckInputs` (`extension.enabled.orElse(envBool(...))`
     * would never reach the env). The default-false is supplied at
     * the wiring site instead via a trailing `.orElse(false)` so the
     * DSL ↔ env ↔ default precedence stays correct.
     */
    val enabled: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /**
     * Emit a Gradle warning when the relative size delta is at or
     * above this percentage (e.g. `5.0` for `+5%`). `0` / unset
     * disables this gate. Independent of [warningBytes].
     */
    val warningPercent: Property<Double> = objects.property(Double::class.javaObjectType)

    /**
     * Throw `GradleException` (failing the upload task) when the
     * relative size delta is at or above this percentage. `0` / unset
     * disables this gate. Independent of [failBytes].
     */
    val failPercent: Property<Double> = objects.property(Double::class.javaObjectType)

    /**
     * Emit a Gradle warning when the absolute byte delta is at or
     * above this value. `0` / unset disables this gate. Independent
     * of [warningPercent].
     */
    val warningBytes: Property<Long> = objects.property(Long::class.javaObjectType)

    /**
     * Throw `GradleException` (failing the upload task) when the
     * absolute byte delta is at or above this value. `0` / unset
     * disables this gate. Independent of [failPercent].
     */
    val failBytes: Property<Long> = objects.property(Long::class.javaObjectType)
}
