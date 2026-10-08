package com.bugsee.android.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drift guard for the Maven Central file set (ported from the Bugsee Android SDK).
 *
 * Central requires `.md5` and `.sha1` next to each file. Gradle 8.7 also uploads `.sha256` and
 * `.sha512` unless `org.gradle.internal.publish.checksums.insecure` is set, and checksums of the
 * `.asc` signatures too, which the sonatype publish tasks strip before uploading. This fails if
 * either mechanism is removed, which would silently put those files back (210 files per release
 * instead of 84). The filter's behaviour is covered by `buildSrc/src/test`.
 */
class MavenCentralChecksumPolicyTest {

    private val property = "systemProp.org.gradle.internal.publish.checksums.insecure=true"

    private fun read(relative: String): String {
        val f = File(relative)
        assertTrue(f.isFile, "could not locate $relative from ${File(".").absolutePath}")
        return f.readText()
    }

    @Test fun `gradle properties disables the optional Maven checksums`() {
        val lines = read("gradle.properties").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filter { it.startsWith("systemProp.org.gradle.internal.publish.checksums.insecure") }
        assertEquals(listOf(property), lines, "optional Maven checksums must stay disabled")
    }

    @Test fun `sonatype publish strips signature checksums before upload`() {
        val src = read("build.gradle.kts")
        assertTrue(src.contains("allprojects"), "the hook must cover the root and :compose-compiler-plugin")
        assertTrue(src.contains("ToSonatypeRepository"), "hook must target the sonatype publish tasks")
        assertTrue(src.contains("setRepository(localRepo)"), "the staged repo must not carry the HTTP credentials")
        assertTrue(src.contains("localRepo.setUrl(dir)"))
        assertTrue(src.contains("MavenSignatureChecksums.deleteSignatureChecksums"))
        assertTrue(src.contains("MavenSignatureChecksums.upload"))
        assertTrue(src.contains("MavenSignatureChecksums.seedSnapshotMetadata"), "snapshot metadata must be seeded")
        assertTrue(src.contains("upload(dir, destination, user, password ?: \"\", snapshot)"))
    }

    @Test fun `publish scripts do not re-enable the optional checksums`() {
        for (script in listOf("scripts/deploy.sh", "scripts/localPublish.sh")) {
            assertFalse(
                read(script).contains("org.gradle.internal.publish.checksums.insecure=false"),
                "$script must not force SHA-256/SHA-512 publication back on",
            )
        }
    }
}
