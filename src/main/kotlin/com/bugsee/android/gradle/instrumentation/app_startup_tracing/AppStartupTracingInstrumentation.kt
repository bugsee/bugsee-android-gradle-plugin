package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.StartupTier
import com.bugsee.android.gradle.instrumentation.BugseeSdkVersion
import com.bugsee.android.gradle.instrumentation.SdkSymbolAvailability
import com.bugsee.android.gradle.instrumentation.DependencyDetector
import com.bugsee.android.gradle.instrumentation.Instrumentation
import com.bugsee.android.gradle.instrumentation.InstrumentationConfigResolver
import org.gradle.api.Project

/**
 * App-startup bytecode tracing.
 *
 * Wraps initialization-time method bodies (Application/ContentProvider/etc.)
 * with calls to `com.bugsee.library.adapters.BugseeAppStartupDispatcher` so
 * the SDK's APM subsystem can surface per-method (and, at higher tiers,
 * per-call / per-loop) latency for the cold-start path.
 *
 * Gated on (checked once at configuration time in [shouldApply]):
 *  - the resolved [StartupTier] being something other than [StartupTier.OFF]
 *  - the `com.bugsee:bugsee-android` dependency being present
 *  - the declared `bugsee-android` version being &ge; the version that
 *    first ships [com.bugsee.library.adapters.BugseeAppStartupDispatcher]
 *    (see `MIN_SDK_VERSION_WITH_DISPATCHER` in [shouldApply]). When the
 *    version is older, the plugin logs a warning and skips
 *    instrumentation entirely so the host app doesn't crash at launch
 *    with `NoClassDefFoundError` (the injected `INVOKESTATIC` runs
 *    inside `InitializationProvider.onCreate`, BEFORE
 *    `Application.onCreate` — there is no SDK code path to absorb
 *    the failure). Unparseable / dynamic version strings (`7.+`,
 *    project deps) are treated permissively — proceed without
 *    refusing — since Gradle's lazy model makes strict resolution at
 *    config time costly.
 *
 * **Implementation layers:**
 *  - **DSL plumbing + tier resolution + per-variant registration** —
 *    declared here in `shouldApply` / `apply`, with the resolved tier
 *    captured into the AGP transform parameter so the lambda stays
 *    configuration-cache compatible.
 *  - **Bytecode rewriting** — performed by
 *    [AppStartupTracingClassVisitor] + [MethodBodyWrapper] (MINIMAL),
 *    [TopLevelCallWrapper] (STANDARD), [LoopWrapper] (DETAILED), and
 *    the FULL-tier annotation peek visitor (FULL). Layer order is
 *    increasing-scope: per-call → per-loop → whole-method, with the
 *    method-body catch-any pinned at the end of `tryCatchBlocks` via
 *    [MethodBodyWrapper]'s layer-ordering invariant guard.
 */
internal class AppStartupTracingInstrumentation(
    private val configResolver: InstrumentationConfigResolver
) : Instrumentation {

    override val name: String = "AppStartupTracing"
    override val key: String = "appStartupTracing"
    override val isTierDriven: Boolean = true

    /**
     * Captured in [shouldApply] for use by [apply], which AGP's Variant API does not hand a
     * Project. The registrar calls the two back to back for each variant, shouldApply first.
     */
    private var hostProject: Project? = null

    override fun shouldApply(project: Project, coreSdkAutoLoad: Boolean): Boolean {
        hostProject = project
        if (configResolver.resolveStartupTier() == StartupTier.OFF) {
            return false
        }
        if (!DependencyDetector.hasBugseeDependency(project, "bugsee-android")) {
            // No declared core SDK. Apply only when the plugin will auto-add it.
            // The auto-add floor (MIN_SDK_VERSION) is always >= the version that
            // first ships BugseeAppStartupDispatcher, so the version gate below
            // is unnecessary here — and there is no declared version to inspect.
            return coreSdkAutoLoad
        }

        // SDK-version gating: refuse to instrument when the runtime
        // `bugsee-android` is older than the version that first ships
        // `BugseeAppStartupDispatcher` (the class our injected
        // INVOKESTATIC calls reference). Without this check, pairing
        // this plugin with an old SDK produces `NoClassDefFoundError`
        // at app launch — `InitializationProvider.onCreate` runs
        // before `Application.onCreate`, so the host app crashes
        // before the SDK can self-report. Best-effort: when the
        // declared version is a Gradle range / null / unparseable
        // (e.g. project deps, `7.+`, version catalogs that haven't
        // resolved at config time), we proceed without warning rather
        // than refuse — Gradle's lazy model makes a strict probe
        // expensive and the most common case is a pinned version
        // string we CAN parse.
        val declaredVersion = DependencyDetector.getBugseeDependencyVersion(project, "bugsee-android")
        val parsed = BugseeSdkVersion.parse(declaredVersion)
        if (parsed != null && parsed < MIN_SDK_VERSION_WITH_DISPATCHER) {
            project.logger.warn(
                "Bugsee gradle plugin: app-startup tracing requires `com.bugsee:bugsee-android` " +
                    "$MIN_SDK_VERSION_WITH_DISPATCHER or newer (found $declaredVersion). " +
                    "Skipping instrumentation to avoid NoClassDefFoundError on " +
                    "BugseeAppStartupDispatcher at app launch. Upgrade the SDK or " +
                    "downgrade the plugin to a compatible release."
            )
            return false
        }
        return true
    }

    override fun apply(variant: Variant, excludes: Set<String>) {
        // resolveStartupTier() is called at configuration time, here in
        // apply(variant); the resulting String is captured into the
        // parameter lambda below. Project state (held by configResolver)
        // is therefore not referenced from inside the lambda — keeps the
        // resulting transform configuration-cache compatible.
        val tier = configResolver.resolveStartupTier()
        variant.instrumentation.transformClassesWith(
            AppStartupTracingClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) { params ->
            params.targetClass.set("com.bugsee.library.adapters.BugseeAppStartupDispatcher")
            hostProject?.let { p ->
                params.symbolAvailable.set(
                    SdkSymbolAvailability.of(p, "com.bugsee.library.adapters.BugseeAppStartupDispatcher", "app-startup tracing", LOGGER_)
                )
            }
            params.tier.set(tier)
            params.excludes.set(excludes)
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
        )
    }

    private companion object {
        val LOGGER_ = org.gradle.api.logging.Logging.getLogger(AppStartupTracingInstrumentation::class.java)

        /**
         * First `com.bugsee:bugsee-android` release that ships
         * [com.bugsee.library.adapters.BugseeAppStartupDispatcher] — the
         * runtime class our injected `INVOKESTATIC` calls target. Pairing
         * this plugin with an older SDK would crash the host app on
         * cold start with `NoClassDefFoundError`.
         *
         * <p><b>Per-instrumentation, not shared.</b> This constant gates
         * APP-STARTUP TRACING specifically. If a future Bugsee
         * instrumentation introduces a NEW injected SDK symbol (e.g.
         * a different dispatcher class), add a SEPARATE min-version
         * constant in that instrumentation's class rather than bumping
         * this one — otherwise consumers running an SDK that has the
         * APP-STARTUP dispatcher but lacks the newer symbol will be
         * spuriously refused. The single-constant model assumes all
         * dispatcher methods on a given class ship in lockstep; the
         * per-instrumentation split is the right axis if that ever
         * stops being true.
         *
         * <p>Update this constant when the dispatcher's class FQN or
         * method signatures change in a backward-incompatible way.
         *
         * <p><b>Still required alongside the artifact probe.</b>
         * [com.bugsee.android.gradle.instrumentation.SdkSymbolAvailability]
         * now checks whether the dispatcher CLASS is present on the resolved
         * classpath, which covers cases a version string cannot (ranges,
         * platform-managed versions, project dependencies, and a class
         * stripped from the published artifact). It does NOT inspect method
         * signatures, so it cannot detect a dispatcher whose class is present
         * but whose methods changed shape — which is the other half of what
         * this constant guards. The two are complementary; neither subsumes
         * the other.
         */
        val MIN_SDK_VERSION_WITH_DISPATCHER = BugseeSdkVersion(
            major = 7,
            minor = 0,
            patch = 0,
            preLabel = "beta",
            preNumber = 11,
        )
    }
}
