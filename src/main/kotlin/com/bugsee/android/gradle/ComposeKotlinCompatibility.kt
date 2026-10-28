package com.bugsee.android.gradle

/**
 * Maps a consumer's Kotlin version to the Compose compiler-plugin artifact built for it.
 *
 * A Kotlin compiler plugin binds the EXACT descriptors of the compiler API it was compiled against,
 * so one artifact cannot serve every Kotlin line — and a mismatch does not degrade, it aborts the
 * consumer's compilation with an error they can neither diagnose nor work around. We therefore ship
 * one artifact per line-group and pick between them here.
 *
 * Every boundary below is pinned by `ComposeVariantMatrixTest`, which runs each variant through that
 * line's real compiler and asserts the injected call reaches the emitted bytecode:
 *
 * | Kotlin      | Artifact                              | Why the boundary is here                    |
 * |-------------|---------------------------------------|---------------------------------------------|
 * | ≤ 2.1       | `bugsee-compose-compiler-plugin`      | legacy IR API                               |
 * | 2.2 – 2.3   | `bugsee-compose-compiler-plugin-k22`  | 2.2 widened the `irCall`/`irString` receiver to `IrBuilder` |
 * | 2.4         | `bugsee-compose-compiler-plugin-k24`  | 2.4 moved registration to `ExtensionPointDescriptor` and removed `valueParameters` |
 * | 2.5+, 3.x   | none — instrumentation stands down    | unverified; Kotlin moves these APIs with no deprecation cycle |
 *
 * ### Kotlin 1.9: registration order decides omitted-`modifier` tagging
 *
 * The androidx Compose compiler 1.5.x releases that register through the legacy `ComponentRegistrar`
 * service (verified: 1.5.15; 1.5.0 does not) are run by the 1.9 compiler ahead of every
 * `CompilerPluginRegistrar`. The k21 artifact therefore registers
 * through `ComponentRegistrar` on a Kotlin 1.9 compiler (and through `CompilerPluginRegistrar` only on
 * 2.0+, exactly one of the two per compiler), so its position follows the plugin classpath order.
 * Ahead of Compose it sees the call sites as written and tags a composable that omits `modifier`;
 * behind it, Compose's default-argument lowering has already turned every OMITTED `modifier` into a
 * `DEFAULT_VALUE` composite, which we skip on purpose because the callee's `$default` fixup would
 * overwrite anything injected there (issues #119, #138). On this path an omitted `modifier` is also only
 * rewritten when the callee's own default is the bare `Modifier`, since the injected chain replaces it
 * (with a modern-registering Compose compiler such as 1.5.0 that means same-module callees only; library
 * callees, whose default is not visible, used to be tagged there and are now skipped).
 * The Kotlin Gradle plugin lists our artifact
 * ahead of the Compose compiler AGP adds (`ComposePluginOrderTest`), so the first case is the normal one.
 * Verified through 1.9.25 with Compose compiler 1.5.15 by `Kotlin19ComposePipelineTest`, which executes
 * the compiled call paths under the JVM verifier in both orders.
 *
 * Strict on anything unrecognised. The guarded failure is an unrecoverable abort of the consumer's
 * build, so an unverified or undeterminable version must not be assumed safe — the opposite of the
 * SDK symbol probe, where an unknown answer costs only a capture feature.
 *
 * Deliberately free of Gradle and Kotlin-plugin types so it stays unit-testable: the Kotlin Gradle
 * plugin API is `compileOnly` here and is absent at test runtime.
 */
internal object ComposeKotlinCompatibility {

    /** Base coordinates; the k21 variant keeps the unsuffixed artifactId it has always had. */
    const val ARTIFACT_BASE = "bugsee-compose-compiler-plugin"

    /** Highest Kotlin MINOR line any shipped variant is verified against. */
    const val MAX_SUPPORTED_MINOR = 4

    const val SUPPORTED_MAJOR = 2

    /**
     * The artifactId to load into [version]'s compiler, or `null` when no shipped variant covers it
     * and Compose instrumentation must stand down.
     */
    fun artifactIdFor(version: String?): String? {
        val parsed = parse(version) ?: return null
        val (major, minor) = parsed
        val suffix = when {
            // 1.9 and older predate the IR changes entirely; verified on 1.9.22 and 1.9.25 (see the KDoc:
            // registration order decides omitted-modifier tagging with the real Compose 1.5 compiler).
            major < SUPPORTED_MAJOR -> ""
            major > SUPPORTED_MAJOR -> return null
            minor <= 1 -> ""
            minor <= 3 -> "-k22"
            minor <= MAX_SUPPORTED_MINOR -> "-k24"
            else -> return null
        }
        return ARTIFACT_BASE + suffix
    }

    /** Whether any shipped variant covers [version]. */
    fun isSupported(version: String?): Boolean = artifactIdFor(version) != null

    /** `major` to `minor`, or null when [version] cannot be read. */
    private fun parse(version: String?): Pair<Int, Int>? {
        if (version.isNullOrBlank()) return null
        val parts = version.split('.', '-')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return major to minor
    }
}
