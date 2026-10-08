package com.bugsee.android.gradle.upload

import com.bugsee.android.gradle.config.RecordingLogger
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The size-check baseline URL is `…/v2/apps/<token>/builds/baseline`, so the debug line that prints it
 * must mask the app token. The line is logged before the request, so an unreachable endpoint is enough.
 */
class BaselineClientLogTest {

    private val appToken = "tok_SECRET_0123456789abcdef"

    @Test fun `debug logging masks the app token in the baseline URL`() {
        val logger = RecordingLogger()
        BaselineClient.fetchBaseline(
            endpoint = "http://127.0.0.1:1", // nothing listens here: the call fails fast, after the debug line
            appToken = appToken,
            packageId = "com.example.app",
            format = "aab",
            buildConfiguration = "release",
            logger = logger,
            debug = true,
        )
        val line = logger.warnings.singleOrNull { it.contains("fetching baseline from") }
        assertTrue(line != null, "debug must log the baseline URL; got: ${logger.warnings}")
        assertFalse(logger.warnings.any { it.contains(appToken) }, "full token must not be logged; got: ${logger.warnings}")
        assertTrue(line.contains("/v2/apps/tok_…cdef/builds/baseline"), "must show the masked token in place; got: $line")
    }
}
