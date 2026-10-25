package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
        val asset = File(
            fixture.projectDir,
            "app/build/intermediates/assets/debug/mergeDebugAssets/bugsee_build_id.properties",
        )
        // Output location is AGP-owned; fall back to a search so a layout change fails loudly.
        val found = asset.isFile ||
            File(fixture.projectDir, "app/build").walkTopDown()
                .any { it.name == "bugsee_build_id.properties" }
        assertTrue("bugsee_build_id.properties asset must exist", found)
    }
}
