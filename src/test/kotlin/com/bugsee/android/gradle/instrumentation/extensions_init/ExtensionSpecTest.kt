package com.bugsee.android.gradle.instrumentation.extensions_init

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [ExtensionSpec.fromInitProviderFqn] and the [ExtensionSpec.KNOWN]
 * table, the one definition both the manifest strip and the bytecode injection
 * read, so the two cannot disagree about which providers are replaced.
 */
class ExtensionSpecTest {

    @Test
    fun `rejects FQN without Bugsee prefix`() {
        assertNull(ExtensionSpec.fromInitProviderFqn("com.example.MyInitProvider"))
    }

    @Test
    fun `rejects FQN without InitProvider suffix`() {
        assertNull(ExtensionSpec.fromInitProviderFqn("com.bugsee.library.BugseeFoo"))
    }

    @Test
    fun `rejects the core SDK provider`() {
        // The core provider is the consolidation target, never a source.
        assertNull(ExtensionSpec.fromInitProviderFqn("com.bugsee.library.BugseeInitProvider"))
    }

    /**
     * Pins the table against the SDK's shipped extensions (android/sdk, each module's
     * `src/main/AndroidManifest.xml` and `Bugsee<Name>.register<Name>Extension()`).
     * A row that drifts from the SDK strips a provider and injects a call to a
     * missing facade.
     */
    @Test
    fun `known table matches the SDK's shipped extensions`() {
        assertEquals(
            listOf(
                Triple("com.bugsee.library.BugseeFeedbackInitProvider", "com/bugsee/library/BugseeFeedback", "registerFeedbackExtension"),
                Triple("com.bugsee.library.BugseeRemotingInitProvider", "com/bugsee/library/BugseeRemoting", "registerRemotingExtension"),
                Triple("com.bugsee.library.compose.BugseeComposeInitProvider", "com/bugsee/library/BugseeCompose", "registerComposeExtension"),
                Triple("com.bugsee.library.BugseeNdkInitProvider", "com/bugsee/library/BugseeNdk", "registerNdkExtension"),
                Triple("com.bugsee.library.BugseeOkHttpInitProvider", "com/bugsee/library/BugseeOkHttp", "registerOkHttpExtension"),
                Triple("com.bugsee.library.BugseeKtor2InitProvider", "com/bugsee/library/BugseeKtor2", "registerKtor2Extension"),
                Triple("com.bugsee.library.BugseeKtor3InitProvider", "com/bugsee/library/BugseeKtor3", "registerKtor3Extension"),
                Triple("com.bugsee.library.BugseeCronetInitProvider", "com/bugsee/library/BugseeCronet", "registerCronetExtension"),
                Triple("com.bugsee.library.BugseeLeakInitProvider", "com/bugsee/library/BugseeLeak", "registerLeakExtension"),
            ),
            ExtensionSpec.KNOWN.map { Triple(it.initProviderFqn, it.facadeInternalName, it.registerMethodName) },
        )
    }

    /**
     * Regression: plugin <= 4.0.6 derived a facade from any `*.Bugsee<Word>InitProvider`,
     * so these were stripped and replaced by a call into a class that does not exist.
     */
    @Test
    fun `rejects Bugsee-shaped providers that are not known extensions`() {
        for (fqn in listOf(
            "com.acme.probe.BugseeFooInitProvider",
            "com.bugsee.reactnative.BugseeWrapperInitProvider",
            "com.bugsee.library.BugseeFooInitProvider",
            // A known simple name in a foreign package is still not ours.
            "com.acme.BugseeNdkInitProvider",
        )) {
            assertNull(fqn, ExtensionSpec.fromInitProviderFqn(fqn))
        }
    }
}
