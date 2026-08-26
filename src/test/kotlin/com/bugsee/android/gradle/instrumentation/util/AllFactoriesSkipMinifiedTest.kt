package com.bugsee.android.gradle.instrumentation.util

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

        val missing = factories.filterNot { it.readText().contains("MinifiedClassSkip.shouldSkip") }

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
}
