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
     * Selected tier. Carried as a typed [StartupTier] enum — Kotlin enums
     * are [java.io.Serializable] by virtue of extending [java.lang.Enum],
     * so they cross AGP's configuration-cache boundary without issue.
     *
     * The DSL is also typed (`Property<StartupTier>` on the extension);
     * Gradle-property and manifest-meta-data sources stay as strings and
     * are parsed via [StartupTier.parse] at resolution time, then handed
     * to this property as an already-resolved enum value.
     */
    @get:Input
    val tier: Property<StartupTier>
}
