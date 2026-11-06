package com.bugsee.android.gradle.upload

import org.gradle.api.GradleException
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UploadFailureTest {

    @Test
    fun offByDefaultNeverThrows() {
        UploadFailure.failIfRequested(false, "the mapping for variant release")
    }

    @Test
    fun onThrowsAGradleExceptionNamingWhatFailedAndTheSetting() {
        val ex = assertFailsWith<GradleException> { UploadFailure.failIfRequested(true, "the mapping for variant release") }
        val msg = ex.message!!
        assertTrue("the mapping for variant release" in msg, msg)
        assertTrue("failOnUploadError" in msg, msg)
    }
}
