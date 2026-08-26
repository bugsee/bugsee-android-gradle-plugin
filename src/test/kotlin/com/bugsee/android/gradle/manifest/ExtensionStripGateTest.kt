package com.bugsee.android.gradle.manifest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the strip-follows-injection contract of [ExtensionStripGate].
 *
 * The regression this guards: `BugseeManifestTask` used to strip Bugsee
 * extension `<provider>` entries keyed ONLY on `optimizeExtensionsLoading`,
 * while the compensating `ExtensionsInitInstrumentation` bytecode injection
 * additionally required instrumentation to be globally enabled and the
 * target class to not be excluded. A consumer running with
 * `-Pbugsee.instrumentation.enabled=false` (e.g. to keep instrumented
 * classes out of host-app unit tests) would ship an APK whose extensions
 * (NDK crash reporting, feedback, compose, remoting) never register —
 * stripped from the manifest with no inlined registration call replacing
 * them.
 */
class ExtensionStripGateTest {

    @Test
    fun `strips in the default configuration`() {
        assertTrue(
            ExtensionStripGate.shouldStrip(
                optimizeExtensionsLoading = true,
                instrumentationGloballyEnabled = true,
                excludes = emptySet(),
            )
        )
    }

    @Test
    fun `does not strip when optimizeExtensionsLoading is off`() {
        assertFalse(
            ExtensionStripGate.shouldStrip(
                optimizeExtensionsLoading = false,
                instrumentationGloballyEnabled = true,
                excludes = emptySet(),
            )
        )
    }

    @Test
    fun `does not strip when instrumentation is globally disabled`() {
        // The injection cannot run — stripping would orphan every extension.
        assertFalse(
            ExtensionStripGate.shouldStrip(
                optimizeExtensionsLoading = true,
                instrumentationGloballyEnabled = false,
                excludes = emptySet(),
            )
        )
    }

    @Test
    fun `does not strip when the injection target is excluded`() {
        // InstrumentationException's help text tells consumers to add exactly
        // this exclude when the ExtensionsInit rewrite fails on their build.
        assertFalse(
            ExtensionStripGate.shouldStrip(
                optimizeExtensionsLoading = true,
                instrumentationGloballyEnabled = true,
                excludes = setOf("com.bugsee.library.BugseeInitProvider"),
            )
        )
    }

    @Test
    fun `broad glob and package-prefix excludes also disarm the strip`() {
        for (pattern in listOf("com.bugsee.library", "com.bugsee.*", "com.*")) {
            assertFalse(
                "pattern '$pattern' matches the injection target, so stripping must stand down",
                ExtensionStripGate.shouldStrip(
                    optimizeExtensionsLoading = true,
                    instrumentationGloballyEnabled = true,
                    excludes = setOf(pattern),
                )
            )
        }
    }

    @Test
    fun `unrelated excludes keep the strip armed`() {
        assertTrue(
            ExtensionStripGate.shouldStrip(
                optimizeExtensionsLoading = true,
                instrumentationGloballyEnabled = true,
                excludes = setOf("com.example.*", "org.some.Lib"),
            )
        )
    }
}
