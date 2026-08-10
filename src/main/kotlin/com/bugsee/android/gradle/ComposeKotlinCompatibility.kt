package com.bugsee.android.gradle

/**
 * Which Kotlin compilers the bundled Compose compiler plugin may be loaded into.
 *
 * Kotlin compiler-plugin APIs are not stable across minor releases, and this artifact is compiled
 * against exactly one of them. Loading it into a compiler whose extension API has moved does not
 * degrade — it aborts the consumer's compilation, a failure an app author can neither diagnose nor
 * work around.
 *
 * Every bound below was established by running the real compiler of each line against a Compose
 * consumer with this artifact loaded (see `compose-compiler-plugin/src/test`), not by reading
 * release notes:
 *
 * | Kotlin  | Result                                                                       |
 * |---------|------------------------------------------------------------------------------|
 * | 1.9.22  | compiles                                                                      |
 * | 2.1.x   | compiles — the line this artifact is built against                            |
 * | 2.2.x   | `NoSuchMethodError: irCall(IrBuilderWithScope, IrSimpleFunctionSymbol)`       |
 * | 2.3.x   | same, wrapped in `IrGenerationExtensionException`                             |
 * | 2.4.x   | `ClassCastException: IrGenerationExtension$Companion → ProjectExtensionDescriptor` |
 *
 * 2.2 widened the `irCall`/`irString` builder receiver from `IrBuilderWithScope` to `IrBuilder`;
 * a compiled call site binds the exact descriptor, so the widening alone is a binary break. 2.4
 * additionally moved extension registration onto `ExtensionPointDescriptor` and removed the
 * `valueParameters` / `putValueArgument` / `extensionReceiver` IR API.
 *
 * Deliberately free of Gradle and Kotlin-plugin types so it stays unit-testable: the Kotlin
 * Gradle plugin API is `compileOnly` here and is absent at test runtime.
 */
internal object ComposeKotlinCompatibility {

    const val SUPPORTED_MAJOR = 2

    /**
     * Highest Kotlin MINOR line the Compose compiler plugin is verified against.
     *
     * Raise this only alongside a compiler-plugin build actually tested on that line — and "tested"
     * means a real compile that reaches the transformer. This constant said `3` through 4.0.3 on
     * the strength of a check that never exercised the IR pass; 2.2 and 2.3 in fact abort the
     * consumer's build. Kotlin moves these APIs with no deprecation cycle, so an untested line is
     * an unsupported line.
     */
    const val MAX_SUPPORTED_MINOR = 1

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
