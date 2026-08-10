package com.bugsee.android.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
 * 4.0.3 and earlier allowed 2.2 and 2.3 on the strength of a check that never reached the IR pass,
 * and shipped a single artifact for every line. Both are fixed: each line-group now gets the
 * artifact built against it, and `ComposeVariantMatrixTest` proves each one injects on the lines
 * claimed here by running that line's real compiler.
 */
class ComposeKotlinVersionGateTest {

    private fun supported(v: String?) = ComposeKotlinCompatibility.isSupported(v)

    private fun artifact(v: String?) = ComposeKotlinCompatibility.artifactIdFor(v)

    private val base = ComposeKotlinCompatibility.ARTIFACT_BASE

    @Test
    fun `lines with the legacy IR API get the unsuffixed artifact`() {
        assertEquals(base, artifact("2.1.0"), "the line the k21 build targets")
        assertEquals(base, artifact("2.1.21"))
        assertEquals(base, artifact("2.0.21"))
    }

    /**
     * 2.2 widened the irCall/irString builder receiver to `IrBuilder`; a compiled call site binds
     * the exact descriptor, so these lines need the artifact built against 2.2. 2.3 shares 2.2's IR
     * API and is covered by the same binary.
     */
    @Test
    fun `the lines that widened the IR builder receiver get k22`() {
        assertEquals("$base-k22", artifact("2.2.0"))
        assertEquals("$base-k22", artifact("2.2.21"))
        assertEquals("$base-k22", artifact("2.3.0"))
        assertEquals("$base-k22", artifact("2.3.21"))
    }

    /** 2.4 moved extension registration and removed the old value-argument API. */
    @Test
    fun `the line that moved extension registration gets k24`() {
        assertEquals("$base-k24", artifact("2.4.0"))
        assertEquals("$base-k24", artifact("2.4.10"))
    }

    @Test
    fun `later lines are refused until a variant is verified against them`() {
        assertFalse(supported("2.5.0"))
        assertFalse(supported("3.0.0"))
        assertNull(artifact("2.5.0"), "no artifact may be offered for an unverified line")
    }

    /** Pre-release qualifiers must not defeat parsing — `2.4.0-RC` is still 2.4. */
    @Test
    fun `pre-release qualifiers are parsed to their line`() {
        assertEquals("$base-k24", artifact("2.4.0-RC"))
        assertEquals("$base-k24", artifact("2.4.20-Beta1"))
        assertEquals("$base-k22", artifact("2.2.0-RC2"))
        assertEquals(base, artifact("2.1.20-RC"))
        assertFalse(supported("2.5.0-Beta1"))
    }

    /**
     * Older majors predate the API change entirely — 1.9.22 was run through the same real-compiler
     * harness and compiles the consumer successfully.
     */
    @Test
    fun `older majors are supported`() {
        assertTrue(supported("1.9.22"))
        assertEquals(base, artifact("1.9.22"))
    }

    /**
     * Strict on unknown, deliberately. The guarded failure is an unrecoverable abort of the
     * consumer's compilation, so an undeterminable version must not be assumed safe — the
     * opposite of the SDK symbol probe, where unknown costs only a capture feature.
     */
    @Test
    fun `an undeterminable version is refused rather than assumed safe`() {
        assertNull(artifact(null))
        assertFalse(supported(null))
        assertFalse(supported(""))
        assertFalse(supported("not-a-version"))
        assertFalse(supported("2"))
    }
}
