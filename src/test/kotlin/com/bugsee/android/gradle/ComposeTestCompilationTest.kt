package com.bugsee.android.gradle

import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation

/** Which Kotlin compilations the Compose tag/secure compiler plugin must stay out of (#56). */
class ComposeTestCompilationTest {

    private fun isTest(name: String, associated: Boolean = false) =
        ComposeTestCompilation.isTest(name) { associated }

    @Test
    fun `reserved test names are tests with or without an association`() {
        listOf("test", "androidTest", "unitTest", "testFixtures").forEach {
            assertTrue(isTest(it), it)
        }
        listOf("testDebug", "testFreeRelease", "androidTestDebug", "androidTestFreeDebug",
            "debugTestFixtures", "releaseTestFixtures").forEach {
            assertTrue(isTest(it), it)
        }
    }

    @Test
    fun `associated test-suffixed compilations are tests`() {
        listOf("debugUnitTest", "releaseUnitTest", "freeDebugAndroidTest", "releaseAndroidTest",
            "jvmTest", "androidUnitTest", "androidInstrumentedTest", "commonTest").forEach {
            assertTrue(isTest(it, associated = true), it)
        }
    }

    @Test
    fun `production variants stay applicable`() {
        listOf("debug", "release", "staging", "freeDebug", "paidRelease", "main",
            "androidMain", "commonMain", "jvm", "latestTestRelease", "testimonialDebug")
            .forEach { assertFalse(isTest(it, associated = true), it) }
    }

    /** A build type called `smokeTest` is a production compilation; only association can tell. */
    @Test
    fun `a build type merely ending in Test is production unless associated`() {
        assertFalse(isTest("smokeTest", associated = false))
        assertFalse(isTest("freeSmokeTest", associated = false))
        assertEquals(true, isTest("smokeTest", associated = true))
    }

    private fun compilation(name: String, associated: () -> Set<Any>): KotlinCompilation<*> =
        Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(KotlinCompilation::class.java)
        ) { _, m, _ ->
            when (m.name) {
                "getCompilationName" -> name
                "getAllAssociatedCompilations" -> associated()
                else -> throw UnsupportedOperationException(m.name)
            }
        } as KotlinCompilation<*>

    @Test
    fun `real compilation objects are classified by name and association`() {
        assertTrue(ComposeTestCompilation.isTest(compilation("debugUnitTest") { setOf(Any()) }))
        assertFalse(ComposeTestCompilation.isTest(compilation("debug") { emptySet() }))
        assertFalse(ComposeTestCompilation.isTest(compilation("smokeTest") { emptySet() }))
    }

    @Test
    fun `a failing association lookup is treated as not associated`() {
        assertFalse(ComposeTestCompilation.isTest(compilation("smokeTest") { error("boom") }))
    }
}
