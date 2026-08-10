package com.bugsee.android.gradle

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate that keeps the bundled Compose compiler plugin out of a Kotlin compiler whose
 * extension API it was not built against.
 *
 * Kotlin compiler-plugin APIs move without a deprecation cycle, and loading this artifact into a
 * compiler whose API has moved aborts the CONSUMER's compilation — a failure an app author can
 * neither diagnose nor route around.
 *
 * Each bound is pinned to an observed failure from running that line's real compiler over a Compose
 * consumer with the artifact loaded:
 *
 *  - 2.2 / 2.3 — `NoSuchMethodError: irCall(IrBuilderWithScope, IrSimpleFunctionSymbol)`; 2.2
 *    widened the builder receiver to `IrBuilder` and a compiled call site binds the exact
 *    descriptor.
 *  - 2.4 — `ClassCastException: IrGenerationExtension$Companion cannot be cast to
 *    ProjectExtensionDescriptor`, thrown during registration before any IR runs.
 *
 * 4.0.3 and earlier allowed 2.2 and 2.3 here on the strength of a check that never reached the IR
 * pass. Both abort the build, hence the bound below sits at 2.1.
 */
class ComposeKotlinVersionGateTest {

    private fun supported(v: String?) = ComposeKotlinCompatibility.isSupported(v)

    @Test
    fun `the line the artifact is compiled against is supported`() {
        assertTrue(supported("2.1.0"), "the line this artifact is compiled against")
        assertTrue(supported("2.1.21"))
    }

    @Test
    fun `lines that break the IR builder API are refused`() {
        assertFalse(
            supported("2.2.0"),
            "2.2 widened the irCall/irString receiver; the compiled call site NoSuchMethodErrors",
        )
        assertFalse(supported("2.2.21"))
        assertFalse(supported("2.3.0"))
        assertFalse(supported("2.3.21"))
    }

    @Test
    fun `the version that breaks the registrar is refused`() {
        assertFalse(
            supported("2.4.0"),
            "2.4 moved the extension-registration API; loading into it aborts the build",
        )
    }

    @Test
    fun `later lines are refused until verified`() {
        assertFalse(supported("2.5.0"))
        assertFalse(supported("3.0.0"))
    }

    /** Pre-release qualifiers must not defeat parsing — `2.4.0-RC` is still 2.4. */
    @Test
    fun `pre-release qualifiers are parsed to their line`() {
        assertFalse(supported("2.4.0-RC"))
        assertFalse(supported("2.4.20-Beta1"))
        assertFalse(supported("2.2.0-RC2"))
        assertTrue(supported("2.1.20-RC"))
    }

    /**
     * Older majors predate the API change entirely — 1.9.22 was run through the same real-compiler
     * harness and compiles the consumer successfully.
     */
    @Test
    fun `older majors are supported`() {
        assertTrue(supported("1.9.22"))
    }

    /**
     * Strict on unknown, deliberately. The guarded failure is an unrecoverable abort of the
     * consumer's compilation, so an undeterminable version must not be assumed safe — the
     * opposite of the SDK symbol probe, where unknown costs only a capture feature.
     */
    @Test
    fun `an undeterminable version is refused rather than assumed safe`() {
        assertFalse(supported(null))
        assertFalse(supported(""))
        assertFalse(supported("not-a-version"))
        assertFalse(supported("2"))
    }
}
