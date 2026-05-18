package com.bugsee.android.gradle.instrumentation

import com.bugsee.android.gradle.BugseeInstrumentationExtension
import com.bugsee.android.gradle.StartupTier
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests for [InstrumentationConfigResolver.resolveStartupTier] — verifies
 * the three-source priority chain (DSL > Gradle property > manifest meta-data)
 * plus lenient fall-through semantics for the string-based sources.
 *
 * **DSL source is typed.** `extension.startupTier` is
 * `Property<StartupTier>` — the compiler enforces validity, so invalid-DSL-
 * value tests are not possible (and not needed). Tests that used to assert
 * a `GradleException` on `extension.startupTier.set("BLERG")` were dropped
 * when the DSL became typed; the contract is now enforced at write time.
 *
 * Manifest meta-data source is exercised separately at the integration
 * layer (it requires a real `AndroidManifest.xml` file and is wired
 * through `ManifestModifier.getMetaDataValue`). The unit tests below
 * cover the DSL and Gradle-property paths.
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
        extension.startupTier.set(StartupTier.DETAILED)
        assertEquals(StartupTier.DETAILED, resolver.resolveStartupTier())
    }

    @Test fun `DSL OFF disables instrumentation`() {
        extension.startupTier.set(StartupTier.OFF)
        assertEquals(StartupTier.OFF, resolver.resolveStartupTier())
    }

    @Test fun `DSL beats Gradle property`() {
        extension.startupTier.set(StartupTier.MINIMAL)
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "FULL")
        assertEquals(StartupTier.MINIMAL, resolver.resolveStartupTier())
    }

    @Test fun `every tier value reachable via DSL`() {
        // Sanity that all enum values can be set and resolved. Catches a
        // future regression where the resolver special-cases certain
        // tiers (e.g., refusing to return OFF).
        for (tier in StartupTier.entries) {
            extension.startupTier.set(tier)
            assertEquals(tier, resolver.resolveStartupTier())
        }
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

    // ── Gradle property stays lenient on invalid values ──────────────

    @Test fun `invalid Gradle property with no other source returns DEFAULT`() {
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "garbage")
        assertEquals(StartupTier.DEFAULT, resolver.resolveStartupTier())
    }

    @Test fun `invalid Gradle property falls through to default when DSL unset`() {
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "BAD2")
        assertEquals(StartupTier.DEFAULT, resolver.resolveStartupTier())
    }

    // ── DSL.isPresent semantics ──────────────────────────────────────

    @Test fun `unset DSL value falls through to Gradle property`() {
        // Property is not set at all — `isPresent` is false, so the
        // resolver consults the next source.
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "DETAILED")
        assertEquals(StartupTier.DETAILED, resolver.resolveStartupTier())
    }

    @Test fun `DSL set to absent Provider falls through to Gradle property`() {
        // Gradle's `Property` semantics: setting to a Provider whose
        // `getOrNull()` returns null leaves `isPresent == false`. The
        // resolver's `isPresent` check should treat this the same as
        // never having called `.set(...)`. Common shape in build
        // scripts that read tier from an environment variable:
        //   startupTier.set(provider { System.getenv("X")?.let(StartupTier::parse) })
        // — when X is unset, the provider yields null and the resolver
        // falls through to the next source.
        extension.startupTier.set(project.provider<StartupTier> { null })
        project.extensions.extraProperties.set("bugsee.instrumentation.startupTier", "MINIMAL")
        assertEquals(StartupTier.MINIMAL, resolver.resolveStartupTier())
    }
}
