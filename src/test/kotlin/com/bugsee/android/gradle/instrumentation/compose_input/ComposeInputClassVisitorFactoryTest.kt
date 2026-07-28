package com.bugsee.android.gradle.instrumentation.compose_input

import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationContext
import com.bugsee.android.gradle.instrumentation.BugseeInstrumentationParameters
import org.gradle.api.provider.Property
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/**
 * Unit tests for [ComposeInputClassVisitorFactory.createClassVisitor] — the
 * per-class gate that decides whether `AndroidComposeView.dispatchTouchEvent`
 * gets the `BugseeComposeInputAdapter.onComposeTouch` injection.
 *
 * **Regression context (the bug this test pins).** An earlier revision of
 * [ComposeInputClassVisitorFactory.createClassVisitor] re-probed the SDK's
 * presence per-class via `ClassContext.loadClassData(adapter)` and returned
 * `nextClassVisitor` (skip) when it came back `null`:
 *
 * ```
 * if (classContext.loadClassData(parameters.get().targetClass.get()) == null) {
 *     return nextClassVisitor
 * }
 * ```
 *
 * That probe is UNRELIABLE across AGP's artifact-transform isolation
 * boundaries. AGP transforms project classes and external-JAR classes through
 * DIFFERENT boundaries, each with its own classpath. `AndroidComposeView`
 * ONLY ever exists in the third-party `androidx.compose.ui:ui` JAR — whose
 * transform boundary does NOT see the consumer's `:library` / `bugsee-android`
 * dependency — so `loadClassData(adapter)` ALWAYS returned `null` for it, and
 * the ONE class this instrumentation exists to touch was silently skipped.
 * Result: Compose touch capture never fired for any app (all of the SDK's
 * `ComposeInputCaptureTest` instrumentation tests failed with zero captured
 * events). SDK presence is instead gated once at configuration time via
 * [ComposeInputInstrumentation.shouldApply]. Same failure mode was fixed in
 * `AppStartupTracingClassVisitorFactory`.
 *
 * These tests reproduce the external-JAR boundary with a fake [ClassContext]
 * whose `loadClassData` returns `null` (or fails loudly if consulted at all)
 * and assert the factory instruments `AndroidComposeView` regardless.
 */
class ComposeInputClassVisitorFactoryTest {

    private val androidComposeView = "androidx.compose.ui.platform.AndroidComposeView"

    private val nextVisitor: ClassVisitor = object : ClassVisitor(Opcodes.ASM9) {}

    /**
     * THE regression test. The adapter class is NOT visible on the
     * `AndroidComposeView` transform boundary (`loadClassData` → `null`) — the
     * exact condition the buggy probe hit. The factory MUST still instrument
     * the class. Pre-fix, this returned `nextVisitor` and touch capture was
     * dead.
     */
    @Test
    fun `instruments AndroidComposeView even when adapter is not on this transform boundary`() {
        val context = FakeClassContext(
            current = FakeClassData(androidComposeView),
            behavior = { null }, // external-JAR boundary: :library not visible
        )

        val result = factory().createClassVisitor(context, nextVisitor)

        assertTrue(
            "AndroidComposeView must be instrumented even when loadClassData(adapter) " +
                    "returns null on its transform boundary — otherwise Compose touch " +
                    "capture never fires (the shipped-in-4.0.0 regression).",
            result is ComposeInputClassVisitor
        )
    }

    /**
     * Belt-and-suspenders: the factory must not consult `loadClassData` at
     * all. A fake that throws if called makes any reintroduced probe fail
     * loudly rather than silently skip.
     */
    @Test
    fun `does not consult loadClassData when deciding to instrument AndroidComposeView`() {
        val context = FakeClassContext(
            current = FakeClassData(androidComposeView),
            behavior = {
                throw AssertionError(
                    "createClassVisitor must NOT probe ClassContext.loadClassData — " +
                            "it is unreliable across AGP transform-boundary isolation " +
                            "(external-JAR classes cannot see the consumer's :library dep)."
                )
            },
        )

        val result = factory().createClassVisitor(context, nextVisitor)

        assertTrue(result is ComposeInputClassVisitor)
        assertEquals("loadClassData must never be called", 0, context.loadClassDataCallCount)
    }

    /**
     * The instrumenting visitor must wrap and DELEGATE to the supplied
     * `nextClassVisitor` (the downstream ClassWriter). Pins against a mutant
     * that constructs the visitor around a fresh throw-away `ClassVisitor`
     * instead of `nextClassVisitor` — that would silently sever the chain
     * (downstream receives nothing → corrupt output) yet still be
     * `instanceof ComposeInputClassVisitor`.
     */
    @Test
    fun `instrumenting visitor delegates class events to the next visitor`() {
        val recording = RecordingClassVisitor()
        val result = factory().createClassVisitor(
            FakeClassContext(FakeClassData(androidComposeView), behavior = { null }),
            recording,
        )

        result.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "androidx/compose/ui/platform/AndroidComposeView",
                null, "android/view/ViewGroup", null)

        assertTrue("returned visitor must delegate to nextClassVisitor", recording.visited)
    }

    /** Other classes in the compose platform package are passed through untouched. */
    @Test
    fun `passes through non-target compose platform classes`() {
        val context = FakeClassContext(
            current = FakeClassData("androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat"),
            behavior = { throw AssertionError("must not be consulted") },
        )

        val result = factory().createClassVisitor(context, nextVisitor)

        assertSame("non-AndroidComposeView classes must pass through unchanged", nextVisitor, result)
    }

    /** Unrelated classes are passed through untouched. */
    @Test
    fun `passes through unrelated classes`() {
        val context = FakeClassContext(
            current = FakeClassData("com.example.MyCustomView"),
            behavior = { throw AssertionError("must not be consulted") },
        )

        val result = factory().createClassVisitor(context, nextVisitor)

        assertSame(nextVisitor, result)
    }

    // ── test doubles ─────────────────────────────────────────────────────

    private fun factory(): ComposeInputClassVisitorFactory = TestableComposeInputClassVisitorFactory()

    /**
     * Concrete [ComposeInputClassVisitorFactory] for tests. Only
     * `createClassVisitor` is exercised; the Gradle-managed `parameters` /
     * `instrumentationContext` are never read on that path, so they throw to
     * catch any accidental new dependency on them.
     */
    private class TestableComposeInputClassVisitorFactory : ComposeInputClassVisitorFactory() {
        override val parameters: Property<BugseeInstrumentationParameters>
            get() = throw UnsupportedOperationException("createClassVisitor must not read parameters")
        override val instrumentationContext: InstrumentationContext
            get() = throw UnsupportedOperationException("createClassVisitor must not read instrumentationContext")
    }

    /** Records whether the wrapping visitor delegated `visit` downstream. */
    private class RecordingClassVisitor : ClassVisitor(Opcodes.ASM9) {
        var visited = false
            private set

        override fun visit(
            version: Int, access: Int, name: String?, signature: String?,
            superName: String?, interfaces: Array<out String>?,
        ) {
            visited = true
        }
    }

    private class FakeClassData(override val className: String) : ClassData {
        override val classAnnotations: List<String> = emptyList()
        override val interfaces: List<String> = emptyList()
        override val superClasses: List<String> = emptyList()
    }

    private class FakeClassContext(
        private val current: ClassData,
        private val behavior: (String) -> ClassData?,
    ) : ClassContext {
        var loadClassDataCallCount = 0
            private set

        override val currentClassData: ClassData get() = current

        override fun loadClassData(className: String): ClassData? {
            loadClassDataCallCount++
            return behavior(className)
        }
    }
}
