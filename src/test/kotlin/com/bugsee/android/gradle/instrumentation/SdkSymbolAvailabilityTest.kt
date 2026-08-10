package com.bugsee.android.gradle.instrumentation

import org.gradle.api.Project
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The decision layer that turns "is this class in the SDK?" into "may this lane inject?".
 *
 * Worth testing carefully because the first implementation of this shipped a build-breaking
 * regression: it resolved the variant's runtime classpath, which is the very classpath the ASM
 * transform instruments, so consumers with Android project dependencies could not resolve the
 * transform's own dependencies. The rewrite resolves a DETACHED configuration instead, and
 * treats project dependencies permissively rather than probing them.
 *
 * The repository here is a hand-built local one so the test is hermetic: no network, and no
 * dependence on whatever happens to be in the module cache.
 */
class SdkSymbolAvailabilityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val logger = Logging.getLogger(SdkSymbolAvailabilityTest::class.java)
    private val fqn = "com.bugsee.library.okhttp.BugseeOkHttpWebSockets"
    private val artifact = "bugsee-android-okhttp"

    /** Lays out `com/bugsee/<artifact>/<version>/` with a pom and a jar holding [entries]. */
    private fun repoWith(version: String, vararg entries: String): File {
        val repo = temp.newFolder("repo-$version")
        val dir = File(repo, "com/bugsee/$artifact/$version").apply { mkdirs() }
        File(dir, "$artifact-$version.pom").writeText(
            """<project><modelVersion>4.0.0</modelVersion>
               <groupId>com.bugsee</groupId><artifactId>$artifact</artifactId>
               <version>$version</version><packaging>jar</packaging></project>"""
        )
        ZipOutputStream(File(dir, "$artifact-$version.jar").outputStream()).use { zip ->
            for (e in entries) {
                zip.putNextEntry(ZipEntry(e)); zip.write(byteArrayOf(1)); zip.closeEntry()
            }
        }
        return repo
    }

    private fun projectWith(repo: File?, dependency: Any?): Project {
        val project = ProjectBuilder.builder().build()
        repo?.let { r -> project.repositories.maven { it.setUrl(r.toURI()) } }
        project.configurations.create("implementation")
        dependency?.let { project.dependencies.add("implementation", it) }
        return project
    }

    private fun availability(project: Project): Boolean =
        SdkSymbolAvailability.of(project, fqn, "test capability", logger).get()

    @Test
    fun `reports available when the resolved artifact contains the class`() {
        val repo = repoWith("9.9.9", "com/bugsee/library/okhttp/BugseeOkHttpWebSockets.class")
        val project = projectWith(repo, "com.bugsee:$artifact:9.9.9")

        assertTrue(availability(project))
    }

    @Test
    fun `reports unavailable when the resolved artifact lacks the class`() {
        val repo = repoWith("9.9.8", "com/bugsee/library/okhttp/BugseeOkHttpInterceptor.class")
        val project = projectWith(repo, "com.bugsee:$artifact:9.9.8")

        assertTrue(
            !availability(project),
            "an SDK shipping the interceptor but not the WebSocket producer must read as absent",
        )
    }

    /**
     * The permissive branch, and the one that matters most.
     *
     * No repository is configured here, so ANY attempt to resolve would fail the call. Returning
     * true therefore proves the probe short-circuited without resolving — which is exactly what
     * keeps a project dependency from dragging the target module's build into the ASM transform's
     * dependency graph, the regression this rewrite fixed.
     */
    @Test
    fun `a project dependency is permissive and resolves nothing`() {
        val root = ProjectBuilder.builder().build()
        val child = ProjectBuilder.builder().withParent(root).withName("sdk").build()
        root.configurations.create("implementation")
        root.dependencies.add("implementation", root.dependencies.project(mapOf("path" to child.path)))

        assertTrue(availability(root))
    }

    @Test
    fun `a dynamic version is permissive and resolves nothing`() {
        assertTrue(availability(projectWith(null, "com.bugsee:$artifact:7.+")))
    }

    @Test
    fun `a version-less dependency is permissive and resolves nothing`() {
        assertTrue(availability(projectWith(null, "com.bugsee:$artifact")))
    }

    @Test
    fun `no Bugsee dependency at all is permissive`() {
        assertTrue(availability(projectWith(null, null)))
    }

    /**
     * Lanes injecting core-SDK adapters must probe `bugsee-android`, not the okhttp extension.
     * Getting this wrong would read a completely unrelated artifact and answer confidently.
     */
    @Test
    fun `core adapter classes are looked up in the core SDK artifact`() {
        val repo = temp.newFolder("core-repo")
        val dir = File(repo, "com/bugsee/bugsee-android/9.9.9").apply { mkdirs() }
        File(dir, "bugsee-android-9.9.9.pom").writeText(
            """<project><modelVersion>4.0.0</modelVersion>
               <groupId>com.bugsee</groupId><artifactId>bugsee-android</artifactId>
               <version>9.9.9</version><packaging>jar</packaging></project>"""
        )
        ZipOutputStream(File(dir, "bugsee-android-9.9.9.jar").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("com/bugsee/library/adapters/BugseeLogAdapter.class"))
            zip.write(byteArrayOf(1)); zip.closeEntry()
        }
        val project = ProjectBuilder.builder().build()
        project.repositories.maven { it.setUrl(repo.toURI()) }
        project.configurations.create("implementation")
        project.dependencies.add("implementation", "com.bugsee:bugsee-android:9.9.9")

        assertTrue(
            SdkSymbolAvailability.of(
                project, "com.bugsee.library.adapters.BugseeLogAdapter", "log capture", logger
            ).get()
        )
    }
}
