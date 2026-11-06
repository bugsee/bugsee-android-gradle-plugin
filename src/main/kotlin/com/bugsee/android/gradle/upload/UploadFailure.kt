package com.bugsee.android.gradle.upload

import org.gradle.api.GradleException

/**
 * The opt-in `failOnUploadError` outcome. Uploads are best-effort by default: a failure is a logged
 * warning and the build stays green. With the setting on, an upload that did not complete fails the task.
 * The cause was already logged (masked) by the uploader, so the message only names what failed.
 */
internal object UploadFailure {

    /** Throws a [GradleException] naming [what] when [failOnUploadError] is on; otherwise does nothing. */
    fun failIfRequested(failOnUploadError: Boolean, what: String) {
        if (failOnUploadError) {
            throw GradleException(
                "Bugsee: $what was not uploaded and failOnUploadError is enabled (see the warning above). " +
                    "Set bugsee { failOnUploadError.set(false) } to only warn.",
            )
        }
    }
}
