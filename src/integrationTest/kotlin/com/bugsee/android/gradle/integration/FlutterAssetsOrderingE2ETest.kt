package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * Issue #33: a Flutter-style task that writes into AGP's merged-assets dir
 * (named `copyFlutterAssetsDebug`) must not make the build-id injection task
 * fail Gradle task validation, and the build-id asset must still be produced.
 */
class FlutterAssetsOrderingE2ETest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun flutter_style_asset_copy_does_not_fail_validation() {
        val fixture = FixtureProject.materialize("app-startup-tracing", temp.newFolder("flutter"))

        val result = fixture.build(
            tier = "STANDARD",
            "-PbugseeFixtureFakeFlutter=true",
        )

        assertTrue(
            "inject task must run after the Flutter copy",
            result.output.indexOf(":app:copyFlutterAssetsDebug") in 0 until
                result.output.indexOf(":app:injectBugseeDebugBuildId"),
        )
        val injected = File(fixture.projectDir, "app/build/intermediates/assets/debug/injectBugseeDebugBuildId")
        assertTrue(
            "bugsee_build_id.properties asset must exist",
            File(injected, "bugsee_build_id.properties").isFile,
        )
        assertTrue(
            "Flutter's asset must survive injection (a late copy would drop it)",
            File(injected, "flutter_marker.txt").isFile,
        )
        val apk = File(fixture.projectDir, "app/build/outputs/apk/debug")
            .listFiles { f -> f.extension == "apk" }!!.single()
        ZipFile(apk).use { zip ->
            assertTrue("build-id asset must be in the APK", zip.getEntry("assets/bugsee_build_id.properties") != null)
            assertTrue("Flutter's asset must be in the APK", zip.getEntry("assets/flutter_marker.txt") != null)
        }
    }
}
