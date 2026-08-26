package com.bugsee.android.gradle.instrumentation

import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.instrumentation.app_startup_tracing.AppStartupTracingInstrumentation
import com.bugsee.android.gradle.instrumentation.compose_input.ComposeInputInstrumentation
import com.bugsee.android.gradle.instrumentation.http_engine.HttpEngineInstrumentation
import com.bugsee.android.gradle.instrumentation.log.LogInstrumentation
import com.bugsee.android.gradle.instrumentation.okhttp.OkHttpInstrumentation
import com.bugsee.android.gradle.instrumentation.main_thread_misuse.MainThreadMisuseInstrumentation
import com.bugsee.android.gradle.instrumentation.operation_dispatch.OperationDispatchInstrumentation
import com.bugsee.android.gradle.instrumentation.thread.ThreadInstrumentation
import org.gradle.api.Project
import org.gradle.api.logging.Logger

/**
 * Iterates all known instrumentations and applies those whose dependencies are present.
 *
 * [extras] lets the caller append instrumentations that need construction-time
 * context the registrar cannot supply on its own (e.g. a [TaskProvider] for
 * a per-variant manifest task). They participate in the same gate/dependency
 * filtering as the built-in entries.
 */
internal class InstrumentationRegistrar(
    private val project: Project,
    private val logger: Logger,
    private val debug: Boolean,
    private val configResolver: InstrumentationConfigResolver,
    private val excludes: Set<String>,
    extras: List<Instrumentation> = emptyList(),
    /**
     * `true` when the plugin will auto-add the core `com.bugsee:bugsee-android`
     * SDK (sdkAutoLoad enabled AND the consumer has not declared the core
     * itself). In that case the core SDK is a pending `withDependencies`
     * addition not yet visible to [DependencyDetector], so core-gated
     * instrumentations would otherwise be wrongly skipped. Forwarded to each
     * [Instrumentation.shouldApply] so the gate can account for it. Defaults
     * to `false`.
     */
    private val coreSdkAutoLoad: Boolean = false,
) {
    private val instrumentations: List<Instrumentation> = listOf(
        OkHttpInstrumentation(),
        HttpEngineInstrumentation(),
        LogInstrumentation(),
        ThreadInstrumentation(),
        MainThreadMisuseInstrumentation(),
        OperationDispatchInstrumentation(),
        ComposeInputInstrumentation(),
        AppStartupTracingInstrumentation(configResolver)
    ) + extras

    /**
     * Configuration names that feed [variant]'s compile and runtime
     * classpaths, taken from their `extendsFrom` hierarchies — which AGP builds from
     * the variant's own source sets, so this covers base, build-type, flavor
     * and full-variant configurations without hardcoding AGP's naming.
     *
     * Reading `hierarchy` walks `extendsFrom` only; it does NOT resolve the
     * configuration, so this stays safe at configuration time and keeps the
     * detector's "declared dependencies only" contract intact.
     *
     * Deliberately the UNION of the compile and runtime hierarchies rather than
     * runtime alone. Runtime alone would be the stricter reading — an injected
     * reference has to resolve at runtime, so a `compileOnly` SDK cannot satisfy
     * it — but that also silently disables instrumentation for a library module
     * that compiles against Bugsee and relies on the consuming app to supply it
     * at runtime, which works today. That is a separate behaviour change with
     * its own regression surface; this fix addresses only the VARIANT
     * dimension, so `debugCompileOnly` still enables debug and now correctly
     * leaves release alone.
     *
     * Returns `null` if AGP does not hand us a usable configuration, which
     * falls back to the legacy all-configurations scan rather than silently
     * disabling every instrumentation.
     */
    private fun variantScopeOf(variant: Variant): Set<String>? = try {
        val names = HashSet<String>()
        variant.compileConfiguration.hierarchy.mapTo(names) { it.name }
        variant.runtimeConfiguration.hierarchy.mapTo(names) { it.name }
        // An empty scope would match NOTHING and silently disable every
        // instrumentation for this variant. If AGP ever hands back an empty
        // hierarchy we do not know the variant's configurations, which is the
        // same state as the catch below — degrade to the legacy unscoped scan
        // and say so, rather than failing silent.
        if (names.isEmpty()) {
            logger.warn("Bugsee: variant ${variant.name} reported no configurations; falling back to an unscoped dependency scan")
            null
        } else {
            names
        }
    } catch (t: Throwable) {
        logger.warn("Bugsee: could not resolve variant configuration scope for ${variant.name}; falling back to an unscoped dependency scan", t)
        null
    }

    fun applyAll(variant: Variant) {
        val scope = variantScopeOf(variant)
        for (instrumentation in instrumentations) {
            // Tier-driven instrumentations (e.g. AppStartupTracing) own
            // their disable logic via shouldApply; routing them through
            // the boolean gate would treat a typo'd Gradle property like
            // bugsee.instrumentation.appStartupTracing=garbage as an
            // "invalid boolean" and silently disable, masking the real
            // configuration mistake.
            if (!instrumentation.isTierDriven
                && !configResolver.isFeatureEnabled(instrumentation.key)) {
                if (debug) logger.warn("Bugsee: Skipping ${instrumentation.name} instrumentation (disabled by configuration)")
                continue
            }
            // Each instrumentation decides whether the core SDK counts as
            // present given the pending auto-add (see [coreSdkAutoLoad]).
            // Extension-gated entries (OkHttp, Compose, …) ignore the flag.
            val applies = instrumentation.shouldApply(project, coreSdkAutoLoad, scope)
            if (applies) {
                if (debug) logger.warn("Bugsee: Applying ${instrumentation.name} instrumentation to variant ${variant.name}")
                instrumentation.apply(variant, excludes)
            } else {
                if (debug) logger.warn("Bugsee: Skipping ${instrumentation.name} instrumentation (dependency not found)")
            }
        }
    }
}
