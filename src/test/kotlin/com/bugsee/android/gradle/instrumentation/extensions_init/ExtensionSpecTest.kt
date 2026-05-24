package com.bugsee.android.gradle.instrumentation.extensions_init

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Convention tests for [ExtensionSpec.fromInitProviderFqn]. The same
 * convention is what guarantees that the manifest-stripping side (which
 * only knows init-provider FQNs) lines up with the bytecode-injection
 * side (which needs facade FQNs + register method names).
 *
 * The fact that compose's init provider lives in a different subpackage
 * (`com.bugsee.library.compose`) than its facade (`com.bugsee.library`)
 * is the case that makes this convention non-trivial — the derivation
 * keys on the *simple* class name and pins the facade to the
 * `com.bugsee.library` package regardless of where the init provider
 * sits.
 */
class ExtensionSpecTest {

    @Test
    fun `derives feedback spec from canonical FQN`() {
        val spec = ExtensionSpec.fromInitProviderFqn(
            "com.bugsee.library.BugseeFeedbackInitProvider"
        )!!
        assertEquals("com/bugsee/library/BugseeFeedback", spec.facadeInternalName)
        assertEquals("registerFeedbackExtension", spec.registerMethodName)
        assertEquals("com.bugsee.library.BugseeFeedbackInitProvider", spec.initProviderFqn)
    }

    @Test
    fun `derives compose spec even though init provider sits in compose subpackage`() {
        // Critical case — compose's init provider FQN is
        // `com.bugsee.library.compose.BugseeComposeInitProvider`, but the
        // facade is `com.bugsee.library.BugseeCompose`. The convention
        // throws away everything except the simple class name.
        val spec = ExtensionSpec.fromInitProviderFqn(
            "com.bugsee.library.compose.BugseeComposeInitProvider"
        )!!
        assertEquals("com/bugsee/library/BugseeCompose", spec.facadeInternalName)
        assertEquals("registerComposeExtension", spec.registerMethodName)
    }

    @Test
    fun `derives ktor2 spec preserves digits in name`() {
        val spec = ExtensionSpec.fromInitProviderFqn(
            "com.bugsee.library.BugseeKtor2InitProvider"
        )!!
        assertEquals("com/bugsee/library/BugseeKtor2", spec.facadeInternalName)
        assertEquals("registerKtor2Extension", spec.registerMethodName)
    }

    @Test
    fun `rejects FQN without Bugsee prefix`() {
        // A `<provider>` that slipped past the manifest regex would land
        // here as a malformed entry — the spec derivation must reject it
        // rather than synthesize an invalid call.
        assertNull(ExtensionSpec.fromInitProviderFqn("com.example.MyInitProvider"))
    }

    @Test
    fun `rejects FQN without InitProvider suffix`() {
        assertNull(ExtensionSpec.fromInitProviderFqn("com.bugsee.library.BugseeFoo"))
    }

    @Test
    fun `rejects FQN with empty name part`() {
        // "BugseeInitProvider" itself: simple name strips prefix+suffix to
        // empty, which would synthesize a call to a no-op facade. The core
        // SDK provider should never reach the spec derivation (the manifest
        // step excludes it), but defensively reject here too.
        assertNull(ExtensionSpec.fromInitProviderFqn("com.bugsee.library.BugseeInitProvider"))
    }
}
