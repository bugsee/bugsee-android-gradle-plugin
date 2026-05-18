package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.bugsee.android.gradle.StartupTier
import org.junit.Assert.assertFalse
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
}
