package com.bugsee.android.gradle.manifest

import java.io.File
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

internal object ManifestModifier {

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val BUILD_UUID_TAG = "com.bugsee.android.BUILD_UUID"

    /**
     * Returns a [DocumentBuilderFactory] hardened against XML External
     * Entity (XXE) attacks per OWASP guidance.
     *
     * The threat surface here is build-time only — the parsers consume
     * AndroidManifest.xml files that come from the user's own project
     * sources or AGP intermediates. A practical attack requires
     * either (a) the build author intentionally putting malicious XML
     * into their own manifest, or (b) an upstream dependency's AAR
     * manifest including a DOCTYPE / external entity. Case (b) is the
     * realistic one in a compromised-dependency scenario, where a
     * malicious library could exfiltrate local files via DTD-resolution
     * side effects when the BugseeManifestTask parses the merged
     * manifest. Defense-in-depth is cheap; turn off every feature an
     * AndroidManifest does not legitimately need.
     *
     * Wrap each `setFeature` in try/catch: not every JAXP parser
     * implementation supports every flag (XInclude support, in
     * particular, varies). Failing to set one feature should not break
     * the build — we set as many as we can and proceed.
     */
    internal fun secureDocumentBuilderFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        // Block DOCTYPE entirely. AndroidManifest.xml never legitimately
        // uses one. With doctype blocked the rest of the XXE feature
        // matrix is mostly moot — entities require a doctype to declare
        // them — but we set the remainder for belt-and-braces against
        // any non-standard parser that interprets entities outside the
        // doctype.
        runCatching {
            factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true,
            )
        }
        runCatching {
            factory.setFeature(
                "http://xml.org/sax/features/external-general-entities",
                false,
            )
        }
        runCatching {
            factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities",
                false,
            )
        }
        runCatching {
            factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                false,
            )
        }
        // XInclude can pull in arbitrary URLs even without a doctype.
        runCatching { factory.isXIncludeAware = false }
        // Expand-entity-references is a separate switch from external
        // entities; closing both narrows the parser's behaviour to
        // exactly what an Android manifest needs.
        runCatching { factory.isExpandEntityReferences = false }
        return factory
    }

    /**
     * Fully-qualified name of the core SDK init provider. We never strip
     * this one — it's the consolidation target, not a consolidation
     * source.
     */
    private const val CORE_INIT_PROVIDER_FQN = "com.bugsee.library.BugseeInitProvider"

    /**
     * Matches Bugsee extension init providers. The class name pattern is
     * `Bugsee<Name>InitProvider` regardless of subpackage (the compose
     * extension lives in `com.bugsee.library.compose`, the others under
     * `com.bugsee.library`). Anchored with `Bugsee` prefix + `InitProvider`
     * suffix so unrelated classes that happen to live in the same package
     * (e.g. `BugseeContextProvider`) do not match.
     */
    private val EXTENSION_INIT_PROVIDER_REGEX = Regex("""^.*\.Bugsee[A-Za-z0-9_]+InitProvider$""")

    /**
     * Modifies the given AndroidManifest.xml file:
     * - Removes any existing BUILD_UUID meta-data tags
     * - Injects a new BUILD_UUID meta-data tag under the <application> element
     *
     * @return true if the application element was found and modified, false otherwise
     */
    fun addBuildUuidToManifest(manifestFile: File, buildUuid: String): Boolean {
        val docFactory = secureDocumentBuilderFactory().apply {
            isNamespaceAware = true
        }
        val doc = docFactory.newDocumentBuilder().parse(manifestFile)
        doc.documentElement.normalize()

        val applicationNodes = doc.getElementsByTagName("application")
        if (applicationNodes.length == 0) return false

        val application = applicationNodes.item(0) as Element

        // Remove any old BUILD_UUID meta-data tags
        val metaDataNodes = application.getElementsByTagName("meta-data")
        val toRemove = mutableListOf<Element>()
        for (i in 0 until metaDataNodes.length) {
            val node = metaDataNodes.item(i) as Element
            val nameAttr = node.getAttributeNS(ANDROID_NS, "name")
            if (nameAttr == BUILD_UUID_TAG) {
                toRemove.add(node)
            }
        }
        for (node in toRemove) {
            application.removeChild(node)
        }

        // Add new BUILD_UUID meta-data
        val metaData = doc.createElement("meta-data")
        metaData.setAttributeNS(ANDROID_NS, "android:name", BUILD_UUID_TAG)
        metaData.setAttributeNS(ANDROID_NS, "android:value", buildUuid)
        application.appendChild(metaData)

        // Write back to file
        writeDocument(doc, manifestFile)
        return true
    }

    /**
     * Removes every `<provider>` element whose `android:name` matches a
     * Bugsee extension init provider (i.e. `*.Bugsee<Name>InitProvider`
     * other than the core SDK's own `BugseeInitProvider`).
     *
     * Returns the list of fully-qualified class names that were removed,
     * in document order. The caller uses this list to inline the
     * corresponding `register<Name>Extension()` calls into
     * `BugseeInitProvider.initializeExtensions()`.
     *
     * Always rewrites the file even when nothing was removed: callers
     * use timestamp-driven Gradle up-to-date checks and rely on the
     * output landing at a deterministic location.
     */
    fun removeExtensionInitProviders(manifestFile: File): List<String> {
        if (!manifestFile.isFile) return emptyList()

        val docFactory = secureDocumentBuilderFactory().apply {
            isNamespaceAware = true
        }
        val doc = docFactory.newDocumentBuilder().parse(manifestFile)
        doc.documentElement.normalize()

        val applicationNodes = doc.getElementsByTagName("application")
        if (applicationNodes.length == 0) return emptyList()

        val application = applicationNodes.item(0) as Element
        val providerNodes = application.getElementsByTagName("provider")

        val removed = mutableListOf<String>()
        val toRemove = mutableListOf<Element>()
        for (i in 0 until providerNodes.length) {
            val provider = providerNodes.item(i) as Element
            val name = provider.getAttributeNS(ANDROID_NS, "name")
            if (name.isEmpty() || name == CORE_INIT_PROVIDER_FQN) continue
            if (EXTENSION_INIT_PROVIDER_REGEX.matches(name)) {
                removed.add(name)
                toRemove.add(provider)
            }
        }

        if (toRemove.isEmpty()) return emptyList()

        for (node in toRemove) {
            // Remove via the node's actual parent rather than
            // hardcoding `application.removeChild(node)`. The
            // `getElementsByTagName("provider")` walk above
            // returns descendants at ANY depth (DOM Level 1
            // contract), so a `<provider>` nested inside an
            // intermediate wrapping element — possible under
            // unusual AGP manifest-merger output for build-type /
            // flavor overlays — would yield a match whose parent
            // is NOT `application`. The prior
            // `application.removeChild(node)` would throw
            // `DOMException.NOT_FOUND_ERR` in that case, taking
            // the build down with a confusing stack trace. The
            // null-safe parent-removal handles ALL parents
            // correctly and silently no-ops if the node was
            // already detached during iteration (which can't
            // currently happen but is harmless to guard against).
            node.parentNode?.removeChild(node)
        }
        writeDocument(doc, manifestFile)
        return removed
    }

    /**
     * Extracts the value of a specific meta-data tag from the manifest.
     */
    fun getMetaDataValue(manifestFile: File, metaDataName: String): String? {
        val docFactory = secureDocumentBuilderFactory().apply {
            isNamespaceAware = true
        }
        val doc = docFactory.newDocumentBuilder().parse(manifestFile)
        doc.documentElement.normalize()

        val applicationNodes = doc.getElementsByTagName("application")
        if (applicationNodes.length == 0) return null

        val application = applicationNodes.item(0) as Element
        val metaDataNodes = application.getElementsByTagName("meta-data")
        for (i in 0 until metaDataNodes.length) {
            val node = metaDataNodes.item(i) as Element
            val nameAttr = node.getAttributeNS(ANDROID_NS, "name")
            if (nameAttr == metaDataName) {
                return node.getAttributeNS(ANDROID_NS, "value")
            }
        }
        return null
    }

    /**
     * Reads the android:versionName from the manifest root element.
     */
    /**
     * Reads the package attribute from the manifest root element.
     */
    fun getPackageName(manifestFile: File): String? {
        val doc = parseManifest(manifestFile)
        return doc.documentElement.getAttribute("package").ifEmpty { null }
    }

    fun getVersionName(manifestFile: File): String? {
        val doc = parseManifest(manifestFile)
        return doc.documentElement.getAttributeNS(ANDROID_NS, "versionName").ifEmpty { null }
    }

    /**
     * Reads the android:versionCode from the manifest root element.
     */
    fun getVersionCode(manifestFile: File): String? {
        val doc = parseManifest(manifestFile)
        return doc.documentElement.getAttributeNS(ANDROID_NS, "versionCode").ifEmpty { null }
    }

    /**
     * Reads the android:icon attribute from the <application> element.
     */
    fun getApplicationIcon(manifestFile: File): String? {
        val doc = parseManifest(manifestFile)
        val applicationNodes = doc.getElementsByTagName("application")
        if (applicationNodes.length == 0) return null
        val application = applicationNodes.item(0) as Element
        return application.getAttributeNS(ANDROID_NS, "icon").ifEmpty { null }
    }

    private fun parseManifest(manifestFile: File): Document {
        val docFactory = secureDocumentBuilderFactory().apply {
            isNamespaceAware = true
        }
        val doc = docFactory.newDocumentBuilder().parse(manifestFile)
        doc.documentElement.normalize()
        return doc
    }

    private fun writeDocument(doc: Document, file: File) {
        val factory = TransformerFactory.newInstance()
        // XSLT hardening — symmetric with [secureDocumentBuilderFactory]
        // on the parsing side. While the Transformer's threat surface
        // is narrower (it serialises an already-parsed DOM, no entity
        // resolution by default), it can still honour XSLT-level
        // directives if a malicious DOM somehow injected an
        // `xsl:import-schema` / `document(...)` /
        // `system-property(...)` reference. FEATURE_SECURE_PROCESSING
        // is the JAXP-standard switch the Java specification mandates
        // every TransformerFactory honor — when true, the factory
        // imposes XSLT/XPath processing limits documented in JEP
        // (since Java 8). The ACCESS_EXTERNAL_DTD /
        // ACCESS_EXTERNAL_STYLESHEET properties further lock down
        // external-resource resolution.
        //
        // Wrap each setter in `runCatching` — the same
        // implementation-portability concern as the parser-side
        // hardening applies: not every JAXP TransformerFactory
        // supports every attribute. Falling through silently for an
        // unsupported attribute is acceptable defense-in-depth.
        // Use the literal JAXP property strings instead of
        // `XMLConstants.*` — `ACCESS_EXTERNAL_*` constants resolve
        // inconsistently across some plugin-compile classpaths.
        // The strings are part of the public JAXP contract and
        // stable since JDK 7 / 8.
        runCatching { factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true) }
        runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", "") }
        val transformer = factory.newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.ENCODING, "utf-8")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }

        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        file.writeText(writer.toString())
    }
}
