package com.bugsee.android.gradle.instrumentation.okhttp

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The version gate that decides whether `newWebSocket` call sites may be rewritten.
 *
 * Plugin and SDK are released independently, so this is the only thing standing between a
 * new plugin paired with an older SDK and a `NoClassDefFoundError` at the host app's own
 * call site — the injected `INVOKESTATIC` lives in application code, where no SDK code path
 * can absorb the failure.
 *
 * The visitor-level half of the gate (flag off ⇒ no rewrite, interceptor unaffected) is
 * covered in [OkHttpClassVisitorTest]; this covers the decision itself.
 */
class OkHttpWebSocketGateTest {

    private fun projectWith(dependencyNotation: String?): Project {
        val project = ProjectBuilder.builder().build()
        project.configurations.create("implementation")
        if (dependencyNotation != null) {
            project.dependencies.add("implementation", dependencyNotation)
        }
        return project
    }

    private fun resolve(dependencyNotation: String?): Boolean =
        OkHttpInstrumentation().resolveWebSocketCapture(projectWith(dependencyNotation))

    @Test
    fun `an extension older than the class ships is refused`() {
        assertFalse(
            resolve("com.bugsee:bugsee-android-okhttp:7.0.5"),
            "7.0.5 predates BugseeOkHttpWebSockets in the published AAR, so rewriting would " +
                "hand the app a call to a class it does not have",
        )
    }

    @Test
    fun `the first version that ships the class is accepted`() {
        assertTrue(resolve("com.bugsee:bugsee-android-okhttp:7.1.0"))
    }

    @Test
    fun `a newer extension is accepted`() {
        assertTrue(resolve("com.bugsee:bugsee-android-okhttp:7.2.3"))
    }

    /**
     * A pre-release of the same line does NOT yet carry the class — semver ranks
     * `7.1.0-beta1` below `7.1.0`, and the keep rule landed for the stable release.
     */
    @Test
    fun `a pre-release of the target version is refused`() {
        assertFalse(resolve("com.bugsee:bugsee-android-okhttp:7.1.0-beta1"))
    }

    /**
     * The permissive half, and the reason this is a gate rather than a hard requirement:
     * plugin and SDK versions cannot be assumed to line up, and the version is frequently
     * unreadable at configuration time. Refusing on "unknown" would silently disable
     * WebSocket capture for every consumer using a range, a version catalog that has not
     * resolved yet, or a composite/project dependency — including this repo's own sample
     * app. We stand down only when the version is parseable AND provably too old.
     */
    @Test
    fun `an unparseable version is treated permissively`() {
        assertTrue(resolve("com.bugsee:bugsee-android-okhttp:7.+"))
    }

    @Test
    fun `a missing version is treated permissively`() {
        assertTrue(resolve("com.bugsee:bugsee-android-okhttp"))
    }

    @Test
    fun `no declared extension at all is treated permissively`() {
        // shouldApply() has already established the extension is present by the time this
        // runs; reaching here without a readable version means the same "unknown" case.
        assertTrue(resolve(null))
    }
}
