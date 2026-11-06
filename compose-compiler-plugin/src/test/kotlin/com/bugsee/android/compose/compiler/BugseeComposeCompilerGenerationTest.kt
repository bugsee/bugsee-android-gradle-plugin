package com.bugsee.android.compose.compiler

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The version gate that decides which registrar owns the IR extension (issue #138). Both registrars
 * ask the same function, so exactly one registers on any compiler; these pin where it flips.
 */
class BugseeComposeCompilerGenerationTest {

    @Test fun `Kotlin 1_9 compilers are served by the legacy registrar`() {
        for (version in listOf("1.9.0", "1.9.22", "1.9.25", "1.9.20-Beta", "1.9", "1.10.0")) {
            assertEquals(version, true, BugseeComposeCompilerGeneration.isLegacy(version))
        }
    }

    @Test fun `Kotlin 2_0 and later keep the modern registrar`() {
        for (version in listOf("2.0.0", "2.0.0-RC1", "2.0.21", "2.1.0", "2.2.20", "2.4.10", "3.0.0")) {
            assertEquals(version, false, BugseeComposeCompilerGeneration.isLegacy(version))
        }
    }

    @Test fun `Kotlin 1_8 and older are left alone`() {
        for (version in listOf("1.8.22", "1.7.10", "1.0.0")) {
            assertEquals(version, false, BugseeComposeCompilerGeneration.isLegacy(version))
        }
    }

    @Test fun `an unknown or unreadable version falls back to the modern registrar`() {
        for (version in listOf(null, "", "garbage", "@snapshot@", "1", "x.9")) {
            assertEquals(version.toString(), false, BugseeComposeCompilerGeneration.isLegacy(version))
        }
    }
}
