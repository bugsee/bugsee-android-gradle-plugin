package com.bugsee.android.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Drift guard for the build heap (#101). The Gradle and Kotlin daemon defaults intermittently fail
 * with "GC overhead limit exceeded" in `:compose-compiler-plugin:compileKotlin` and
 * `compileTestKotlin`, so `gradle.properties` must keep both heaps at 4g or more.
 */
class BuildHeapPolicyTest {

    private fun heapMb(key: String): Int {
        val f = File("gradle.properties")
        assertTrue(f.isFile, "could not locate gradle.properties from ${File(".").absolutePath}")
        val props = java.util.Properties().also { p -> f.reader().use { p.load(it) } }
        val value = props.getProperty(key) ?: error("$key is not set in gradle.properties")
        val m = Regex("""-Xmx(\d+)([mMgG])""").findAll(value).lastOrNull() // the JVM honours the last -Xmx
            ?: error("$key has no -Xmx: $value")
        val n = m.groupValues[1].toInt()
        return if (m.groupValues[2].lowercase() == "g") n * 1024 else n
    }

    @Test fun `gradle daemon heap is at least 4g`() {
        heapMb("org.gradle.jvmargs").let { assertTrue(it >= 4096, "org.gradle.jvmargs heap is ${it}m, need >= 4096m") }
    }

    @Test fun `kotlin daemon heap is at least 4g`() {
        heapMb("kotlin.daemon.jvmargs").let { assertTrue(it >= 4096, "kotlin.daemon.jvmargs heap is ${it}m, need >= 4096m") }
    }
}
