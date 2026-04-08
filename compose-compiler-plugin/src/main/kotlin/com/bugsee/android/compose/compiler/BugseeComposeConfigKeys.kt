package com.bugsee.android.compose.compiler

import org.jetbrains.kotlin.config.CompilerConfigurationKey

object BugseeComposeConfigKeys {
    /**
     * Compose tag injection — automatic `Modifier.bugseeTag(<name>)` for
     * every composable call inside a user `@Composable`. CLI option name
     * is `enabled` for backward compatibility with existing builds.
     */
    val TAG_INJECTION_ENABLED = CompilerConfigurationKey<Boolean>("bugsee.compose.enabled")

    /**
     * Compose secure-field auto-detection — injects
     * `Modifier.bugseeSecure()` into call sites of `TextField` family
     * composables whose `visualTransformation` is statically detectable
     * as `PasswordVisualTransformation`. CLI option name: `secure`.
     */
    val SECURE_INJECTION_ENABLED = CompilerConfigurationKey<Boolean>("bugsee.compose.secure")
}
