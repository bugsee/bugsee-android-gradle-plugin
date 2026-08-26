package com.bugsee.android.compose.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Registrar for the simulator built against Kotlin **2.4** (`k24` matrix rows).
 *
 * Mirrors the plugin's own `registrarOverride` variant: from 2.3 onwards
 * `pluginId` is an abstract member of [CompilerPluginRegistrar] and a plain
 * `val` is rejected at compile time.
 */
@OptIn(ExperimentalCompilerApi::class)
class ComposeDefaultLoweringSimulatorRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = SIMULATOR_PLUGIN_ID

    override val supportsK2: Boolean get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(ComposeDefaultLoweringSimulatorExtension())
    }
}

internal const val SIMULATOR_PLUGIN_ID = "com.bugsee.compose.lowering.simulator"
