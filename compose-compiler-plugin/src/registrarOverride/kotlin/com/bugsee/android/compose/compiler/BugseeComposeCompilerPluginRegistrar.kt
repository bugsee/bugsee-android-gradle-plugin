package com.bugsee.android.compose.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Registrar for the variant built against Kotlin **2.4+** (`k24`).
 *
 * Identical to the `registrarPlain` variant except for the `pluginId` modifier: 2.3 added
 * `abstract val pluginId` to [CompilerPluginRegistrar], so from 2.3 onwards the compiler rejects a
 * plain `val` with *"'pluginId' hides member of supertype and needs an 'override' modifier"*. The
 * modifier is therefore not expressible in one shared source file, which is the only reason this
 * class is duplicated rather than shared.
 */
@OptIn(ExperimentalCompilerApi::class)
class BugseeComposeCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = BugseeComposeCommandLineProcessor.PLUGIN_ID

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
