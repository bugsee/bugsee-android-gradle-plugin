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
        val docFactory = DocumentBuilderFactory.newInstance().apply {
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

        val docFactory = DocumentBuilderFactory.newInstance().apply {
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
            application.removeChild(node)
        }
        writeDocument(doc, manifestFile)
        return removed
    }

    /**
     * Extracts the value of a specific meta-data tag from the manifest.
     */
    fun getMetaDataValue(manifestFile: File, metaDataName: String): String? {
        val docFactory = DocumentBuilderFactory.newInstance().apply {
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
        val docFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = docFactory.newDocumentBuilder().parse(manifestFile)
        doc.documentElement.normalize()
        return doc
    }

    private fun writeDocument(doc: Document, file: File) {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.ENCODING, "utf-8")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
            // Preserve the XML declaration if present
            val xmlDecl = doc.xmlStandalone
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }

        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        file.writeText(writer.toString())
    }
}
