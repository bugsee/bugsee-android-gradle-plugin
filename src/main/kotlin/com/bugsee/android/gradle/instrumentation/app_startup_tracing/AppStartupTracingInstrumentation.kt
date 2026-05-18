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
 *
 * **Phase 3:** the registered ASM factory is a skeleton — no bytecode is
 * rewritten yet. Tier selection, DSL plumbing, and per-variant registration
 * all work end-to-end so future phases can drop in the real transform
 * without touching the wiring.
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
        // TODO(phase-5): probe the resolved runtime classpath for
        // BugseeAppStartupDispatcher.class and, if absent, emit a Gradle
        // warning ("SDK too old — startup instrumentation will be skipped")
        // and return false. For Phase 3 the per-class
        // ClassContext.loadClassData check inside the factory is the only
        // gate; an SDK without the dispatcher will simply produce no
        // bytecode (because isInstrumentable currently returns false
        // anyway).
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
            params.tier.set(tier.name)
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS
        )
    }
}
