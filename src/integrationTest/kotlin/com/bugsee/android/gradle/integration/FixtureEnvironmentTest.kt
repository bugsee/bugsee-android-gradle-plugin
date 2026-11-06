package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The fixture builds must see the same `RELEASE` as the outer build.
 *
 * The fixtures include this project as the plugin under test and its `build.gradle.kts` derives the plugin's version
 * from `RELEASE` (see [FixtureProject.releaseEnvironment]). `withEnvironment` replaces the environment, so a fixture
 * build that is not given `RELEASE` computes `-SNAPSHOT` while a release build (the production deploy) has published
 * the compose-compiler artifacts under the plain version: the 4.1.1 production deploy failed six Compose fixtures
 * with "Could not find ...-SNAPSHOT". A SNAPSHOT build cannot show that (both sides say SNAPSHOT), so the check is on
 * the environment itself, which differs in every mode.
 */
class FixtureEnvironmentTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun release_is_forwarded_when_the_outer_build_sets_it() {
        assertEquals(mapOf("RELEASE" to "true"), FixtureProject.releaseEnvironment { if (it == "RELEASE") "true" else null })
        assertEquals(mapOf("RELEASE" to "false"), FixtureProject.releaseEnvironment { if (it == "RELEASE") "false" else null })
    }

    @Test
    fun release_is_left_unset_when_the_outer_build_does_not_set_it() {
        assertEquals(emptyMap<String, String>(), FixtureProject.releaseEnvironment { null })
    }

    @Test
    fun a_fixture_build_sees_the_same_release_as_the_outer_build() {
        val probe = temp.newFile("print-release.init.gradle").apply {
            writeText("gradle.rootProject { println(\"NESTED_RELEASE=\" + System.getenv('RELEASE')) }\n")
        }
        val fixture = FixtureProject.materialize("plugin-order-reversed", temp.newFolder("project"))

        val result = fixture.runTasks(listOf("help"), "-I", probe.absolutePath)

        val nested = Regex("NESTED_RELEASE=(.*)").find(result.output)?.groupValues?.get(1)?.trim()
        assertTrue("the probe printed nothing:\n${result.output}", nested != null)
        assertEquals("RELEASE as seen by a fixture build vs by the outer build", System.getenv("RELEASE").toString(), nested)
    }
}
