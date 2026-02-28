package com.bugsee.android.compose.compiler

import org.jetbrains.kotlin.config.CompilerConfigurationKey

object BugseeComposeConfigKeys {
    val ENABLED = CompilerConfigurationKey<Boolean>("bugsee.compose.enabled")
}
