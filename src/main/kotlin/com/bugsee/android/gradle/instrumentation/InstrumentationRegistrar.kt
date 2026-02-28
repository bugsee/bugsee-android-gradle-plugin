package com.bugsee.android.gradle.instrumentation

import com.android.build.api.variant.Variant
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
 */
internal class InstrumentationRegistrar(
    private val project: Project,
    private val logger: Logger,
    private val debug: Boolean,
    private val configResolver: InstrumentationConfigResolver
) {
    private val instrumentations: List<Instrumentation> = listOf(
        OkHttpInstrumentation(),
        HttpEngineInstrumentation(),
        LogInstrumentation(),
        ThreadInstrumentation(),
        MainThreadMisuseInstrumentation(),
        OperationDispatchInstrumentation(),
        ComposeInputInstrumentation()
    )

    fun applyAll(variant: Variant) {
        for (instrumentation in instrumentations) {
            if (!configResolver.isFeatureEnabled(instrumentation.key)) {
                if (debug) logger.warn("Bugsee: Skipping ${instrumentation.name} instrumentation (disabled by configuration)")
                continue
            }
            if (instrumentation.shouldApply(project)) {
                if (debug) logger.warn("Bugsee: Applying ${instrumentation.name} instrumentation to variant ${variant.name}")
                instrumentation.apply(variant)
            } else {
                if (debug) logger.warn("Bugsee: Skipping ${instrumentation.name} instrumentation (dependency not found)")
            }
        }
    }
}
