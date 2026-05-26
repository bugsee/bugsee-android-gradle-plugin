package com.bugsee.android.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * DSL block for **dependencies collection** — the default-on sub-feature
 * of `buildInfo` that walks the variant's resolved dependency graph at
 * build time, computes a scalar summary (counts + by-type breakdown),
 * and ships both the summary (inline in the build-info POST body) and
 * the full per-entry list (gzipped, via a second presigned PUT) to the
 * Bugsee backend.
 *
 * Always lives under `buildInfo` — there is no standalone deps upload
 * path. When `buildInfo.enabled = false`, deps collection is also
 * skipped regardless of this block's `enabled` value.
 *
 * ```kotlin
 * bugsee {
 *     buildInfo {
 *         dependencies {
 *             // enabled.set(true)                  // default — disable to opt out
 *             // scope.set("runtime")               // default; alt: "runtime_direct_only" | "compile_runtime"
 *             // includeSelectedReason.set(false)   // default — verbose, off
 *             // maxCount.set(5000)                 // default — safety cap
 *         }
 *     }
 * }
 * ```
 *
 * `enabled` defaults to true. Opt-out cases:
 *   - Privacy-sensitive shops that don't want dependency lists on
 *     external servers (e.g. internal-Nexus deps named after products).
 *   - Pathological dependency graphs whose resolution measurably
 *     slows the build (rare — collection runs lazy at task execution).
 *
 * `scope` defaults to `"runtime"` — every entry in the variant's
 * runtime classpath, direct + transitive. Each entry carries a
 * `direct` flag so the viewer can collapse transitives without losing
 * the full graph. Alternatives:
 *   - `"runtime_direct_only"` — only entries declared in the
 *     variant's build script (no transitives).
 *   - `"compile_runtime"` — union of compile and runtime classpaths;
 *     includes `compileOnly` deps that don't ship but affect builds.
 *
 * `includeSelectedReason` defaults to false. Gradle's
 * `ComponentSelectionReason` explains why a particular version of a
 * dependency won conflict resolution (forced, constraint, conflict
 * resolution …). Useful for diagnosing version skew but verbose
 * enough that the default keeps the blob compact.
 *
 * `maxCount` caps the per-entry list at 5000 entries by default. The
 * summary's `truncated` flag is set when the cap kicks in, so the
 * viewer can warn the user that the displayed list is partial. The
 * appserver also enforces a defensive cap on the server side
 * regardless of this value.
 *
 * Each property is also settable via
 * `plugin.buildInfo.dependencies.<name>` in `bugsee.properties`.
 */
abstract class BugseeDependenciesCollectionExtension @Inject constructor(objects: ObjectFactory) {

    /**
     * Master gate for dependencies collection. When `true` (default),
     * every variant that runs `buildInfo` registration also resolves
     * its runtime graph, sends the scalar summary inline, and uploads
     * the full list via a second presigned URL.
     *
     * Default: `true`
     */
    val enabled: Property<Boolean> =
        objects.property(Boolean::class.javaObjectType).convention(true)

    /**
     * Which classpaths to walk. One of:
     *   - `"runtime"` (default) — the variant's resolved runtime
     *     classpath; every entry (direct + transitive). The list
     *     entries' `direct` flag distinguishes them.
     *   - `"runtime_direct_only"` — first-level dependencies of the
     *     runtime classpath only. Smaller, less noisy; loses
     *     transitives that drive most real risk.
     *   - `"compile_runtime"` — union of the compile and runtime
     *     classpaths. Includes `compileOnly` deps that don't ship
     *     but affect what compiles. Biggest list.
     *
     * Default: `"runtime"`
     */
    val scope: Property<String> =
        objects.property(String::class.java).convention("runtime")

    /**
     * Whether to include Gradle's `ComponentSelectionReason` for each
     * entry — a short string explaining why this version won conflict
     * resolution (e.g. `forced`, `selected by rule`, `constraint`).
     *
     * Off by default because the reasons are verbose enough to roughly
     * double the per-entry payload while only being useful in the
     * narrow case of diagnosing dependency-version skew.
     *
     * Default: `false`
     */
    val includeSelectedReason: Property<Boolean> =
        objects.property(Boolean::class.javaObjectType).convention(false)

    /**
     * Hard cap on the per-entry list length. Above this, the
     * collector truncates and sets the summary's `truncated` flag so
     * the viewer can warn. Picked well above any plausible legitimate
     * project (5000 entries serialises to ~500 KB gzipped JSON,
     * which round-trips comfortably).
     *
     * The appserver also enforces a defensive cap on its own — this
     * one bounds the producer-side memory + payload size before the
     * blob is uploaded.
     *
     * Default: `5000`
     */
    val maxCount: Property<Int> =
        objects.property(Int::class.javaObjectType).convention(5_000)
}
