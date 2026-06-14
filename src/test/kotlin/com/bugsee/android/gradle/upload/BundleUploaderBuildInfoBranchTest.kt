package com.bugsee.android.gradle.upload

import org.apache.http.impl.client.HttpClients
import org.gradle.api.Action
import org.gradle.api.logging.Logging
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.process.JavaExecSpec
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the build-info bundle routing in [BundleUploader]:
 *
 *  1. The pure gate [BundleUploader.shouldAttemptBundle].
 *  2. The LAZY CLI resolution + null-resolve fallback in
 *     [BundleUploader.uploadBuildInfoComponents] (drives the real method,
 *     not just the predicate).
 *
 * The bundle is attempted ONLY when ALL hold: the server signed a
 * `build_info_upload_endpoint`, the `BUGSEE_LEGACY_BUILDINFO_GZIP` escape
 * hatch is off, an [ExecOperations] is available, and there's at least one
 * component to pack. Any miss — or a CLI that won't resolve — falls back to
 * the legacy per-blob path. The rollout fails closed.
 */
class BundleUploaderBuildInfoBranchTest {

    // ── pure gate ────────────────────────────────────────────────────

    @Test fun `gate opens when every condition holds`() {
        assertTrue(
            BundleUploader.shouldAttemptBundle(
                buildInfoUploadEndpoint = "https://s3.example/build-info?sig=…",
                legacyBuildInfoGzip = false,
                hasExec = true,
                hasAnyJson = true,
            ),
        )
    }

    @Test fun `gate closed when the server did not sign a bundle URL (org flag off)`() {
        assertFalse(
            BundleUploader.shouldAttemptBundle("", false, hasExec = true, hasAnyJson = true),
        )
    }

    @Test fun `gate closed when the escape hatch forces legacy gzip`() {
        assertFalse(
            BundleUploader.shouldAttemptBundle(
                "https://s3.example/build-info", true, hasExec = true, hasAnyJson = true,
            ),
        )
    }

    @Test fun `gate closed when no ExecOperations is available`() {
        assertFalse(
            BundleUploader.shouldAttemptBundle(
                "https://s3.example/build-info", false, hasExec = false, hasAnyJson = true,
            ),
        )
    }

    @Test fun `gate closed when there is nothing to pack`() {
        assertFalse(
            BundleUploader.shouldAttemptBundle(
                "https://s3.example/build-info", false, hasExec = true, hasAnyJson = false,
            ),
        )
    }

    // ── lazy resolution + fallback (drives the real method) ──────────

    /**
     * `resolveCli` must be invoked ONLY when the gate opens — an org that
     * isn't flagged on (no bundle URL) must never pay the CLI auto-download.
     * `gzFile`s are null so the legacy `uploadAuxiliaryBlob` calls
     * early-return without touching the network, and `resolveCli` returns
     * null so the CLI is never exec'd — keeping this a pure unit test.
     */
    @Test fun `resolveCli is invoked only when the gate opens (lazy)`() {
        // gate open → resolved exactly once
        assertEquals(1, resolveInvocations(endpoint = "https://s3/u", legacy = false, hasExec = true))
        // gate closed → never resolved (no wasted download)
        assertEquals(0, resolveInvocations(endpoint = "", legacy = false, hasExec = true))
        assertEquals(0, resolveInvocations(endpoint = "https://s3/u", legacy = true, hasExec = true))
        assertEquals(0, resolveInvocations(endpoint = "https://s3/u", legacy = false, hasExec = false))
    }

    /** Drives the real `uploadBuildInfoComponents` and returns how many
     *  times `resolveCli` was called. A null resolve falls back to the
     *  legacy per-blob PUTs, which no-op here (null gz files). */
    private fun resolveInvocations(endpoint: String, legacy: Boolean, hasExec: Boolean): Int {
        var calls = 0
        HttpClients.createDefault().use { client ->
            BundleUploader.uploadBuildInfoComponents(
                client = client,
                buildInfoUploadEndpoint = endpoint,
                dependenciesUploadEndpoint = "",
                timingsUploadEndpoint = "",
                execOps = if (hasExec) NoopExecOps else null,
                resolveCli = { calls++; null },
                depsJsonFile = File("/tmp/dependencies.json"),
                timingsJsonFile = null,
                dependenciesGzFile = null,
                timingsGzFile = null,
                legacyBuildInfoGzip = legacy,
                logger = Logging.getLogger(BundleUploaderBuildInfoBranchTest::class.java),
                debug = false,
            )
        }
        return calls
    }

    /** No-op [ExecOperations]: the lazy-resolution test resolves the CLI to
     *  null, so the CLI is never exec'd — these methods are unreachable in
     *  this test and throw if that ever changes. */
    private object NoopExecOps : ExecOperations {
        override fun exec(action: Action<in ExecSpec>): ExecResult =
            throw UnsupportedOperationException("exec must not be reached in this test")

        override fun javaexec(action: Action<in JavaExecSpec>): ExecResult =
            throw UnsupportedOperationException("javaexec must not be reached in this test")
    }
}
