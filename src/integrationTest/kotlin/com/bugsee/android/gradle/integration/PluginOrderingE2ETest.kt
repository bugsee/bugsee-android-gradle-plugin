package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * Issue #46: with `plugins { id("com.bugsee.android.gradle"); id("com.android.application") }`
 * the Bugsee plugin used to look up AndroidComponentsExtension before AGP existed and wired
 * nothing, silently. The wiring now waits for the Android plugin via `pluginManager.withPlugin`.
 */
class PluginOrderingE2ETest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun bugsee_applied_before_android_plugin_still_injects_uuid_and_build_id() {
        val fixture = FixtureProject.materialize("plugin-order-reversed", temp.newFolder("reversed"))

        val result = fixture.build()

        assertFalse(
            "must not warn that the Android plugin is missing",
            result.output.contains("AndroidComponentsExtension not found"),
        )
        val manifest = fixture.readMergedManifest("debug")
        assertNotNull("merged manifest must exist", manifest)
        assertNotNull("BUILD_UUID must be injected", fixture.extractBuildUuid(manifest!!))
        val apk = File(fixture.projectDir, "app/build/outputs/apk/debug")
            .listFiles { f -> f.extension == "apk" }!!.single()
        ZipFile(apk).use { zip ->
            assertTrue(
                "build-id asset must be in the APK",
                zip.getEntry("assets/bugsee_build_id.properties") != null,
            )
        }
    }
}
