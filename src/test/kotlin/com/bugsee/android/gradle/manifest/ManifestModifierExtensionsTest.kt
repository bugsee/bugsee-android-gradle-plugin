package com.bugsee.android.gradle.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verifies that [ManifestModifier.removeExtensionInitProviders] strips
 * only Bugsee extension `<provider>` entries, preserves the core SDK
 * provider, leaves unrelated providers alone, and reports the removed
 * FQNs in document order.
 */
class ManifestModifierExtensionsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `strips every Bugsee extension provider and leaves the core SDK provider`() {
        val manifest = writeManifest("""
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <provider
                        android:name="com.bugsee.library.BugseeInitProvider"
                        android:authorities="example.bugseeinitprovider"
                        android:exported="false" />
                    <provider
                        android:name="com.bugsee.library.BugseeFeedbackInitProvider"
                        android:authorities="example.bugseefeedbackinitprovider"
                        android:exported="false" />
                    <provider
                        android:name="com.bugsee.library.BugseeRemotingInitProvider"
                        android:authorities="example.bugseeremotinginitprovider"
                        android:exported="false" />
                    <provider
                        android:name="com.bugsee.library.compose.BugseeComposeInitProvider"
                        android:authorities="example.bugseecomposeinitprovider"
                        android:exported="false" />
                    <provider
                        android:name="com.bugsee.library.BugseeNdkInitProvider"
                        android:authorities="example.bugseendkinitprovider"
                        android:exported="false" />
                </application>
            </manifest>
        """.trimIndent())

        val removed = ManifestModifier.removeExtensionInitProviders(manifest)

        // Order is document order — the test asserts the exact sequence
        // so a mutation that reverses iteration order or filters by name
        // (instead of by document position) is caught.
        assertEquals(
            listOf(
                "com.bugsee.library.BugseeFeedbackInitProvider",
                "com.bugsee.library.BugseeRemotingInitProvider",
                "com.bugsee.library.compose.BugseeComposeInitProvider",
                "com.bugsee.library.BugseeNdkInitProvider",
            ),
            removed,
        )

        val updated = manifest.readText()
        // Core SDK provider survives.
        assertTrue("core BugseeInitProvider must remain", "BugseeInitProvider\"" in updated || "BugseeInitProvider \"" in updated || "BugseeInitProvider'" in updated)
        // Every removed entry is gone.
        assertFalse("BugseeFeedbackInitProvider" in updated)
        assertFalse("BugseeRemotingInitProvider" in updated)
        assertFalse("BugseeComposeInitProvider" in updated)
        assertFalse("BugseeNdkInitProvider" in updated)
    }

    @Test
    fun `leaves non-Bugsee providers untouched`() {
        val manifest = writeManifest("""
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <provider
                        android:name="com.example.MyInitProvider"
                        android:authorities="example.myinitprovider"
                        android:exported="false" />
                    <provider
                        android:name="androidx.startup.InitializationProvider"
                        android:authorities="example.androidx.startup"
                        android:exported="false" />
                    <provider
                        android:name="com.bugsee.library.BugseeFeedbackInitProvider"
                        android:authorities="example.bugseefeedbackinitprovider"
                        android:exported="false" />
                </application>
            </manifest>
        """.trimIndent())

        val removed = ManifestModifier.removeExtensionInitProviders(manifest)

        assertEquals(
            listOf("com.bugsee.library.BugseeFeedbackInitProvider"),
            removed,
        )

        val updated = manifest.readText()
        // Third-party providers are preserved.
        assertTrue("com.example.MyInitProvider must remain", "com.example.MyInitProvider" in updated)
        assertTrue("androidx.startup must remain", "androidx.startup.InitializationProvider" in updated)
        assertFalse("BugseeFeedbackInitProvider" in updated)
    }

    @Test
    fun `returns empty list and leaves file untouched when no extension providers present`() {
        val original = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <provider
                        android:name="com.bugsee.library.BugseeInitProvider"
                        android:authorities="example.bugseeinitprovider"
                        android:exported="false" />
                </application>
            </manifest>
        """.trimIndent()
        val manifest = writeManifest(original)

        val beforeBytes = manifest.readBytes()
        val removed = ManifestModifier.removeExtensionInitProviders(manifest)
        val afterBytes = manifest.readBytes()

        assertEquals(emptyList<String>(), removed)
        // No-op semantics — the function only rewrites when something
        // was removed. A regression here would cause unnecessary disk
        // churn and re-trigger downstream Gradle tasks every build.
        assertTrue("manifest must be byte-identical when nothing to remove", beforeBytes.contentEquals(afterBytes))
    }

    @Test
    fun `does not match classes named like BugseeContextProvider or BugseeFooProvider`() {
        // Defensive: the regex must require BOTH "Bugsee" prefix and
        // "InitProvider" suffix. Anything else stays.
        val manifest = writeManifest("""
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <provider
                        android:name="com.bugsee.library.BugseeContextProvider"
                        android:authorities="example.contextprovider"
                        android:exported="false" />
                    <provider
                        android:name="com.bugsee.library.BugseeFooProvider"
                        android:authorities="example.fooprovider"
                        android:exported="false" />
                    <provider
                        android:name="com.example.BugseeStyleInitProvider"
                        android:authorities="example.styleinitprovider"
                        android:exported="false" />
                </application>
            </manifest>
        """.trimIndent())

        val removed = ManifestModifier.removeExtensionInitProviders(manifest)

        // BugseeStyleInitProvider matches the convention even in a foreign
        // package — the convention is shape-based, not package-based.
        assertEquals(
            listOf("com.example.BugseeStyleInitProvider"),
            removed,
        )
        val updated = manifest.readText()
        assertTrue("BugseeContextProvider must remain", "BugseeContextProvider" in updated)
        assertTrue("BugseeFooProvider must remain", "BugseeFooProvider" in updated)
        assertFalse("BugseeStyleInitProvider" in updated)
    }

    @Test
    fun `provider nested inside a deeper wrapping element is still removed without DOMException`() {
        // Defensive: `getElementsByTagName("provider")` returns
        // descendants at ANY depth (DOM Level 1 contract). The prior
        // implementation called `application.removeChild(node)` which
        // throws `DOMException.NOT_FOUND_ERR` when `node`'s actual
        // parent isn't `<application>` directly. Realistic manifests
        // don't nest providers, but AGP's manifest merger can produce
        // odd shapes under build-type / flavor overlay scenarios.
        // The fix routes via `node.parentNode?.removeChild(node)` —
        // pin that the no-throw contract holds and the nested
        // provider is still detected and removed.
        //
        // The fixture is contrived (Android manifests legitimately
        // can't have `<application>` inside `<application>`), but it
        // exercises exactly the bytecode path that handles the case
        // when `getElementsByTagName` finds a deeper descendant.
        // Any future shape where AGP nests providers under wrapping
        // elements is covered by this same path.
        val manifest = writeManifest("""
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <!-- Direct child: normal path. -->
                    <provider
                        android:name="com.bugsee.library.BugseeFeedbackInitProvider"
                        android:authorities="example.bugseefeedbackinitprovider"
                        android:exported="false" />
                    <!-- Nested inside a hypothetical wrapping element.
                         `getElementsByTagName("provider")` walks the
                         entire subtree; this provider would have been
                         a NOT_FOUND_ERR pre-fix. -->
                    <queries>
                        <provider
                            android:name="com.bugsee.library.BugseeNestedInitProvider"
                            android:authorities="example.bugseenestedinitprovider"
                            android:exported="false" />
                    </queries>
                </application>
            </manifest>
        """.trimIndent())
        // Must NOT throw — pre-fix this would have crashed with
        // `DOMException: NOT_FOUND_ERR: An attempt was made to
        // reference a node in a context where it does not exist.`
        val removed = ManifestModifier.removeExtensionInitProviders(manifest)

        assertEquals(
            "both extension providers must be detected (direct + nested)",
            listOf(
                "com.bugsee.library.BugseeFeedbackInitProvider",
                "com.bugsee.library.BugseeNestedInitProvider",
            ),
            removed,
        )
        val updated = manifest.readText()
        assertFalse(
            "direct-child extension provider must be stripped",
            updated.contains("BugseeFeedbackInitProvider"),
        )
        assertFalse(
            "nested extension provider must ALSO be stripped (the load-bearing claim)",
            updated.contains("BugseeNestedInitProvider"),
        )
    }

    private fun writeManifest(content: String): File {
        val f = tempFolder.newFile("AndroidManifest.xml")
        f.writeText(content)
        return f
    }
}
