package com.bugsee.android.gradle.instrumentation

import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input

/**
 * Shared instrumentation parameters carrying the target adapter class name.
 *
 * SDK / extension presence is verified ONCE at configuration time in each
 * `Instrumentation.shouldApply` (via `DependencyDetector.hasBugseeDependency`),
 * NOT per-class via [com.android.build.api.instrumentation.ClassContext.loadClassData].
 * That per-class probe is unreliable across AGP's artifact-transform isolation
 * boundaries: classes from third-party JARs are transformed on a classpath that
 * cannot see the consumer's `:library` dependency, so the probe spuriously returns
 * `null` and silently skips valid instrumentation targets living in those JARs
 * (e.g. OkHttp/Cronet/Log call sites inside third-party SDKs, or androidx.compose's
 * `AndroidComposeView`). SDK version skew instead surfaces at runtime as a
 * `NoClassDefFoundError` pointing at the adapter FQN, which is sufficient.
 *
 * [targetClass] is consulted by `AppStartupTracingClassVisitorFactory` as the
 * dispatcher FQN to inject; the other factories set it for consistency but hardcode
 * their adapter FQN in the injected bytecode and no longer read it.
 */
internal interface BugseeInstrumentationParameters : InstrumentationParameters {

    /** Fully-qualified (dot-separated) class name of the adapter the injected bytecode targets. */
    @get:Input
    val targetClass: Property<String>

    /**
     * Class-name patterns the user opted OUT of instrumentation
     * (`bugsee { instrumentation { excludes.add(...) } }`). Consulted in
     * each factory's `isInstrumentable` via
     * [com.bugsee.android.gradle.instrumentation.util.InstrumentationExcludes].
     */
    @get:Input
    val excludes: SetProperty<String>
}
