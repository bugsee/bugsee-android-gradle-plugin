package com.bugsee.android.gradle.instrumentation

import com.bugsee.android.gradle.BugseeInstrumentationExtension
import com.bugsee.android.gradle.instrumentation.app_startup_tracing.StartupTier
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [InstrumentationConfigResolver.resolveStartupTier] — verifies
 * the three-source priority chain (DSL > Gradle property > manifest meta-data)
 * plus invalid-value pass-through semantics.
 *
 * Manifest meta-data source is exercised separately at the integration
 * layer (it requires a real `AndroidManifest.xml` file and is wired
 * through `ManifestModifier.getMetaDataValue`). The unit tests below
 * cover the DSL and Gradle-property paths, plus DSL fall-through on
 * invalid input.
 */
class StartupTierResolutionTest {

    private lateinit var project: Project
    private lateinit var extension: BugseeInstrumentationExtension
    private lateinit var resolver: InstrumentationConfigResolver

    @Before
    fun setUp() {
        project = ProjectBuilder.builder().build()
        extension = project.objects.newInstance(BugseeInstrumentationExtension::class.java)
        // sourceManifest = null skips the manifest-meta-data branch.
        resolver = InstrumentationConfigResolver(extension, project, null)
    }

    // ── defaults ─────────────────────────────────────────────────────

    @Test fun `no sources set returns DEFAULT (STANDARD)`() {
        assertEquals(StartupTier.STANDARD, resolver.resolveStartupTier())
    }

    // ── DSL priority ─────────────────────────────────────────────────

    @Test fun `DSL value is used when present`() {
        extension.startupTier.set("DETAILED")
        assertEquals(StartupTier.DETAILED, resolver.resolveStartupTier())
    }

    @Test fun `DSL value is case-insensitive`() {
        extension.startupTier.set("full")
        assertEquals(StartupTier.FULL, resolver.resolveStartupTier())
    }

    @Test fun `DSL OFF disables instrumentation`() {
        extension.startupTier.set("OFF")
        assertEquals(StartupTier.OFF, resolver.resolveStartupTier())
    }

    @Test fun `DSL beats Gradle property`() {
        extension.startupTier.set("MINIMAL")
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "FULL")
        assertEquals(StartupTier.MINIMAL, resolver.resolveStartupTier())
    }

    // ── Gradle-property fallback ─────────────────────────────────────

    @Test fun `Gradle property is used when DSL is unset`() {
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "DETAILED")
        assertEquals(StartupTier.DETAILED, resolver.resolveStartupTier())
    }

    @Test fun `Gradle property is case-insensitive`() {
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "minimal")
        assertEquals(StartupTier.MINIMAL, resolver.resolveStartupTier())
    }

    // ── DSL strict-failure paths ─────────────────────────────────────

    @Test fun `invalid DSL value fails the build`() {
        extension.startupTier.set("BOGUS")
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "FULL")
        val ex = assertThrows(GradleException::class.java) {
            resolver.resolveStartupTier()
        }
        assertTrue("error mentions the bad value",
            ex.message?.contains("BOGUS") == true)
        assertTrue("error mentions the DSL source",
            ex.message?.contains("DSL") == true)
    }

    @Test fun `invalid DSL value fails even with no other source`() {
        extension.startupTier.set("NONSENSE")
        assertThrows(GradleException::class.java) {
            resolver.resolveStartupTier()
        }
    }

    @Test fun `invalid DSL value error lists valid tier names`() {
        extension.startupTier.set("WHATEVER")
        val ex = assertThrows(GradleException::class.java) {
            resolver.resolveStartupTier()
        }
        for (tier in StartupTier.entries) {
            assertTrue(
                "error message should mention valid tier ${tier.name}",
                ex.message?.contains(tier.name) == true
            )
        }
    }

    // ── Gradle property / manifest stay lenient ──────────────────────

    @Test fun `invalid Gradle property with no other source returns DEFAULT`() {
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "garbage")
        assertEquals(StartupTier.DEFAULT, resolver.resolveStartupTier())
    }

    @Test fun `invalid Gradle property falls through to default when DSL unset`() {
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "BAD2")
        assertEquals(StartupTier.DEFAULT, resolver.resolveStartupTier())
    }

    // ── blank DSL value is treated as unset (NOT invalid) ────────────

    @Test fun `blank DSL value treated as unset, falls through`() {
        extension.startupTier.set("   ")
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "DETAILED")
        // Blank treated as "I didn't set this" — the strict DSL check
        // only fires for non-blank invalid values. Lets template Gradle
        // files declare `startupTier.set(System.getenv("X") ?: "")`
        // without forcing every consumer to null-guard the DSL call.
        assertEquals(StartupTier.DETAILED, resolver.resolveStartupTier())
    }

    @Test fun `empty-string DSL value treated as unset, falls through`() {
        extension.startupTier.set("")
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "MINIMAL")
        assertEquals(StartupTier.MINIMAL, resolver.resolveStartupTier())
    }
}
