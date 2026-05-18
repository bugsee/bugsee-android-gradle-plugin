package com.bugsee.android.gradle.instrumentation

import com.bugsee.android.gradle.BugseeInstrumentationExtension
import com.bugsee.android.gradle.instrumentation.app_startup_tracing.StartupTier
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
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

    // ── invalid-value fall-through ───────────────────────────────────

    @Test fun `invalid DSL value falls through to Gradle property`() {
        extension.startupTier.set("BOGUS")
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "FULL")
        assertEquals(StartupTier.FULL, resolver.resolveStartupTier())
    }

    @Test fun `invalid DSL value with no other source returns DEFAULT`() {
        extension.startupTier.set("NONSENSE")
        assertEquals(StartupTier.DEFAULT, resolver.resolveStartupTier())
    }

    @Test fun `invalid Gradle property with no other source returns DEFAULT`() {
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "garbage")
        assertEquals(StartupTier.DEFAULT, resolver.resolveStartupTier())
    }

    @Test fun `both DSL and Gradle invalid returns DEFAULT`() {
        extension.startupTier.set("BAD1")
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "BAD2")
        assertEquals(StartupTier.DEFAULT, resolver.resolveStartupTier())
    }

    @Test fun `blank DSL value treated as unset, falls through`() {
        extension.startupTier.set("   ")
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "DETAILED")
        // The DSL Property is "present" (blank string set), parse rejects
        // blanks as null → DSL branch warns and falls through to gradle.
        assertEquals(StartupTier.DETAILED, resolver.resolveStartupTier())
    }
}
