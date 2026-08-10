package com.bugsee.android.compose.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Registrar for the variants built against Kotlin **2.1 and 2.2** (`k21`, `k22`).
 *
 * Kept byte-identical in intent to [the `registrarOverride` variant]; the two differ only in the
 * `pluginId` modifier, which cannot be spelled the same way across the version boundary. See
 * `build.gradle.kts` for which variant consumes which.
 */
@OptIn(ExperimentalCompilerApi::class)
class BugseeComposeCompilerPluginRegistrar : CompilerPluginRegistrar() {

    // Kotlin 2.3.0 added `abstract val pluginId: String` to
    // CompilerPluginRegistrar. This variant compiles against 2.1/2.2 where the
    // parent has no such member, so `override` is not allowed. The generated
    // concrete `getPluginId()` method still satisfies the abstract parent at
    // runtime on Kotlin 2.3 via JVM method resolution — which is exactly how the
    // k22 artifact covers 2.3 as well as 2.2 — and is a harmless extra method on
    // older runtimes. Verified by running the 2.3 compiler against this artifact.
    @Suppress("unused") val pluginId: String = BugseeComposeCommandLineProcessor.PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        // Both subfeatures default to true when the option is not set —
        // matches the gradle plugin's "default to enabled when not
        // explicitly disabled" semantics.
        val tagEnabled = configuration.get(BugseeComposeConfigKeys.TAG_INJECTION_ENABLED, true)
        val secureEnabled =
                configuration.get(BugseeComposeConfigKeys.SECURE_INJECTION_ENABLED, true)

        if (!tagEnabled && !secureEnabled) {
            // Nothing to do — both passes disabled.
            return
        }

        IrGenerationExtension.registerExtension(
                BugseeComposeIrExtension(
                        tagInjectionEnabled = tagEnabled,
                        secureInjectionEnabled = secureEnabled
                )
        )
    }
}
