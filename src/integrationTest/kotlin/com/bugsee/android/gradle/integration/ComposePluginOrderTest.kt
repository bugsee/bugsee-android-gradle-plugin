package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #138: where the Bugsee Compose compiler plugin sits in the Kotlin compilation's plugin
 * classpath relative to the Compose compiler, in a REAL Gradle consumer (AGP + Kotlin Gradle plugin
 * resolving the published artifacts), not a hand-built `-Xplugin` list.
 *
 * Order is load-bearing on Kotlin 1.9. The androidx Compose compiler 1.5 and our k21 artifact both
 * register through the legacy `ComponentRegistrar` there, so the one listed first has its IR
 * extension run first; Compose's default-argument lowering rewrites every omitted `modifier` into a
 * `DEFAULT_VALUE` composite that Bugsee must skip, so Bugsee has to run BEFORE it to tag those sites.
 */
class ComposePluginOrderTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * The fixture pins its own toolchain (AGP 8.6 with the Kotlin line under test, which is also what
     * Compose compiler 1.5.15 needs), so the Gradle/AGP/Kotlin matrix legs cannot run it.
     */
    @Before
    fun defaultToolchainOnly() {
        assumeTrue(
            "compose-pipeline runs on the default toolchain only",
            FixtureProject.gradleVersion == null && FixtureProject.agpVersion == null &&
                FixtureProject.kotlinVersion == null,
        )
    }

    @Test
    fun kotlin_1_9_bugsee_is_ahead_of_the_compose_compiler_and_tags_omitted_modifier_sites() {
        val fixture = materialize("1.9.25", kotlin2 = false)

        val order = pluginClasspathOrder(fixture)
        assertBugseeAheadOfCompose(order)

        fixture.build(null, *composeRepoArgs())
        assertEquals(
            "Kotlin 1.9.25: omitted-modifier sites (ProdDefault) are tagged along with the explicit one",
            mapOf("ProdDefault" to 2, "ProdExplicit" to 1),
            tagCounts(fixture),
        )
    }

    // Kotlin 2.x: the Compose compiler is the bundled, modern-registered one, and this change does not
    // touch the plugin classpath or the registration path there. The order is whatever plugin
    // application order produced, and it alone decides whether omitted-modifier sites are tagged:
    // unchanged by #138 (identical results on the commit before it).
    @Test
    fun kotlin_2_1_bugsee_applied_first_tags_omitted_modifier_sites() =
        assertKotlin2("2.1.0", composeFirst = false, expected = mapOf("ProdDefault" to 2, "ProdExplicit" to 1))

    @Test
    fun kotlin_2_1_compose_applied_first_skips_omitted_modifier_sites() =
        assertKotlin2("2.1.0", composeFirst = true, expected = mapOf("ProdDefault" to 0, "ProdExplicit" to 1))

    @Test
    fun kotlin_2_2_bugsee_applied_first_tags_omitted_modifier_sites() =
        assertKotlin2("2.2.0", composeFirst = false, expected = mapOf("ProdDefault" to 2, "ProdExplicit" to 1))

    @Test
    fun kotlin_2_2_compose_applied_first_skips_omitted_modifier_sites() =
        assertKotlin2("2.2.0", composeFirst = true, expected = mapOf("ProdDefault" to 0, "ProdExplicit" to 1))

    private fun assertKotlin2(kotlin: String, composeFirst: Boolean, expected: Map<String, Int>) {
        val fixture = materialize(kotlin, kotlin2 = true)
        val flags = arrayOf("-PbugseeFixtureKotlin2=true", "-PbugseeFixtureComposeFirst=$composeFirst")
        val order = pluginClasspathOrder(fixture, *flags)
        val bugsee = order.indexOfFirst { it.startsWith("bugsee-compose-compiler-plugin") }
        val compose = order.indexOfFirst { it.startsWith("kotlin-compose-compiler-plugin") }
        assertTrue("both plugins must be on the classpath: $order", bugsee >= 0 && compose >= 0)
        assertEquals(
            "Kotlin $kotlin: the plugin classpath follows plugin application order: $order",
            composeFirst, compose < bugsee,
        )
        fixture.build(null, *composeRepoArgs(), *flags)
        assertEquals("Kotlin $kotlin (composeFirst=$composeFirst)", expected, tagCounts(fixture))
    }

    private fun materialize(kotlin: String, kotlin2: Boolean): FixtureProject {
        val fixture = FixtureProject.materialize(
            "compose-pipeline", temp.newFolder("compose-pipeline-$kotlin-${if (kotlin2) "k2" else "k1"}"),
            toolchainOverrides = false,
        )
        val root = File(fixture.projectDir, "build.gradle.kts")
        root.writeText(
            root.readText()
                .replace("@KOTLIN@", kotlin)
                .replace(
                    "// @COMPOSE_PLUGIN@",
                    if (kotlin2) """id("org.jetbrains.kotlin.plugin.compose") version "$kotlin" apply false""" else "",
                ),
        )
        return fixture
    }

    private fun composeRepoArgs(): Array<String> =
        arrayOf("-PbugseeComposePluginRepo=${System.getProperty("bugsee.testkit.composePluginRepo")}")

    private fun pluginClasspathOrder(fixture: FixtureProject, vararg extra: String): List<String> {
        val output = fixture.buildTasks(
            listOf(":app:printComposePluginOrder"), null, "-q", *composeRepoArgs(), *extra,
        ).output
        return Regex("""PLUGIN_CP\[(\d+)]=(.+)""").findAll(output)
            .sortedBy { it.groupValues[1].toInt() }
            .map { it.groupValues[2].trim() }
            .toList()
    }

    private fun assertBugseeAheadOfCompose(order: List<String>) {
        val bugsee = order.indexOfFirst { it.startsWith("bugsee-compose-compiler-plugin") }
        val compose = order.indexOfFirst { it.startsWith("compiler-1.5.15") }
        assertTrue("Bugsee compiler plugin missing from the plugin classpath: $order", bugsee >= 0)
        assertTrue("Compose compiler missing from the plugin classpath: $order", compose >= 0)
        assertTrue("Bugsee must be ahead of the Compose compiler: $order", bugsee < compose)
    }

    private fun tagCounts(fixture: FixtureProject): Map<String, Int> {
        val classFile = fixture.projectDir.resolve("app/build/tmp/kotlin-classes/debug/com/example/app/ProdKt.class")
        assertTrue("compiled fixture class missing: $classFile", classFile.isFile)
        val javap = File(File(System.getProperty("java.home"), "bin"), "javap").absolutePath
        val process = ProcessBuilder(javap, "-c", "-p", classFile.absolutePath).redirectErrorStream(true).start()
        val dump = process.inputStream.bufferedReader().readText()
        assertEquals("javap failed:\n$dump", 0, process.waitFor())
        val counts = linkedMapOf("ProdDefault" to 0, "ProdExplicit" to 0)
        var current: String? = null
        for (line in dump.lines()) {
            METHOD_HEADER.find(line)?.let { current = it.groupValues[1] }
            if (current in counts && line.contains("invokestatic") && line.contains(".bugseeTag:")) {
                counts[current!!] = counts.getValue(current!!) + 1
            }
        }
        return counts
    }

    private companion object {
        val METHOD_HEADER = Regex("""^\s{2}\S[^(]*?\b([\w$]+)\(.*;$""")
    }
}
