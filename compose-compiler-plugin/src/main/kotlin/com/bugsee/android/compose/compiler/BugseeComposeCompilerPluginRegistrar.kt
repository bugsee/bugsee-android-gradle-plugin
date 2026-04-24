package com.bugsee.android.compose.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class BugseeComposeCompilerPluginRegistrar : CompilerPluginRegistrar() {

    // Kotlin 2.3.0 added `abstract val pluginId: String` to
    // CompilerPluginRegistrar. We compile against 2.1.0 where the parent has
    // no such member, so `override` is not allowed. The generated concrete
    // `getPluginId()` method still satisfies the abstract parent at runtime
    // on Kotlin 2.3+ via JVM method resolution, and is a harmless extra
    // method on older runtimes.
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
