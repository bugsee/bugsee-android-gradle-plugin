package com.bugsee.android.gradle.instrumentation

import java.io.File
import java.util.zip.ZipFile

/**
 * Answers "does the SDK on this build's classpath actually contain class X?" by looking inside
 * the resolved artifacts, rather than inferring it from a declared version string.
 *
 * Why not the obvious alternatives:
 *
 *  - **`ClassContext.loadClassData`** — unusable. It was removed from every call-site factory
 *    because it is unreliable across AGP's artifact-transform isolation boundary: a class from a
 *    third-party JAR is transformed on a classpath that cannot see the consumer's SDK
 *    dependency, so the probe returns null spuriously. Gating on it produces *random*
 *    degradation dressed up as graceful degradation.
 *  - **Version comparison** — works, but only when a version is readable. Ranges (`7.+`),
 *    platform/BOM-managed versions and project dependencies all yield nothing, and a version
 *    number cannot detect a changed method signature on a class that does exist.
 *
 * Reading the artifacts is truthful by construction: it answers the question the injected
 * bytecode will actually ask of the runtime classpath.
 */
internal object SdkClassProbe {

    /**
     * Whether [internalName] (JVM internal form, e.g. `com/bugsee/library/okhttp/Foo`) is defined
     * in any of [artifacts].
     *
     * Accepts both shapes a resolved Android classpath contains: plain `.jar`s, and `.aar`s whose
     * classes live in a nested `classes.jar`. Anything unreadable is skipped rather than thrown —
     * a corrupt or exotic artifact must not fail the consumer's build over an optional capability.
     */
    fun containsClass(artifacts: Iterable<File>, internalName: String): Boolean {
        val entry = "$internalName.class"
        for (artifact in artifacts) {
            if (!artifact.isFile) continue
            val name = artifact.name.lowercase()
            val found = when {
                name.endsWith(".jar") -> jarHasEntry(artifact, entry)
                name.endsWith(".aar") -> aarHasEntry(artifact, entry)
                else -> false
            }
            if (found) return true
        }
        return false
    }

    private fun jarHasEntry(jar: File, entry: String): Boolean = try {
        ZipFile(jar).use { it.getEntry(entry) != null }
    } catch (_: Throwable) {
        false
    }

    /**
     * An AAR keeps its bytecode in a nested `classes.jar`, so the entry cannot be read directly.
     * The nested jar is streamed into memory (they are small — the okhttp extension's is ~45 KB)
     * rather than extracted to disk, keeping the probe free of temp-file lifecycle concerns.
     */
    private fun aarHasEntry(aar: File, entry: String): Boolean = try {
        ZipFile(aar).use { zip ->
            val classesJar = zip.getEntry("classes.jar") ?: return false
            zip.getInputStream(classesJar).use { input ->
                java.util.zip.ZipInputStream(input).use { zis ->
                    generateSequence { zis.nextEntry }.any { it.name == entry }
                }
            }
        }
    } catch (_: Throwable) {
        false
    }
}
