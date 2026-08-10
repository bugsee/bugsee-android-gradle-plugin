package com.bugsee.android.gradle

/**
 * Which Kotlin compilers the bundled Compose compiler plugin may be loaded into.
 *
 * Kotlin compiler-plugin APIs are not stable across minor releases, and this artifact is compiled
 * against exactly one of them. Loading it into a compiler whose extension API has moved does not
 * degrade — it aborts the consumer's compilation. On Kotlin 2.4 the registrar dies with
 * `ClassCastException: IrGenerationExtension$Companion cannot be cast to
 * ProjectExtensionDescriptor`, which an app author can neither diagnose nor work around.
 *
 * Deliberately free of Gradle and Kotlin-plugin types so it stays unit-testable: the Kotlin
 * Gradle plugin API is `compileOnly` here and is absent at test runtime.
 */
internal object ComposeKotlinCompatibility {

    const val SUPPORTED_MAJOR = 2

    /**
     * Highest Kotlin MINOR line the Compose compiler plugin is verified against.
     *
     * Raise this only alongside a compiler-plugin build actually tested on that line. Kotlin can
     * move the extension-registration API with no deprecation cycle — which is what 2.4 did.
     */
    const val MAX_SUPPORTED_MINOR = 3

    /**
     * Whether the Compose compiler plugin can safely be loaded into [version]'s compiler.
     *
     * Strict on an undeterminable version. The guarded failure is an unrecoverable abort of the
     * consumer's build, so "unknown" must not be assumed safe — the opposite of the SDK symbol
     * probe, where an unknown answer costs only a capture feature and unreadable cases (version
     * ranges, composite builds) are common. Here the version comes from the applied Kotlin
     * plugin, so unknown is rare.
     */
    fun isSupported(version: String?): Boolean {
        if (version.isNullOrBlank()) return false
        val parts = version.split('.', '-')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return when {
            major < SUPPORTED_MAJOR -> true
            major > SUPPORTED_MAJOR -> false
            else -> minor <= MAX_SUPPORTED_MINOR
        }
    }
}
