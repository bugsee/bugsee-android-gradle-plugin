package com.bugsee.android.gradle.util

import org.gradle.api.Project
import org.gradle.api.logging.Logger
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

internal object StringResourceResolver {

    private const val STRING_RESOURCE_START = "@string/"

    fun isStringResource(value: String): Boolean = value.startsWith(STRING_RESOURCE_START)

    /**
     * Resolves a @string/ resource reference from the project's strings.xml files.
     */
    fun resolve(project: Project, resourceIdString: String, logger: Logger, debug: Boolean): String? {
        val resourceId = resourceIdString.substring(STRING_RESOURCE_START.length)
        if (resourceId.isEmpty()) {
            logger.warn("Invalid string resource name specified: $resourceIdString")
            return null
        }

        if (debug) logger.warn("resourceId: $resourceId")

        val android = project.extensions.findByName("android") ?: return null
        val sourceSets = android.javaClass.getMethod("getSourceSets").invoke(android)
        val mainSourceSet = sourceSets.javaClass.getMethod("getByName", String::class.java).invoke(sourceSets, "main")
        val res = mainSourceSet.javaClass.getMethod("getRes").invoke(mainSourceSet)
        @Suppress("UNCHECKED_CAST")
        val sourceFiles = res.javaClass.getMethod("getSourceFiles").invoke(res) as Iterable<File>

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
