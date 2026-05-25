package com.bugsee.android.gradle.upload

import org.apache.http.impl.client.HttpClients
import org.gradle.api.logging.Logger
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pin the three short-circuit branches inside
 * [BundleUploader.uploadAuxiliaryBlob]:
 *
 * 1. `gzFile == null` → silent return (no PUT, NO warn). This is
 *    the "collection step produced nothing" path — a clean signal,
 *    not an error.
 * 2. `gzFile != null` AND `presignedUrl.isEmpty()` → log a warn,
 *    no PUT. This is the "we had something to upload but the
 *    server didn't grant us a URL" path — surfaces a server-side
 *    gating mismatch that the operator should see.
 * 3. `gzFile == null` AND `presignedUrl.isEmpty()` → silent
 *    return (no PUT, NO warn). The gzFile check runs first; the
 *    URL check is unreached. Pin this so a future refactor that
 *    reorders the checks doesn't accidentally turn the
 *    deps-collection-disabled run into a noisy build.
 *
 * Prior to this file, branch (2) — the load-bearing one for
 * server/client schema drift — was documented in the helper's KDoc
 * but **not asserted**. A mutation that turned the warn into a
 * silent return would have been undetected.
 */
class BundleUploaderAuxBlobTest {

    @get:org.junit.Rule
    val tempFolder = TemporaryFolder()

    private lateinit var client: org.apache.http.impl.client.CloseableHttpClient
    private lateinit var logger: RecordingLoggerForAuxBlob

    @Before fun setUp() {
        // The short-circuit paths never use the client; any real
        // CloseableHttpClient works. We close it in tearDown so we
        // don't leak file descriptors across tests.
        client = HttpClients.createDefault()
        logger = RecordingLoggerForAuxBlob()
    }

    @After fun tearDown() {
        client.close()
    }

    private fun tempGzFile(): File {
        val f = tempFolder.newFile("blob.gz")
        f.writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 0, 0))  // gz magic; content irrelevant
        return f
    }

    @Test fun `null gz file with empty URL — silent return, no warn`() {
        // Deps-collection-disabled flow: the client never produced a
        // gz blob, the server never minted a presigned URL. Both
        // absent. The helper must NOT warn — this is the clean path.
        BundleUploader.uploadAuxiliaryBlob(
            client = client,
            label = "dependencies",
            presignedUrl = "",
            gzFile = null,
            logger = logger,
            debug = false,
        )
        assertTrue(
            logger.warnMessages.isEmpty(),
            "null gz + empty URL must be silent; got: ${logger.warnMessages}",
        )
    }

    @Test fun `null gz file with valid URL — silent return, no warn`() {
        // Defensive: even if the server somehow returned a URL the
        // client doesn't have content for (server/client schema
        // drift the other direction), the helper short-circuits on
        // `gzFile == null` first. The URL is dropped silently; no
        // wasted PUT attempt; no warn (the URL is reused for nothing,
        // which isn't the client's problem).
        BundleUploader.uploadAuxiliaryBlob(
            client = client,
            label = "dependencies",
            presignedUrl = "https://example.invalid/aux/deps",
            gzFile = null,
            logger = logger,
            debug = false,
        )
        assertTrue(
            logger.warnMessages.isEmpty(),
            "null gz with non-empty URL must still short-circuit silently; got: ${logger.warnMessages}",
        )
    }

    @Test fun `gz file present with empty URL — WARN and skip PUT`() {
        // The load-bearing case. The client produced a deps blob but
        // the server did NOT return a presigned URL (server-side
        // flag gating differs from what the client expected). The
        // helper MUST warn — silent loss of a successfully-collected
        // payload would be a debugging black hole.
        BundleUploader.uploadAuxiliaryBlob(
            client = client,
            label = "dependencies",
            presignedUrl = "",
            gzFile = tempGzFile(),
            logger = logger,
            debug = false,
        )
        assertEquals(
            1, logger.warnMessages.size,
            "expected one warn for the no-URL-but-content-ready case; got: ${logger.warnMessages}",
        )
        val msg = logger.warnMessages.single()
        assertTrue(
            msg.contains("dependencies"),
            "warn must include the label so the operator can tell which blob was orphaned; got: $msg",
        )
        assertTrue(
            msg.contains("presigned URL") || msg.contains("did not return"),
            "warn must explain WHY it skipped (no server URL); got: $msg",
        )
    }

    @Test fun `label is included verbatim in the no-URL warn for greppable logs`() {
        // The KDoc explicitly says "log messages so warning lines
        // are greppable". Pin label-in-warn for both labels.
        BundleUploader.uploadAuxiliaryBlob(
            client = client,
            label = "timings",
            presignedUrl = "",
            gzFile = tempGzFile(),
            logger = logger,
            debug = false,
        )
        assertEquals(1, logger.warnMessages.size)
        assertTrue(
            logger.warnMessages.single().contains("timings"),
            "label 'timings' must appear verbatim in warn output; got: ${logger.warnMessages.single()}",
        )
    }
}

/**
 * Hand-rolled recording Logger — captures warn() messages. We don't
 * share `RecordingLogger` from [ChunkedBundleUploaderHttpTest] because
 * that class is `private` to that test file (the test file uses
 * file-private visibility for its mock harness). Duplicating the
 * minimal subset we need is cheaper than restructuring both files.
 */
private class RecordingLoggerForAuxBlob : Logger {
    val warnMessages: MutableList<String> = mutableListOf()
    private fun record(msg: String?) { if (msg != null) warnMessages.add(msg) }
    override fun getName(): String = "recording-aux"
    override fun isTraceEnabled(): Boolean = false
    override fun isTraceEnabled(p: org.slf4j.Marker?): Boolean = false
    override fun trace(msg: String?) {}
    override fun trace(format: String?, arg: Any?) {}
    override fun trace(format: String?, arg1: Any?, arg2: Any?) {}
    override fun trace(format: String?, vararg arguments: Any?) {}
    override fun trace(msg: String?, t: Throwable?) {}
    override fun trace(marker: org.slf4j.Marker?, msg: String?) {}
    override fun trace(marker: org.slf4j.Marker?, format: String?, arg: Any?) {}
    override fun trace(marker: org.slf4j.Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun trace(marker: org.slf4j.Marker?, format: String?, vararg arguments: Any?) {}
    override fun trace(marker: org.slf4j.Marker?, msg: String?, t: Throwable?) {}
    override fun isDebugEnabled(): Boolean = false
    override fun isDebugEnabled(p: org.slf4j.Marker?): Boolean = false
    override fun debug(msg: String?) {}
    override fun debug(format: String?, arg: Any?) {}
    override fun debug(format: String?, arg1: Any?, arg2: Any?) {}
    override fun debug(format: String?, vararg arguments: Any?) {}
    override fun debug(msg: String?, t: Throwable?) {}
    override fun debug(marker: org.slf4j.Marker?, msg: String?) {}
    override fun debug(marker: org.slf4j.Marker?, format: String?, arg: Any?) {}
    override fun debug(marker: org.slf4j.Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun debug(marker: org.slf4j.Marker?, format: String?, vararg arguments: Any?) {}
    override fun debug(marker: org.slf4j.Marker?, msg: String?, t: Throwable?) {}
    override fun isInfoEnabled(): Boolean = false
    override fun isInfoEnabled(p: org.slf4j.Marker?): Boolean = false
    override fun info(msg: String?) {}
    override fun info(format: String?, arg: Any?) {}
    override fun info(format: String?, arg1: Any?, arg2: Any?) {}
    override fun info(format: String?, vararg arguments: Any?) {}
    override fun info(msg: String?, t: Throwable?) {}
    override fun info(marker: org.slf4j.Marker?, msg: String?) {}
    override fun info(marker: org.slf4j.Marker?, format: String?, arg: Any?) {}
    override fun info(marker: org.slf4j.Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun info(marker: org.slf4j.Marker?, format: String?, vararg arguments: Any?) {}
    override fun info(marker: org.slf4j.Marker?, msg: String?, t: Throwable?) {}
    override fun isWarnEnabled(): Boolean = true
    override fun isWarnEnabled(p: org.slf4j.Marker?): Boolean = true
    override fun warn(msg: String?) { record(msg) }
    override fun warn(format: String?, arg: Any?) { record(format) }
    override fun warn(format: String?, arg1: Any?, arg2: Any?) { record(format) }
    override fun warn(format: String?, vararg arguments: Any?) { record(format) }
    override fun warn(msg: String?, t: Throwable?) { record(msg) }
    override fun warn(marker: org.slf4j.Marker?, msg: String?) { record(msg) }
    override fun warn(marker: org.slf4j.Marker?, format: String?, arg: Any?) { record(format) }
    override fun warn(marker: org.slf4j.Marker?, format: String?, arg1: Any?, arg2: Any?) { record(format) }
    override fun warn(marker: org.slf4j.Marker?, format: String?, vararg arguments: Any?) { record(format) }
    override fun warn(marker: org.slf4j.Marker?, msg: String?, t: Throwable?) { record(msg) }
    override fun isErrorEnabled(): Boolean = false
    override fun isErrorEnabled(p: org.slf4j.Marker?): Boolean = false
    override fun error(msg: String?) {}
    override fun error(format: String?, arg: Any?) {}
    override fun error(format: String?, arg1: Any?, arg2: Any?) {}
    override fun error(format: String?, vararg arguments: Any?) {}
    override fun error(msg: String?, t: Throwable?) {}
    override fun error(marker: org.slf4j.Marker?, msg: String?) {}
    override fun error(marker: org.slf4j.Marker?, format: String?, arg: Any?) {}
    override fun error(marker: org.slf4j.Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun error(marker: org.slf4j.Marker?, format: String?, vararg arguments: Any?) {}
    override fun error(marker: org.slf4j.Marker?, msg: String?, t: Throwable?) {}
    override fun isLifecycleEnabled(): Boolean = false
    override fun lifecycle(msg: String?) {}
    override fun lifecycle(msg: String?, vararg arguments: Any?) {}
    override fun lifecycle(msg: String?, t: Throwable?) {}
    override fun isQuietEnabled(): Boolean = false
    override fun quiet(msg: String?) {}
    override fun quiet(msg: String?, vararg arguments: Any?) {}
    override fun quiet(msg: String?, t: Throwable?) {}
    override fun isEnabled(level: org.gradle.api.logging.LogLevel?): Boolean = false
    override fun log(level: org.gradle.api.logging.LogLevel?, message: String?) {}
    override fun log(level: org.gradle.api.logging.LogLevel?, message: String?, vararg objects: Any?) {}
    override fun log(level: org.gradle.api.logging.LogLevel?, message: String?, throwable: Throwable?) {}
}
