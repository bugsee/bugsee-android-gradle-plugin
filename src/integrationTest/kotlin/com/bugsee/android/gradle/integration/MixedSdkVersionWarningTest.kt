package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #93: when the variant runtime classpath resolves with `com.bugsee:*` modules on different
 * versions, the plugin warns once, naming the modules; the build still succeeds and the warning
 * does not require configuration-time resolution (so it is configuration-cache safe).
 */
class MixedSdkVersionWarningTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val marker = "resolves mixed Bugsee SDK versions"

    /** Adds `com.bugsee:bugsee-android-okhttp:<version>` (pom-only) from a local repo to the debug runtime. */
    private fun withExtension(fixture: FixtureProject, version: String) {
        val repo = temp.newFolder("mixed-repo")
        val dir = File(repo, "com/bugsee/bugsee-android-okhttp/$version").apply { mkdirs() }
        File(dir, "bugsee-android-okhttp-$version.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.bugsee</groupId>
              <artifactId>bugsee-android-okhttp</artifactId>
              <version>$version</version>
              <packaging>pom</packaging>
            </project>
            """.trimIndent()
        )
        val settings = File(fixture.projectDir, "settings.gradle.kts")
        val before = settings.readText()
        val after = before.replace(
            "        google()\n        mavenCentral()\n    }\n}\n\nrootProject",
            "        maven { url = uri(\"${repo.path.replace(File.separatorChar, '/')}\") }\n" +
                "        google()\n        mavenCentral()\n    }\n}\n\nrootProject",
        )
        assertTrue("settings anchor not found", before != after)
        settings.writeText(after)
        File(fixture.projectDir, "app/build.gradle.kts")
            .appendText("\ndependencies { debugImplementation(\"com.bugsee:bugsee-android-okhttp:$version\") }\n")
    }

    @Test
    fun `mixed versions warn once naming both modules and do not fail the build`() {
        val fixture = FixtureProject.materialize("build-type-gating", temp.newFolder("bt"))
        withExtension(fixture, "98.0.0")
        val out = fixture.buildTasks(listOf(":app:assembleDebug"), null, "--configuration-cache").output
        assertTrue("build must succeed\n${out.takeLast(2000)}", out.contains("BUILD SUCCESSFUL"))
        assertEquals("exactly one warning\n$out", 1, out.split(marker).size - 1)
        assertTrue(out, "com.bugsee:bugsee-android:99.0.0" in out)
        assertTrue(out, "com.bugsee:bugsee-android-okhttp:98.0.0" in out)
        assertTrue(out, "variant debug (module :app)" in out)
    }

    @Test
    fun `configuring without resolving the classpath does not warn`() {
        val fixture = FixtureProject.materialize("build-type-gating", temp.newFolder("bt"))
        withExtension(fixture, "98.0.0")
        val out = fixture.buildTasks(listOf(":app:help")).output
        assertTrue("build must succeed\n${out.takeLast(2000)}", out.contains("BUILD SUCCESSFUL"))
        assertFalse("must not resolve at configuration time\n$out", marker in out)
    }

    private val floorMarker = "extension provider stripping"

    private fun withOldDeclaredSdk(fixture: FixtureProject) {
        val app = File(fixture.projectDir, "app/build.gradle.kts")
        val before = app.readText()
        val after = before.replace("com.bugsee:bugsee-android:99.0.0", "com.bugsee:bugsee-android:7.0.0-beta10")
        assertTrue("fixture SDK declaration not found", before != after)
        app.writeText(after)
    }

    @Test
    fun `declared SDK below the extensions-init floor warns once across variants`() {
        val fixture = FixtureProject.materialize("build-type-gating", temp.newFolder("bt"))
        withOldDeclaredSdk(fixture)
        val out = fixture.buildTasks(listOf(":app:help")).output
        assertTrue("build must succeed\n${out.takeLast(2000)}", out.contains("BUILD SUCCESSFUL"))
        assertEquals("exactly one warning\n$out", 1, out.split(floorMarker).size - 1)
        assertTrue(out, "7.0.0-beta10" in out)
    }

    @Test
    fun `below-floor warning is silent when optimizeExtensionsLoading is off or instrumentation is disabled`() {
        val fixture = FixtureProject.materialize("build-type-gating", temp.newFolder("bt"))
        withOldDeclaredSdk(fixture)
        val off = fixture.buildTasks(listOf(":app:help"), null, "-Pbugsee.instrumentation.enabled=false").output
        assertFalse(off, floorMarker in off)
        File(fixture.projectDir, "app/build.gradle.kts")
            .appendText("\nbugsee { optimizeExtensionsLoading.set(false) }\n")
        val noOpt = fixture.buildTasks(listOf(":app:help")).output
        assertFalse(noOpt, floorMarker in noOpt)
    }

    @Test
    fun `aligned versions do not warn`() {
        val fixture = FixtureProject.materialize("build-type-gating", temp.newFolder("bt"))
        withExtension(fixture, "99.0.0")
        val out = fixture.buildTasks(listOf(":app:assembleDebug")).output
        assertTrue("build must succeed\n${out.takeLast(2000)}", out.contains("BUILD SUCCESSFUL"))
        assertFalse(out, marker in out)
    }
}
