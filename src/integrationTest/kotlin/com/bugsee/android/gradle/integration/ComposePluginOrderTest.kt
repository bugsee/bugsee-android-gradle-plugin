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
            mapOf("ProdDefault" to 2, "ProdExplicit" to 1, "ProdCustomDefault" to 0),
            tagCounts(fixture),
        )
        assertEquals("Kotlin 1.9.25: every real library text field is secured (issues #149, #151)", SECURE_ALL, secureCounts(fixture))
    }

    // Kotlin 2.x: the Compose compiler is the bundled, modern-registered one. The order is whatever
    // plugin application order produced, and it alone decides whether omitted-modifier sites are
    // tagged. ProdCustomDefault (callee default is not the bare Modifier) is never tagged (issue
    // #146: the tag would have replaced the callee's own default when Bugsee runs first).
    @Test
    fun kotlin_2_1_bugsee_applied_first_tags_omitted_modifier_sites() =
        assertKotlin2("2.1.0", composeFirst = false, expected = mapOf("ProdDefault" to 2, "ProdExplicit" to 1, "ProdCustomDefault" to 0))

    @Test
    fun kotlin_2_1_compose_applied_first_skips_omitted_modifier_sites() =
        assertKotlin2("2.1.0", composeFirst = true, expected = mapOf("ProdDefault" to 0, "ProdExplicit" to 1, "ProdCustomDefault" to 0))

    @Test
    fun kotlin_2_2_bugsee_applied_first_tags_omitted_modifier_sites() =
        assertKotlin2("2.2.0", composeFirst = false, expected = mapOf("ProdDefault" to 2, "ProdExplicit" to 1, "ProdCustomDefault" to 0))

    @Test
    fun kotlin_2_2_compose_applied_first_skips_omitted_modifier_sites() =
        assertKotlin2("2.2.0", composeFirst = true, expected = mapOf("ProdDefault" to 0, "ProdExplicit" to 1, "ProdCustomDefault" to 0))

    // Issue #158: real foundation 1.7.8 TextFieldState-based fields (no VisualTransformation parameter) are intentionally
    // not matched by the compile-time pass; the String overload on the same version is the positive control. This pins
    // existing behavior (it passes on main too). Kotlin 2.x only, see :app17.
    @Test
    fun kotlin_2_1_foundation_1_7_state_based_text_fields_are_not_secured_at_compile_time() {
        val fixture = materialize("2.1.0", kotlin2 = true)
        fixture.buildTasks(listOf(":app17:compileDebugKotlin"), null, *composeRepoArgs(), "-PbugseeFixtureKotlin2=true", "-PbugseeFixtureApp17=true")
        assertEquals(
            "foundation 1.7.8: state-based BasicTextField and BasicSecureTextField are left to the runtime path (issue #158)",
            STATE_BASED,
            countCalls(fixture, ".bugseeSecure:", STATE_BASED.keys.toList(), "app17/build/tmp/kotlin-classes/debug/com/example/app17/Prod17Kt.class"),
        )
    }

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
        assertEquals("Kotlin $kotlin (composeFirst=$composeFirst): every real library text field is secured", SECURE_ALL, secureCounts(fixture))
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

    private fun tagCounts(fixture: FixtureProject): Map<String, Int> =
        countCalls(fixture, ".bugseeTag:", listOf("ProdDefault", "ProdExplicit", "ProdCustomDefault"))

    /** `bugseeSecure` call count per real-library text-field composable of the fixture. */
    private fun secureCounts(fixture: FixtureProject): Map<String, Int> =
        countCalls(fixture, ".bugseeSecure:", SECURE_ALL.keys.toList())

    private fun countCalls(
        fixture: FixtureProject,
        callSuffix: String,
        names: List<String>,
        classPath: String = "app/build/tmp/kotlin-classes/debug/com/example/app/ProdKt.class",
    ): Map<String, Int> {
        val classFile = fixture.projectDir.resolve(classPath)
        assertTrue("compiled fixture class missing: $classFile", classFile.isFile)
        val javap = File(File(System.getProperty("java.home"), "bin"), "javap").absolutePath
        val process = ProcessBuilder(javap, "-c", "-p", classFile.absolutePath).redirectErrorStream(true).start()
        val dump = process.inputStream.bufferedReader().readText()
        assertEquals("javap failed:\n$dump", 0, process.waitFor())
        val counts = linkedMapOf<String, Int>().apply { names.forEach { put(it, 0) } }
        val seen = mutableSetOf<String>()
        var current: String? = null
        for (line in dump.lines()) {
            METHOD_HEADER.find(line)?.let { current = it.groupValues[1]; seen += it.groupValues[1] }
            if (current in counts && line.contains("invokestatic") && line.contains(callSuffix)) {
                counts[current!!] = counts.getValue(current!!) + 1
            }
        }
        // A zero-expected name that never appears in the dump (renamed, not emitted) must not pass vacuously.
        assertTrue("fixture methods missing from the javap dump: ${names - seen}", seen.containsAll(names))
        return counts
    }

    private companion object {
        val SECURE_ALL = linkedMapOf(
            "ProdSecureMaterial" to 1, "ProdSecureMaterialOutlined" to 1, "ProdSecureMaterial3" to 1,
            "ProdSecureMaterial3Outlined" to 1, "ProdSecureBasic" to 1,
            // Real foundation 1.6.8 text2 entry points (issue #155): no VisualTransformation parameter, never secured.
            "ProdText2Value" to 0, "ProdText2State" to 0, "ProdText2Secure" to 0,
            // Same-module non-androidx look-alike: never secured.
            "ProdSecureFake" to 0,
        )
        // :app17, real foundation 1.7.8 (issue #158); the String overload is the live-pass control.
        val STATE_BASED = linkedMapOf(
            "ProdStateBasic" to 0, "ProdStateBasicMasked" to 0, "ProdStateSecure" to 0, "ProdValueSecure" to 1,
        )
        val METHOD_HEADER = Regex("""^\s{2}\S[^(]*?\b([\w$]+)\(.*;$""")
    }
}
