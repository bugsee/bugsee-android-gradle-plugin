package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.bugsee.android.gradle.StartupTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [shouldInstantiateVisitor], the factory-side gating
 * decision that picks whether to spin up an [AppStartupTracingClassVisitor]
 * for a given class.
 *
 * **Regression context:** an earlier revision of
 * [AppStartupTracingClassVisitorFactory.createClassVisitor] short-
 * circuited on `kinds.isEmpty()` BEFORE considering the tier's
 * annotation-pickup behavior. The result: at FULL tier, on any
 * non-Application / non-ContentProvider / non-Initializer /
 * non-ComponentRegistrar / non-Configuration.Provider class, the
 * factory returned `nextClassVisitor` without instantiating the
 * visitor — silently disabling `@BugseeTrace` instrumentation for the
 * vast majority of classes, contradicting the documented FULL-tier
 * behavior. The end-to-end transform tests in `FullTierTransformTest`
 * could not catch this because they bypass the factory and instantiate
 * the visitor directly. This test pins the gate at the helper level.
 */
class AppStartupTracingClassVisitorFactoryGatingTest {

    private val anyCandidate = MethodKey("onCreate", "()V")
    private val noCandidates: Set<MethodKey> = emptySet()
    private val someCandidates: Set<MethodKey> = setOf(anyCandidate)

    // ── kind-candidate path: instantiate regardless of tier (except OFF) ─

    @Test fun `kind candidate present at MINIMAL — instantiate`() {
        assertTrue(shouldInstantiateVisitor(someCandidates, StartupTier.MINIMAL))
    }

    @Test fun `kind candidate present at STANDARD — instantiate`() {
        assertTrue(shouldInstantiateVisitor(someCandidates, StartupTier.STANDARD))
    }

    @Test fun `kind candidate present at DETAILED — instantiate`() {
        assertTrue(shouldInstantiateVisitor(someCandidates, StartupTier.DETAILED))
    }

    @Test fun `kind candidate present at FULL — instantiate`() {
        assertTrue(shouldInstantiateVisitor(someCandidates, StartupTier.FULL))
    }

    // ── annotation-pickup path: only FULL instantiates on non-kind class ─

    @Test fun `no kind candidates at MINIMAL — do NOT instantiate`() {
        assertFalse(shouldInstantiateVisitor(noCandidates, StartupTier.MINIMAL))
    }

    @Test fun `no kind candidates at STANDARD — do NOT instantiate`() {
        assertFalse(shouldInstantiateVisitor(noCandidates, StartupTier.STANDARD))
    }

    @Test fun `no kind candidates at DETAILED — do NOT instantiate`() {
        assertFalse(shouldInstantiateVisitor(noCandidates, StartupTier.DETAILED))
    }

    /**
     * **The W4 regression test.** Non-kind class + FULL tier MUST
     * instantiate the visitor so its FULL-tier annotation-peek path
     * can find `@BugseeTrace`. Pre-fix the factory returned
     * `nextClassVisitor` here, silently disabling the feature.
     */
    @Test fun `no kind candidates at FULL — instantiate for annotation pickup`() {
        assertTrue(
            "W4 regression: FULL tier must instantiate the visitor on " +
                    "non-kind classes so the annotation-peek path can fire — " +
                    "otherwise @BugseeTrace is silently ignored except on " +
                    "Application/ContentProvider/Initializer/etc.",
            shouldInstantiateVisitor(noCandidates, StartupTier.FULL)
        )
    }

    // ── tier predicate semantics ─────────────────────────────────────

    @Test fun `the FULL escape hatch fires only at FULL — boundary check`() {
        // Spell out the boundary explicitly: only FULL has
        // picksUpAnnotated() == true. A future enum reorder or new
        // tier between DETAILED and FULL must update either
        // StartupTier.picksUpAnnotated or this test (or both).
        val tiersThatPickUpAnnotated = StartupTier.entries.filter { it.picksUpAnnotated() }
        assertTrue(
            "FULL must remain the only annotation-pickup tier; saw $tiersThatPickUpAnnotated",
            tiersThatPickUpAnnotated == listOf(StartupTier.FULL)
        )
    }

    // ── kindsActiveAtTier — MINIMAL filtering ────────────────────────────
    //
    // The factory's KDoc + the StartupTier.kt KDoc both say MINIMAL
    // covers Application + ContentProvider ONLY; STANDARD+ adds
    // Initializer, ComponentRegistrar, Configuration.Provider. A prior
    // revision did not gate kinds by tier — MINIMAL silently paid the
    // per-method wrap cost for every Initializer / ComponentRegistrar
    // / Configuration.Provider on the classpath. These tests pin the
    // documented behavior at the helper level so a future refactor of
    // the tier ladder can't silently re-introduce the gap.

    private val applicationOnly = setOf(ClassKind.APPLICATION)
    private val contentProviderOnly = setOf(ClassKind.CONTENT_PROVIDER)
    private val initializerOnly = setOf(ClassKind.INITIALIZER)
    private val componentRegistrarOnly = setOf(ClassKind.COMPONENT_REGISTRAR)
    private val configurationProviderOnly = setOf(ClassKind.CONFIGURATION_PROVIDER)
    private val allKinds = setOf(
        ClassKind.APPLICATION,
        ClassKind.CONTENT_PROVIDER,
        ClassKind.INITIALIZER,
        ClassKind.COMPONENT_REGISTRAR,
        ClassKind.CONFIGURATION_PROVIDER,
    )

    @Test fun `MINIMAL — Application kind passes through`() {
        assertTrue(applicationOnly == kindsActiveAtTier(applicationOnly, StartupTier.MINIMAL))
    }

    @Test fun `MINIMAL — ContentProvider kind passes through`() {
        assertTrue(contentProviderOnly == kindsActiveAtTier(contentProviderOnly, StartupTier.MINIMAL))
    }

    @Test fun `MINIMAL — Initializer kind is dropped`() {
        // The load-bearing claim. Pre-fix, an Initializer-only class
        // would be wrapped at MINIMAL despite the documented contract.
        assertTrue(
            "MINIMAL must drop INITIALIZER — only Application + ContentProvider qualify",
            kindsActiveAtTier(initializerOnly, StartupTier.MINIMAL).isEmpty()
        )
    }

    @Test fun `MINIMAL — ComponentRegistrar kind is dropped`() {
        assertTrue(
            "MINIMAL must drop COMPONENT_REGISTRAR — only Application + ContentProvider qualify",
            kindsActiveAtTier(componentRegistrarOnly, StartupTier.MINIMAL).isEmpty()
        )
    }

    @Test fun `MINIMAL — ConfigurationProvider kind is dropped`() {
        assertTrue(
            "MINIMAL must drop CONFIGURATION_PROVIDER — only Application + ContentProvider qualify",
            kindsActiveAtTier(configurationProviderOnly, StartupTier.MINIMAL).isEmpty()
        )
    }

    @Test fun `MINIMAL — mixed kind set keeps only Application + ContentProvider`() {
        // A class that qualifies for multiple kinds (rare but possible
        // — e.g. a ContentProvider that also happens to implement
        // Initializer) at MINIMAL should keep only the kinds the tier
        // covers.
        val result = kindsActiveAtTier(allKinds, StartupTier.MINIMAL)
        assertTrue(
            "MINIMAL must keep exactly {APPLICATION, CONTENT_PROVIDER} from a full kind set; got $result",
            result == setOf(ClassKind.APPLICATION, ClassKind.CONTENT_PROVIDER)
        )
    }

    // ── kindsActiveAtTier — STANDARD and above pass through ──────────────

    @Test fun `STANDARD — all kinds pass through`() {
        assertTrue(allKinds == kindsActiveAtTier(allKinds, StartupTier.STANDARD))
    }

    @Test fun `DETAILED — all kinds pass through`() {
        assertTrue(allKinds == kindsActiveAtTier(allKinds, StartupTier.DETAILED))
    }

    @Test fun `FULL — all kinds pass through`() {
        assertTrue(allKinds == kindsActiveAtTier(allKinds, StartupTier.FULL))
    }

    @Test fun `STANDARD — Initializer-only class is active`() {
        // STANDARD is when Initializer / ComponentRegistrar /
        // Configuration.Provider become active. Pin the lower bound so
        // a tier-ladder shuffle can't silently move them up to
        // DETAILED or FULL.
        assertTrue(
            initializerOnly == kindsActiveAtTier(initializerOnly, StartupTier.STANDARD)
        )
        assertTrue(
            componentRegistrarOnly == kindsActiveAtTier(componentRegistrarOnly, StartupTier.STANDARD)
        )
        assertTrue(
            configurationProviderOnly == kindsActiveAtTier(configurationProviderOnly, StartupTier.STANDARD)
        )
    }

    // ── kindsActiveAtTier — degenerate cases ─────────────────────────────

    @Test fun `empty kind set passes through unchanged at every tier`() {
        for (tier in StartupTier.entries) {
            assertTrue(
                "empty kind set must remain empty at tier=$tier",
                kindsActiveAtTier(emptySet(), tier).isEmpty()
            )
        }
    }

    // ── kindsActiveAtTier — hot-path reference-identity contract ─────────
    //
    // The KDoc on `kindsActiveAtTier` explicitly contracts the
    // STANDARD-and-above fast path to return the SAME `Set` reference
    // (not a copy) so downstream code in `createClassVisitor` can hand
    // the result straight to `StartupMethodFilter.candidateMethodsFor`
    // without paying an extra allocation per class. A mutation that
    // silently changes the return to `HashSet(kinds)` or
    // `kinds.toSet()` would pass every set-equality assertion above
    // but break this allocation-budget contract — so assert reference
    // identity directly.

    @Test fun `STANDARD returns the same Set reference (no allocation)`() {
        assertSame(allKinds, kindsActiveAtTier(allKinds, StartupTier.STANDARD))
    }

    @Test fun `DETAILED returns the same Set reference (no allocation)`() {
        assertSame(allKinds, kindsActiveAtTier(allKinds, StartupTier.DETAILED))
    }

    @Test fun `FULL returns the same Set reference (no allocation)`() {
        assertSame(allKinds, kindsActiveAtTier(allKinds, StartupTier.FULL))
    }

    @Test fun `empty input returns the same Set reference (no allocation)`() {
        // The empty-input fast path likewise contracts to return the
        // input ref. A mutation to `return emptySet()` would pass the
        // `isEmpty()` assertion above but allocate (and break the
        // contract documented on the helper's empty-case branch).
        val empty: Set<ClassKind> = emptySet()
        assertSame(empty, kindsActiveAtTier(empty, StartupTier.MINIMAL))
        assertSame(empty, kindsActiveAtTier(empty, StartupTier.STANDARD))
    }

    // ── kindsActiveAtTier — OFF tier behavior ────────────────────────────
    //
    // Production callers short-circuit at OFF before reaching this
    // helper (createClassVisitor at line 72; isInstrumentable has no
    // OFF guard but the per-class result is irrelevant once
    // createClassVisitor returns nextClassVisitor). The helper itself
    // is still defined for OFF — it falls through to the MINIMAL
    // filter (OFF.ordinal < STANDARD.ordinal). Pin that defensible
    // behavior so a future refactor that special-cases OFF doesn't
    // silently change semantics.

    @Test fun `OFF — Initializer kind is dropped (same as MINIMAL)`() {
        assertTrue(
            kindsActiveAtTier(initializerOnly, StartupTier.OFF).isEmpty()
        )
    }

    @Test fun `OFF — Application kind passes through (same as MINIMAL)`() {
        assertTrue(
            applicationOnly == kindsActiveAtTier(applicationOnly, StartupTier.OFF)
        )
    }

    @Test fun `OFF — mixed kind set keeps only Application + ContentProvider`() {
        val result = kindsActiveAtTier(allKinds, StartupTier.OFF)
        assertTrue(
            "OFF must behave like MINIMAL on the helper; got $result",
            result == setOf(ClassKind.APPLICATION, ClassKind.CONTENT_PROVIDER)
        )
    }
}
