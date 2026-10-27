package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket

/**
 * Issue #94: an upload that cannot reach the server only warns by default; `failOnUploadError` makes it
 * fail the build. A closed local port stands in for the unreachable endpoint.
 */
class UploadFailOnErrorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture() = FixtureProject.materialize("build-type-gating", temp.newFolder())

    /** A localhost port nothing listens on: connections are refused at once. */
    private fun closedPortUrl(): String = ServerSocket(0).use { "http://127.0.0.1:${it.localPort}" }

    private fun uploadMapping(failOnUploadError: Boolean?): Pair<Boolean, String> {
        val args = mutableListOf("-PbugseeFixtureMinifyRelease=true", "-PbugseeFixtureEndpoint=${closedPortUrl()}")
        if (failOnUploadError != null) args += "-PbugseeFixtureFailOnUploadError=$failOnUploadError"
        return fixture().runTasksAllowingFailure(listOf(":app:uploadBugseeReleaseMapping"), *args.toTypedArray())
    }

    @Test
    fun unreachableEndpointOnlyWarnsByDefault() {
        val (ok, out) = uploadMapping(failOnUploadError = null)
        assertTrue("an upload failure must not fail the build by default:\n$out", ok)
        assertTrue("the failure must be warned about:\n$out", "Bugsee upload failed" in out)
    }

    @Test
    fun unreachableEndpointFailsTheBuildWhenOptedIn() {
        val (ok, out) = uploadMapping(failOnUploadError = true)
        assertTrue("failOnUploadError must fail the build:\n$out", !ok)
        assertTrue("the failure must name the setting:\n$out", "failOnUploadError" in out)
        assertTrue("the app token must stay out of the output:\n$out", "fixture-token-0123456789" !in out)
    }
}
