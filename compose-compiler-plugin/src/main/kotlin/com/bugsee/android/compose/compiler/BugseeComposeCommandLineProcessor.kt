package com.bugsee.android.compose.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class BugseeComposeCommandLineProcessor : CommandLineProcessor {

    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption(
            optionName = OPTION_ENABLED,
            valueDescription = "<true|false>",
            description = "Enable Bugsee Compose tag injection",
            required = false
        )
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration
    ) {
        when (option.optionName) {
            OPTION_ENABLED -> configuration.put(
                BugseeComposeConfigKeys.ENABLED,
                value.toBooleanStrictOrNull() ?: true
            )
        }
    }

    companion object {
        const val PLUGIN_ID = "com.bugsee.compose.compiler"
        const val OPTION_ENABLED = "enabled"
    }
}
