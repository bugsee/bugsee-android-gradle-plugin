package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * C12, minified half: what R8 does with instrumentation leaked into a release
 * variant that does not declare the SDK.
 *
 * A reasonable hope is that R8 simply strips the injected calls as dead code.
 * Measured (2026-08-26, AGP 8.6.0, `debugCompileOnly` SDK dependency), it does
 * that only for calls in code that is already unreachable — the injected
 * `BugseeOperationDispatcher` calls in an uncalled helper were shrunk away and
 * never surfaced. That is no help in practice, because instrumentation targets
 * `Application` / `ContentProvider` / init classes, which are exactly the
 * always-reachable ones. For those, two outcomes were observed:
 *
 *  - WITHOUT a suppression rule: the build FAILS at `:app:minifyReleaseWithR8`
 *    with `ERROR: R8: Missing class com.bugsee.library.adapters
 *    .BugseeAppStartupDispatcher (referenced from: void
 *    SampleApp.attachBaseContext(Context) and 4 other contexts)`.
 *
 *  - WITH the `-dontwarn` that AGP's own `missing_rules.txt` instructs the
 *    consumer to add: the build SUCCEEDS and ships an APK containing 53
 *    `invoke-static` call sites to `BugseeAppStartupDispatcher` and ZERO
 *    Bugsee class definitions.
 *
 * That last APK was signed, installed on an API 35 emulator and launched
 * (2026-08-26). It dies before `Application.onCreate`, inside the framework's
 * own `Application.attach`:
 *
 * ```
 * FATAL EXCEPTION: main
 * java.lang.NoClassDefFoundError: Failed resolution of:
 *     Lcom/bugsee/library/adapters/BugseeAppStartupDispatcher;
 *   at com.example.fixture.SampleApp.attachBaseContext(SourceFile:5)
 *   at android.app.Application.attach(Application.java:346)
 *   at android.app.Instrumentation.newApplication(Instrumentation.java:1244)
 *   at android.app.LoadedApk.makeApplicationInner(LoadedApk.java:1458)
 *   at android.app.ActivityThread.handleBindApplication(ActivityThread.java:6772)
 * Caused by: java.lang.ClassNotFoundException:
 *     com.bugsee.library.adapters.BugseeAppStartupDispatcher
 * ```
 *
 * A log line at the top of the launcher activity's `onCreate` never printed:
 * no application code runs at all. Unconditional, first-launch, every user.
 *
 * Both tests below assert the CORRECT post-fix behaviour. They were written
 * against the unfixed plugin and failed exactly as described above; they now
 * pass unchanged, with variant-scoped dependency detection in place.
 */
class ReleaseMinifiedGatingTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a minified release without the SDK builds cleanly`() {
        val fixture = FixtureProject.materialize("build-type-gating", temp.newFolder("bt"))
        val (ok, out) = fixture.runTasksAllowingFailure(
            listOf(":app:assembleRelease"),
            "-PbugseeFixtureMinifyRelease=true",
        )
        assertTrue(
            "R8 must not be handed references to a class this variant never declared.\n" +
                out.lineSequence().filter { "Missing class" in it }.joinToString("\n"),
            ok,
        )
    }

    @Test
    fun `a minified release APK carries no Bugsee references at all`() {
        val fixture = FixtureProject.materialize("build-type-gating", temp.newFolder("bt"))
        val (ok, out) = fixture.runTasksAllowingFailure(
            listOf(":app:assembleRelease"),
            "-PbugseeFixtureMinifyRelease=true",
            // Simulates a consumer following AGP's missing_rules.txt advice —
            // the path that turns a build error into a launch crash.
            "-PbugseeFixtureDontwarn=true",
        )
        assertTrue("release build must succeed\n${out.takeLast(2000)}", ok)

        val apk = File(fixture.projectDir, "app/build/outputs/apk/release")
            .walkTopDown().firstOrNull { it.name.endsWith(".apk") }
        assertTrue("no release APK produced", apk != null)

        val sites = disassemble(apk!!)
            .lineSequence()
            .filter { "Lcom/bugsee/" in it && "invoke-" in it }
            .toList()
        assertEquals(
            "the shipped APK references Bugsee classes that are not in it:\n" +
                sites.take(5).joinToString("\n"),
            0,
            sites.size,
        )
    }

    /** Returns dexdump disassembly, or skips the assertion if build-tools are absent. */
    private fun disassemble(apk: File): String {
        // Same SDK lookup as FixtureProject: the environment first (CI runners,
        // Linux), then the Android Studio default on macOS.
        val androidHome = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: System.getProperty("user.home") + "/Library/Android/sdk"
        val dexdump = File(androidHome, "build-tools")
            .listFiles()?.sortedBy { it.name }?.lastOrNull()?.resolve("dexdump")
        org.junit.Assume.assumeTrue(
            "dexdump not available; this assertion needs Android build-tools",
            dexdump != null && dexdump.canExecute(),
        )
        val p = ProcessBuilder(dexdump!!.absolutePath, "-d", apk.absolutePath)
            .redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText()
    }
}
