package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
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
 * Gated on:
 *  - the `com.bugsee:bugsee-android` dependency being present
 *  - the resolved [StartupTier] being something other than [StartupTier.OFF]
 *  - the SDK shipping `BugseeAppStartupDispatcher` on the runtime classpath
 *    (checked per-class by the factory via [classContext.loadClassData];
 *    older SDKs surface a one-shot `System.err` warning and skip
 *    instrumentation rather than emit calls against a missing class)
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

    override fun shouldApply(project: Project): Boolean {
        if (configResolver.resolveStartupTier() == StartupTier.OFF) {
            return false
        }
        // SDK-version gating lives inside the factory's createClassVisitor
        // via ClassContext.loadClassData on the dispatcher FQN; when the
        // dispatcher is missing (older SDK), the factory short-circuits AND
        // writes a one-time "SDK too old" warning to System.err so the
        // misconfiguration surfaces in the build output rather than
        // silently producing no-op bytecode. Doing the probe at
        // shouldApply time would require resolving the runtime
        // configuration at configuration phase, which Gradle's lazy model
        // discourages; the factory-level probe is the right trade-off.
        return DependencyDetector.hasBugseeDependency(project, "bugsee-android")
    }

    override fun apply(variant: Variant) {
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
            params.tier.set(tier)
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
        )
    }
}
