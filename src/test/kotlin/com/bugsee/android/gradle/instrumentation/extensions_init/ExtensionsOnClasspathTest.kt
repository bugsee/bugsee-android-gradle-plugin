package com.bugsee.android.gradle.instrumentation.extensions_init

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [ExtensionsOnClasspath] against REAL Gradle dependency resolution (a local repository of POM-only
 * modules), not mocked graph objects: what matters is which components a resolved graph contains.
 */
class ExtensionsOnClasspathTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ndk = "com.bugsee.library.BugseeNdkInitProvider"
    private val feedback = "com.bugsee.library.BugseeFeedbackInitProvider"
    private val okhttp = "com.bugsee.library.BugseeOkHttpInitProvider"

    @Test
    fun `an extension module declared directly is reported`() {
        publish("com.bugsee", "bugsee-android-ndk", "7.5.0")
        assertEquals(setOf(ndk), providers("com.bugsee:bugsee-android-ndk:7.5.0"))
    }

    @Test
    fun `an extension reached only through other modules is reported`() {
        publish("com.bugsee", "bugsee-android-ndk", "7.5.0")
        publish("com.acme", "wrapper-inner", "1.0", deps = listOf("com.bugsee:bugsee-android-ndk:7.5.0"))
        publish("com.acme", "wrapper", "1.0", deps = listOf("com.acme:wrapper-inner:1.0"))
        assertEquals(setOf(ndk), providers("com.acme:wrapper:1.0"))
    }

    @Test
    fun `the core SDK alone is no extension`() {
        publish("com.bugsee", "bugsee-android", "7.5.0")
        assertEquals(emptySet<String>(), providers("com.bugsee:bugsee-android:7.5.0"))
    }

    @Test
    fun `several extensions come back in the table order, not the declaration order`() {
        publish("com.bugsee", "bugsee-android-okhttp", "7.5.0")
        publish("com.bugsee", "bugsee-android-ndk", "7.5.0")
        publish("com.bugsee", "bugsee-android-feedback", "7.5.0")
        val found = providers(
            "com.bugsee:bugsee-android-okhttp:7.5.0",
            "com.bugsee:bugsee-android-ndk:7.5.0",
            "com.bugsee:bugsee-android-feedback:7.5.0",
        )
        assertEquals(listOf(feedback, ndk, okhttp), found.toList())
    }

    @Test
    fun `a module of the same name under another group is not an extension`() {
        publish("com.acme", "bugsee-android-ndk", "1.0")
        assertEquals(emptySet<String>(), providers("com.acme:bugsee-android-ndk:1.0"))
    }

    @Test
    fun `nothing on the classpath is nothing`() {
        assertEquals(emptySet<String>(), providers())
    }

    @Test
    fun `every table entry has a distinct artifact id and provider`() {
        val specs = ExtensionSpec.KNOWN
        assertEquals(specs.size, specs.map { it.artifactId }.toSet().size)
        assertEquals(specs.size, specs.map { it.initProviderFqn }.toSet().size)
        specs.forEach { assert(it.artifactId.startsWith("bugsee-android-")) { "unexpected artifact id ${it.artifactId}" } }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private val repo by lazy { tmp.newFolder("repo") }

    private fun publish(group: String, name: String, version: String, deps: List<String> = emptyList()) {
        val dir = File(repo, "${group.replace('.', '/')}/$name/$version").also { it.mkdirs() }
        val depXml = deps.joinToString("\n") { d ->
            val (g, n, v) = d.split(':')
            "<dependency><groupId>$g</groupId><artifactId>$n</artifactId><version>$v</version><scope>compile</scope></dependency>"
        }
        File(dir, "$name-$version.pom").writeText(
            """<project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>$name</artifactId>
               <version>$version</version><dependencies>$depXml</dependencies></project>""",
        )
    }

    private fun providers(vararg coordinates: String): Set<String> {
        val project: Project = ProjectBuilder.builder().build()
        project.repositories.maven { r ->
            r.setUrl(repo.toURI())
            r.metadataSources { it.mavenPom(); it.artifact() }
        }
        val config = project.configurations.create("probe")
        coordinates.forEach { project.dependencies.add("probe", it) }
        return ExtensionsOnClasspath.providersFor(config).get()
    }
}
