@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.bugsee.android.compose.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Legacy-service registration of the Compose IR extension, for Kotlin **1.x** compilers only (the
 * `k21` artifact; see [BugseeComposeCompilerGeneration] and issue #138).
 *
 * The androidx Compose compiler releases that register through `ComponentRegistrar` (verified: 1.5.15)
 * are run by the Kotlin 1.9 compiler before any `CompilerPluginRegistrar`. Registering Bugsee only through
 * the latter therefore put it behind Compose's default-argument lowering, which has already turned an
 * omitted `modifier` into a `DEFAULT_VALUE` composite that the transform must skip. Registering here
 * makes the position follow the plugin classpath order instead, and the Gradle plugin puts Bugsee
 * ahead of Compose.
 *
 * Binds to the embeddable compiler's shaded `MockProject`, as the compiler Gradle runs always is.
 *
 * On Kotlin 2.0+ this class is still discovered (the service file is in the shared jar) but does
 * nothing (under the embeddable compiler; a non-embeddable one, whose `MockProject` is not shaded,
 * fails with `AbstractMethodError` on 1.9 to 2.1): [BugseeComposeCompilerPluginRegistrar] owns registration there.
 */
@OptIn(ExperimentalCompilerApi::class)
class BugseeComposeLegacyComponentRegistrar : ComponentRegistrar {

    override val supportsK2: Boolean = true

    override fun registerProjectComponents(project: MockProject, configuration: CompilerConfiguration) {
        if (!BugseeComposeCompilerGeneration.runningCompilerIsLegacy()) {
            return
        }
        val tagEnabled = configuration.get(BugseeComposeConfigKeys.TAG_INJECTION_ENABLED, true)
        val secureEnabled = configuration.get(BugseeComposeConfigKeys.SECURE_INJECTION_ENABLED, true)
        if (!tagEnabled && !secureEnabled) {
            return
        }
        IrGenerationExtension.registerExtension(
            project,
            BugseeComposeIrExtension(
                tagInjectionEnabled = tagEnabled,
                secureInjectionEnabled = secureEnabled,
                injectOnlyOverBareModifierDefault = true,
            ),
        )
    }
}
