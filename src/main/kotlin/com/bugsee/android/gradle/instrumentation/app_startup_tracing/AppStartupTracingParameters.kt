package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.bugsee.android.gradle.instrumentation.BugseeInstrumentationParameters
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

/**
 * Parameters passed from the build configuration to the runtime ASM factory
 * for app-startup tracing.
 *
 * Extends [BugseeInstrumentationParameters] so the inherited
 * [BugseeInstrumentationParameters.targetClass] can carry the
 * frozen FQN of `BugseeAppStartupDispatcher` — the factory verifies the
 * class exists on the classpath via
 * `ClassContext.loadClassData` before emitting any bytecode that references
 * it, mirroring the gating pattern used by every other Bugsee
 * instrumentation factory.
 */
internal interface AppStartupTracingParameters : BugseeInstrumentationParameters {

    /**
     * Selected tier as a `String` ([StartupTier.name]). The factory parses
     * it on first use; AGP's parameter framework does not support enum
     * properties directly across configuration-cache boundaries.
     */
    @get:Input
    val tier: Property<String>
}
