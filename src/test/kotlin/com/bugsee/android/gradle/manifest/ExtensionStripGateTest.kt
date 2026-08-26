package com.bugsee.android.gradle.manifest

import com.bugsee.android.gradle.instrumentation.BugseeSdkVersion
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

    /**
     * A version comfortably past the initializeExtensions floor. Passed explicitly
     * rather than defaulted: `shouldStrip` deliberately has no default for this
     * parameter, so that a new call site cannot silently omit a precondition — the
     * exact shape of the bug this gate was introduced to fix.
     */
    private val CURRENT_SDK = BugseeSdkVersion.parse("7.1.3")


    @Test
    fun `strips in the default configuration`() {
        assertTrue(
            ExtensionStripGate.shouldStrip(
                optimizeExtensionsLoading = true,
                instrumentationGloballyEnabled = true,
                sdkVersion = CURRENT_SDK,
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
                sdkVersion = CURRENT_SDK,
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
                sdkVersion = CURRENT_SDK,
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
                sdkVersion = CURRENT_SDK,
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
                    sdkVersion = CURRENT_SDK,
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
                sdkVersion = CURRENT_SDK,
                excludes = setOf("com.example.*", "org.some.Lib"),
            )
        )
    }

    // ---- SDK version floor (C4) ----
    //
    // The compensating injection rewrites `BugseeInitProvider.initializeExtensions()`,
    // a hook that only exists from SDK 7.0.0-beta11. Paired with an older SDK the
    // method never matches, so nothing is injected — and if the strip still ran, every
    // extension would be left with NEITHER a manifest <provider> NOR a registration
    // call. That is the same silently-dead-extension failure this gate exists to
    // prevent, reached by a different precondition.

    @Test
    fun `strip is disarmed when the SDK predates the initializeExtensions hook`() {
        assertFalse(
            "an SDK without initializeExtensions() cannot receive the injection, " +
                "so the providers must survive",
            ExtensionStripGate.shouldStrip(
                optimizeExtensionsLoading = true,
                instrumentationGloballyEnabled = true,
                excludes = emptySet(),
                sdkVersion = BugseeSdkVersion.parse("7.0.0-beta10"),
            )
        )
    }

    @Test
    fun `strip stays armed on the exact version that introduced the hook`() {
        assertTrue(
            ExtensionStripGate.shouldStrip(
                optimizeExtensionsLoading = true,
                instrumentationGloballyEnabled = true,
                excludes = emptySet(),
                sdkVersion = BugseeSdkVersion.parse("7.0.0-beta11"),
            )
        )
    }

    @Test
    fun `strip stays armed on a later release`() {
        assertTrue(
            ExtensionStripGate.shouldStrip(
                optimizeExtensionsLoading = true,
                instrumentationGloballyEnabled = true,
                excludes = emptySet(),
                sdkVersion = BugseeSdkVersion.parse("7.1.3"),
            )
        )
    }

    /**
     * Unparseable / absent versions (project deps, `7.+`, unresolved catalogs) must NOT
     * disarm the strip. The startup lane already made this call deliberately: refusing on
     * an unknown version would disable the feature for everyone who does not pin a literal
     * version, which is a worse failure than the narrow old-SDK pairing this guards.
     */
    @Test
    fun `an unknown SDK version leaves the strip armed`() {
        assertTrue(
            ExtensionStripGate.shouldStrip(
                optimizeExtensionsLoading = true,
                instrumentationGloballyEnabled = true,
                excludes = emptySet(),
                sdkVersion = null,
            )
        )
    }

}
