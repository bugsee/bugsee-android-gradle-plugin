package com.bugsee.android.compose.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Registrar for the simulator built against Kotlin **2.2** (`k22` matrix rows).
 *
 * Mirrors the plugin's own `registrarPlain` variant: 2.3 added
 * `abstract val pluginId`, which cannot be spelled with `override` when
 * compiling against 2.2, and the plain `val` still satisfies the abstract
 * parent at runtime via JVM method resolution.
 */
@OptIn(ExperimentalCompilerApi::class)
class ComposeDefaultLoweringSimulatorRegistrar : CompilerPluginRegistrar() {

    @Suppress("unused") val pluginId: String = SIMULATOR_PLUGIN_ID

    override val supportsK2: Boolean get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(ComposeDefaultLoweringSimulatorExtension())
    }
}

internal const val SIMULATOR_PLUGIN_ID = "com.bugsee.compose.lowering.simulator"
