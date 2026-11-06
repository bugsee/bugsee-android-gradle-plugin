package com.bugsee.android.compose.compiler

import org.jetbrains.kotlin.config.KotlinCompilerVersion

/**
 * Which registration path owns the IR extension on the running compiler.
 *
 * The `k21` artifact is loaded by Kotlin 1.9 as well as 2.0/2.1 and is registered twice, through the
 * modern `CompilerPluginRegistrar` and through the legacy `ComponentRegistrar` (issue #138). The
 * compiler discovers both entry points, so each one asks here and exactly one of them registers; if
 * both did, every call site would be tagged twice.
 *
 *  - Kotlin **1.9**: the legacy registrar owns it. The androidx Compose compiler 1.5 releases that
 *    register through `ComponentRegistrar` (1.5.15) are run by the 1.9 compiler ahead of every
 *    `CompilerPluginRegistrar`, so only a legacy registration can be ordered relative to it (by
 *    classpath order, Bugsee first). Registered the modern way Bugsee always ran AFTER Compose's
 *    default-argument lowering and could not tag an omitted `modifier`.
 *  - Kotlin **2.0 and later**: the modern registrar owns it, exactly as before this change. Compose
 *    is bundled and registered the modern way there, so the modern position already follows
 *    classpath order and nothing about it moves.
 *  - Kotlin **1.8 and earlier**: untouched. Only 1.9 has been run through the legacy path, and
 *    `CompilerPluginRegistrar` does not exist there, so the artifact stays as inert as it was.
 */
internal object BugseeComposeCompilerGeneration {

    /** Whether [version] (a `major.minor...` compiler version string) is a Kotlin 1.9 compiler. */
    fun isLegacy(version: String?): Boolean {
        val parts = version?.split('.', '-') ?: return false
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return major == 1 && minor >= 9
    }

    /** [isLegacy] for the compiler this plugin is currently loaded into. */
    fun runningCompilerIsLegacy(): Boolean = isLegacy(KotlinCompilerVersion.getVersion())
}
