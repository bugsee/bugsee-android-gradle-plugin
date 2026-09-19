package com.bugsee.android.gradle.instrumentation.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Guards the ONE property that makes the R8 skip trustworthy: **every** class-visitor
 * factory consults it.
 *
 * There is no shared factory base class — each lane implements
 * `AsmClassVisitorFactory` directly — so wiring the skip is a per-file edit, and a lane
 * that is missed fails silently: it keeps instrumenting R8-optimised third-party classes
 * and produces exactly the `VerifyError` / dexing failures the skip exists to prevent,
 * with nothing to indicate which lane is responsible.
 *
 * This test therefore enumerates the factories from the source tree rather than from a
 * hand-maintained list, so a NEW lane added later is covered the day it appears instead
 * of the day someone remembers to add it here.
 */
class AllFactoriesSkipMinifiedTest {

    @Test
    fun `every ClassVisitorFactory consults the minified-class skip`() {
        val root = File("src/main/kotlin/com/bugsee/android/gradle/instrumentation")
        assertTrue("instrumentation source root not found: ${root.absolutePath}", root.isDirectory)

        val factories = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith("ClassVisitorFactory.kt") }
            .toList()

        assertTrue("expected to find factories under $root", factories.isNotEmpty())

        val missing = factories
            .filterNot { it.name in EXEMPT }
            .filterNot { it.readText().contains("MinifiedClassSkip.shouldSkip") }

        if (missing.isNotEmpty()) {
            fail(
                "these factories do not consult MinifiedClassSkip, so they would still " +
                    "instrument R8-minified classes:\n" +
                    missing.joinToString("\n") { "  - ${it.name}" } +
                    "\n\nAdd at the top of createClassVisitor:\n" +
                    "    if (MinifiedClassSkip.shouldSkip(nextClassVisitor)) return nextClassVisitor"
            )
        }
    }

    /**
     * Regression (plugin 4.0.6): the ExtensionsInit lane consulted the skip, and the
     * published SDK's `BugseeInitProvider` always carries the R8 marker, so the lane
     * never ran while the manifest task still stripped every extension provider.
     */
    @Test
    fun `lanes that target the SDK's own classes never consult the skip`() {
        val root = File("src/main/kotlin/com/bugsee/android/gradle/instrumentation")
        for (name in EXEMPT) {
            val file = root.walkTopDown().singleOrNull { it.name == name }
                ?: return fail("exempt factory $name no longer exists; drop it from EXEMPT")
            assertFalse(
                "$name targets a class in the R8-processed Bugsee SDK, so the minified skip " +
                    "would match it on every real build",
                file.readText().contains("MinifiedClassSkip.shouldSkip"),
            )
        }
    }

    private companion object {
        /** Factories whose only target is a Bugsee SDK class, which is always R8-processed when published. */
        val EXEMPT = setOf("ExtensionsInitClassVisitorFactory.kt")
    }
}
