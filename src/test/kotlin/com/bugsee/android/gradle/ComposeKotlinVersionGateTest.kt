package com.bugsee.android.gradle

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate that keeps the bundled Compose compiler plugin out of a Kotlin compiler whose
 * extension API it was not built against.
 *
 * Kotlin compiler-plugin APIs move without a deprecation cycle. On Kotlin 2.4 the registrar dies
 * with `ClassCastException: IrGenerationExtension$Companion cannot be cast to
 * ProjectExtensionDescriptor`, aborting the CONSUMER's compilation — a failure an app author can
 * neither diagnose nor route around. Verified by building a Compose consumer on 2.1, 2.2, 2.3
 * (all fine) and 2.4 (hard failure).
 */
class ComposeKotlinVersionGateTest {

    private fun supported(v: String?) = ComposeKotlinCompatibility.isSupported(v)

    @Test
    fun `versions the compiler plugin was verified against are supported`() {
        assertTrue(supported("2.1.0"), "the line this artifact is compiled against")
        assertTrue(supported("2.2.0"))
        assertTrue(supported("2.2.20"))
        assertTrue(supported("2.3.0"))
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
        assertTrue(supported("2.3.0-RC2"))
    }

    /** Older majors predate the API change entirely. */
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
