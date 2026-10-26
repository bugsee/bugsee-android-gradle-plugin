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

    @Test
    fun renamed_flutter_task_is_ordered_through_assetsDependsOn() {
        val fixture = FixtureProject.materialize("app-startup-tracing", temp.newFolder("renamed"))

        val result = fixture.build(
            tier = "STANDARD",
            "-PbugseeFixtureFakeFlutter=true",
            "-PbugseeFixtureFakeFlutterTaskName=flutterCopyAssetsDebug",
            "-PbugseeFixtureAssetsDependsOn=flutterCopyAssets<Variant>",
        )

        assertTrue(
            "inject task must run after the renamed copy",
            result.output.indexOf(":app:flutterCopyAssetsDebug") in 0 until
                result.output.indexOf(":app:injectBugseeDebugBuildId"),
        )
    }

    @Test
    fun renamed_flutter_task_without_the_setting_still_fails_validation() {
        val fixture = FixtureProject.materialize("app-startup-tracing", temp.newFolder("renamed-unset"))

        val result = fixture.buildAndFail(
            tier = "STANDARD",
            "-PbugseeFixtureFakeFlutter=true",
            "-PbugseeFixtureFakeFlutterTaskName=flutterCopyAssetsDebug",
        )

        assertTrue(
            "documents why the setting exists",
            result.output.contains("without declaring an explicit or implicit dependency"),
        )
    }

    @Test
    fun unknown_assetsDependsOn_name_warns_and_a_valid_flutter_setup_is_quiet() {
        val bad = FixtureProject.materialize("app-startup-tracing", temp.newFolder("typo"))
        val badResult = bad.build(tier = "STANDARD", "-PbugseeFixtureAssetsDependsOn=typo<Variant>")
        assertTrue(
            "unmatched name must be reported",
            badResult.output.contains("assetsDependsOn(\"typo<Variant>\") } matches no task"),
        )

        val ok = FixtureProject.materialize("app-startup-tracing", temp.newFolder("quiet"))
        val okResult = ok.build(
            tier = "STANDARD",
            "-PbugseeFixtureFlutterPluginStandIn=true",
            "-PbugseeFixtureFakeFlutter=true",
        )
        assertTrue(
            "no Flutter-ordering warning when Flutter is applied and its task exists",
            !okResult.output.contains("assetsDependsOn"),
        )
    }

    @Test
    fun flutter_applied_without_its_copy_task_warns() {
        val fixture = FixtureProject.materialize("app-startup-tracing", temp.newFolder("missing"))
        val result = fixture.build(tier = "STANDARD", "-PbugseeFixtureFlutterPluginStandIn=true")
        assertTrue(
            "rename warning must appear when Flutter is applied but no copy task exists",
            result.output.contains("no copyFlutterAssets<Variant> task was found"),
        )
    }
}
