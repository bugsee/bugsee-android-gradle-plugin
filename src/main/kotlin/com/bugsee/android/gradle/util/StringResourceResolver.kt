package com.bugsee.android.gradle.util

import org.gradle.api.logging.Logger
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

internal object StringResourceResolver {

    private const val STRING_RESOURCE_START = "@string/"

    fun isStringResource(value: String): Boolean = value.startsWith(STRING_RESOURCE_START)

    /**
     * Resolves a `@string/foo` resource reference against a
     * pre-resolved list of `res/` source files.
     *
     * The caller is expected to hand us the `res/` source files list
     * (pulled from `android.sourceSets.main.res.getSourceFiles()` at
     * task-configuration time). Reading it at task-execution time
     * via `project.extensions.findByName("android")` would be a
     * configuration-cache violation — `project` is explicitly not
     * available when a task replays from the CC-stored graph.
     */
    fun resolve(
        sourceFiles: Iterable<File>,
        resourceIdString: String,
        logger: Logger,
        debug: Boolean,
    ): String? {
        val resourceId = resourceIdString.substring(STRING_RESOURCE_START.length)
        if (resourceId.isEmpty()) {
            logger.warn("Invalid string resource name specified: $resourceIdString")
            return null
        }

        if (debug) logger.warn("resourceId: $resourceId")

        val stringResourceFiles = sourceFiles.filter { it.name == "strings.xml" }

        val docBuilderFactory = DocumentBuilderFactory.newInstance()
        for (xmlFile in stringResourceFiles) {
            val doc = docBuilderFactory.newDocumentBuilder().parse(xmlFile)
            val strings = doc.getElementsByTagName("string")
            for (i in 0 until strings.length) {
                val node = strings.item(i)
                if (node.attributes.getNamedItem("name")?.nodeValue == resourceId) {
                    return node.textContent
                }
            }
        }

        logger.warn("Could not find $resourceIdString string resource")
        return null
    }
}
