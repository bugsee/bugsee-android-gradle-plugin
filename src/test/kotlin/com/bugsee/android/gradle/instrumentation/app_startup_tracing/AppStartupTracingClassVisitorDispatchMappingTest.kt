package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Direct unit tests for the private companion function
 * `AppStartupTracingClassVisitor.dispatchMethodNamesFor(ClassKind?)`.
 *
 * The function is not visible from Kotlin source (`private` inside a
 * `private companion object`), so this test reaches it via Java reflection
 * on the compiled `Companion` nested class. We avoid `kotlin-reflect` to
 * keep the plugin's test runtime small.
 *
 * Mutation rationale (per test): see comments above each test. The class-
 * level rationale is "catch a kind-routing flip in `dispatchMethodNamesFor`,
 * e.g. APPLICATION → (onProviderStart, onProviderEnd) — which would
 * surface in the SDK as Application spans showing under the
 * `app.startup.provider` row".
 */
class AppStartupTracingClassVisitorDispatchMappingTest {

    /**
     * Reflectively invokes the private companion function
     * `dispatchMethodNamesFor(ClassKind?): Pair<String, String>`.
     *
     * The Kotlin compiler emits the function on the synthetic `Companion`
     * inner class of [AppStartupTracingClassVisitor]; we look it up by
     * name with a single `ClassKind` parameter (Kotlin nullable types
     * compile to the same erased parameter type) and invoke it on the
     * companion singleton instance.
     */
    private fun callDispatchMethodNamesFor(kind: ClassKind?): Pair<*, *> {
        val visitorClass = AppStartupTracingClassVisitor::class.java
        // The private companion object is exposed as a nested class named
        // `Companion` on the outer; its singleton instance is the outer
        // class's `Companion` static field. Both the field and the
        // companion class itself are `private`, so we need
        // `setAccessible(true)` on both the field and the method lookup.
        val companionField = visitorClass.getDeclaredField("Companion")
        companionField.isAccessible = true
        val companionInstance = companionField.get(null)

        val method = companionInstance.javaClass.getDeclaredMethod(
            "dispatchMethodNamesFor",
            ClassKind::class.java,
        )
        method.isAccessible = true
        val result = method.invoke(companionInstance, kind) as Pair<*, *>
        return result
    }

    // Catches a mutation that swaps APPLICATION → any other dispatcher pair
    // (e.g. APPLICATION → (onProviderStart, onProviderEnd)). Verifies the
    // exact start/end name strings the bytecode wrapper will emit.
    @Test fun `APPLICATION maps to onApplicationStart and onApplicationEnd`() {
        val (start, end) = callDispatchMethodNamesFor(ClassKind.APPLICATION)
        assertEquals("onApplicationStart", start)
        assertEquals("onApplicationEnd", end)
    }

    // Catches a mutation that swaps CONTENT_PROVIDER → any other dispatcher
    // pair (e.g. CONTENT_PROVIDER → (onMethodStart, onMethodEnd) which would
    // collapse provider spans into the generic `app.startup.method` row).
    @Test fun `CONTENT_PROVIDER maps to onProviderStart and onProviderEnd`() {
        val (start, end) = callDispatchMethodNamesFor(ClassKind.CONTENT_PROVIDER)
        assertEquals("onProviderStart", start)
        assertEquals("onProviderEnd", end)
    }

    // Catches a mutation that promotes INITIALIZER out of the generic METHOD_*
    // fallback (e.g. INITIALIZER → (onApplicationStart, onApplicationEnd))
    // which would surface initializer spans under `app.startup.application`.
    @Test fun `INITIALIZER falls back to onMethodStart and onMethodEnd`() {
        val (start, end) = callDispatchMethodNamesFor(ClassKind.INITIALIZER)
        assertEquals("onMethodStart", start)
        assertEquals("onMethodEnd", end)
    }

    // Catches a mutation that promotes COMPONENT_REGISTRAR out of the generic
    // METHOD_* fallback.
    @Test fun `COMPONENT_REGISTRAR falls back to onMethodStart and onMethodEnd`() {
        val (start, end) = callDispatchMethodNamesFor(ClassKind.COMPONENT_REGISTRAR)
        assertEquals("onMethodStart", start)
        assertEquals("onMethodEnd", end)
    }

    // Catches a mutation that promotes CONFIGURATION_PROVIDER out of the
    // generic METHOD_* fallback.
    @Test fun `CONFIGURATION_PROVIDER falls back to onMethodStart and onMethodEnd`() {
        val (start, end) = callDispatchMethodNamesFor(ClassKind.CONFIGURATION_PROVIDER)
        assertEquals("onMethodStart", start)
        assertEquals("onMethodEnd", end)
    }

    // Catches a mutation that removes the defensive `null` branch (which
    // would throw a NoWhenBranchMatchedException at runtime in production
    // bytecode) or routes `null` to a kind-specific pair.
    @Test fun `null kind falls back to onMethodStart and onMethodEnd (defensive branch)`() {
        val (start, end) = callDispatchMethodNamesFor(null)
        assertEquals("onMethodStart", start)
        assertEquals("onMethodEnd", end)
    }
}
