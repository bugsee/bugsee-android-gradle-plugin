package com.bugsee.android.gradle.manifest

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xml.sax.SAXParseException
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pin XXE-hardening on [ManifestModifier]'s XML parsing.
 *
 * **The threat surface.** [ManifestModifier] parses
 * `AndroidManifest.xml` from the consumer's project sources and AGP
 * intermediates. While the threat surface is build-time only (an
 * attacker needs write access to a file inside the project root or
 * a transitive dependency's AAR), an XML External Entity attack via
 * a malicious DOCTYPE could:
 *  - exfiltrate local files via DTD-resolution side effects;
 *  - perform server-side request forgery via external entities
 *    fetched over HTTP;
 *  - blow up the parser via the "Billion Laughs" entity expansion.
 *
 * The fix routes every `DocumentBuilderFactory.newInstance()` call
 * through a [ManifestModifier.secureDocumentBuilderFactory] helper
 * that disables DOCTYPE entirely (the primary defense) plus the
 * external-entity, parameter-entity, external-DTD, XInclude, and
 * entity-expansion features (defense-in-depth in case the parser
 * implementation deviates from the disallow-doctype-decl
 * behaviour).
 *
 * These tests use real attack-shape inputs (not stubs) and verify
 * the hardened parser refuses them. Each test exercises a different
 * `ManifestModifier` entry point — they all share the same
 * factory, so any one passing the test proves the factory is in
 * effect for that code path.
 */
class ManifestModifierXxeHardeningTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun writeManifest(content: String): File {
        val f = temp.newFile("AndroidManifest-${System.nanoTime()}.xml")
        f.writeText(content)
        return f
    }

    /**
     * Manifest with an explicit DOCTYPE declaration but no entity
     * use. This is the SIMPLEST shape the hardening must block —
     * `disallow-doctype-decl=true` rejects ANY DOCTYPE, full stop.
     */
    private val doctypeOnlyManifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <!DOCTYPE manifest>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.example">
            <application android:label="X" />
        </manifest>
    """.trimIndent()

    /**
     * Manifest with a classic XXE — `<!ENTITY xxe SYSTEM "file:///...">`
     * followed by a reference. Pre-hardening, the parser would
     * resolve the entity and substitute the file contents into the
     * document. Post-hardening, the DOCTYPE itself is rejected.
     */
    private val xxeSystemEntityManifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <!DOCTYPE manifest [
            <!ENTITY xxe SYSTEM "file:///etc/passwd">
        ]>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.example">
            <application android:label="&xxe;" />
        </manifest>
    """.trimIndent()

    /**
     * "Billion Laughs" — recursive entity expansion that would
     * exhaust the heap if processed. The hardening rejects the
     * DOCTYPE before any expansion starts.
     */
    private val billionLaughsManifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <!DOCTYPE manifest [
            <!ENTITY lol "lol">
            <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
            <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
        ]>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.example">
            <application android:label="&lol3;" />
        </manifest>
    """.trimIndent()

    // ── addBuildUuidToManifest path ──────────────────────────────────

    @Test fun `addBuildUuidToManifest rejects a DOCTYPE-bearing manifest`() {
        // Pre-fix, the parser would have accepted the DOCTYPE and
        // proceeded with the modification. Post-fix, the hardened
        // factory throws on parse.
        val f = writeManifest(doctypeOnlyManifest)
        val ex = assertFailsWith<Throwable> {
            ManifestModifier.addBuildUuidToManifest(f, "test-uuid")
        }
        // The JAXP parser wraps the disallow-doctype-decl rejection
        // in a SAXParseException nested anywhere up the cause chain.
        // Walk the chain and assert the message mentions DOCTYPE.
        assertTrue(
            generateSequence<Throwable>(ex) { it.cause }
                .any {
                    it.message?.contains("DOCTYPE", ignoreCase = true) == true ||
                        it is SAXParseException
                },
            "expected DOCTYPE rejection in exception chain; got: " +
                generateSequence<Throwable>(ex) { it.cause }
                    .joinToString(" → ") { "${it.javaClass.simpleName}: ${it.message}" },
        )
    }

    @Test fun `addBuildUuidToManifest rejects an XXE entity-bearing manifest`() {
        // The classic XXE shape. Hardening rejects on DOCTYPE before
        // the entity is ever consulted.
        val f = writeManifest(xxeSystemEntityManifest)
        assertFailsWith<Throwable> {
            ManifestModifier.addBuildUuidToManifest(f, "test-uuid")
        }
        // Also pin that the resulting file was NOT modified — a
        // partial write would leak the file's pre-attack content
        // through downstream tasks that read the modified manifest.
        assertFalse(
            f.readText().contains("BUILD_UUID"),
            "manifest file must NOT contain BUILD_UUID after rejected XXE attempt; got:\n${f.readText()}",
        )
    }

    // ── removeExtensionInitProviders path ────────────────────────────

    @Test fun `removeExtensionInitProviders rejects a DOCTYPE-bearing manifest`() {
        val f = writeManifest(doctypeOnlyManifest)
        assertFailsWith<Throwable> {
            ManifestModifier.removeExtensionInitProviders(f)
        }
    }

    // ── getMetaDataValue path ────────────────────────────────────────

    @Test fun `getMetaDataValue rejects a DOCTYPE-bearing manifest`() {
        val f = writeManifest(doctypeOnlyManifest)
        assertFailsWith<Throwable> {
            ManifestModifier.getMetaDataValue(f, "any.name")
        }
    }

    // ── Defense against expansion attacks ────────────────────────────

    @Test fun `Billion Laughs entity expansion is rejected before parsing succeeds`() {
        // The most-impactful denial-of-service shape: a malformed
        // recursive entity expansion that would consume gigabytes
        // of heap if processed. Pre-fix, the parser would have
        // attempted to expand the entity (with whatever
        // JDK-default expansion limits were in effect — those
        // limits exist but are not the load-bearing line of
        // defense for build-time XML). Post-fix, the DOCTYPE
        // rejection short-circuits before any expansion runs.
        val f = writeManifest(billionLaughsManifest)
        val start = System.nanoTime()
        assertFailsWith<Throwable> {
            ManifestModifier.addBuildUuidToManifest(f, "test-uuid")
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L
        // Sanity: the rejection must be cheap. A "billion laughs"
        // attack measured in seconds would indicate the hardening
        // is bypassed and the parser actually started the expansion.
        assertTrue(
            elapsedMs < 5_000L,
            "DOCTYPE rejection on Billion Laughs must be fast (<5s); took ${elapsedMs}ms",
        )
    }

    // ── Sanity: a normal manifest WITHOUT DOCTYPE still parses ───────

    @Test fun `well-formed manifest without DOCTYPE still parses successfully`() {
        // Pin that the hardening hasn't accidentally broken the
        // normal happy path — ordinary AndroidManifest.xml files
        // never have a DOCTYPE, so the hardening should be
        // invisible to them.
        val normalManifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example">
                <application android:label="X">
                    <meta-data android:name="com.bugsee.android.BUILD_UUID" android:value="placeholder" />
                </application>
            </manifest>
        """.trimIndent()
        val f = writeManifest(normalManifest)
        val ok = ManifestModifier.addBuildUuidToManifest(f, "new-uuid")
        assertTrue(
            ok,
            "well-formed DOCTYPE-free manifest must parse and inject BUILD_UUID",
        )
        assertTrue(
            f.readText().contains("new-uuid"),
            "result file must contain the new BUILD_UUID",
        )
    }

    // ── Helper-factory contract ──────────────────────────────────────

    @Test fun `secureDocumentBuilderFactory has disallow-doctype-decl set`() {
        // Direct probe of the factory the helpers route through.
        // Pin the feature is true at the factory level (each parser
        // instance inherits it). Defensive: if a future refactor
        // accidentally bypasses the helper (constructing a raw
        // `DocumentBuilderFactory.newInstance()` directly), the
        // call-site tests above catch the regression — but pinning
        // the factory itself documents the OWASP contract for
        // anyone reading the source.
        val factory = ManifestModifier.secureDocumentBuilderFactory()
        val disallow = factory.getFeature("http://apache.org/xml/features/disallow-doctype-decl")
        assertTrue(disallow, "disallow-doctype-decl must be true on the secure factory")
    }

    @Test fun `secureDocumentBuilderFactory has isXIncludeAware disabled`() {
        val factory = ManifestModifier.secureDocumentBuilderFactory()
        assertFalse(
            factory.isXIncludeAware,
            "isXIncludeAware must be false — XInclude can pull arbitrary URLs",
        )
    }

    @Test fun `secureDocumentBuilderFactory has isExpandEntityReferences disabled`() {
        val factory = ManifestModifier.secureDocumentBuilderFactory()
        assertFalse(
            factory.isExpandEntityReferences,
            "isExpandEntityReferences must be false — narrows parser behaviour to manifest needs",
        )
    }
}
