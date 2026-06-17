package com.bugsee.android.gradle.upload

import org.gradle.api.Action
import org.gradle.api.logging.Logging
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.process.JavaExecSpec
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pin three CliBinaryResolver contracts that, if broken, would silently
 * misroute the auto-download URL and have the plugin point at non-existent
 * artifacts (or at wrong-arch binaries) for end users:
 *
 *   1. Host triple detection must map JVM `os.name` / `os.arch` strings to
 *      the exact Rust target triples `dist` publishes for. A typo here
 *      means the plugin tries to download a file that doesn't exist.
 *   2. Artifact URL construction must produce the canonical
 *      `bugsee-cli-<triple>.<ext>` shape under
 *      `https://download.bugsee.com/cli/v<version>/`.
 *   3. SHA-256 sidecar parsing must extract just the hex digest from
 *      `<hex>  <filename>` / `<hex> *<filename>` formats — anything else
 *      means the verification step compares against a malformed value
 *      and rejects every download.
 */
class CliBinaryResolverTest {

    // ── Host triple mapping ─────────────────────────────────────────

    @Test fun `macOS Apple Silicon maps to aarch64-apple-darwin`() {
        assertEquals(
            "aarch64-apple-darwin",
            CliBinaryResolver.detectHostTriple("Mac OS X", "aarch64"),
        )
        assertEquals(
            "aarch64-apple-darwin",
            CliBinaryResolver.detectHostTriple("Darwin", "arm64"),
        )
    }

    @Test fun `macOS Intel maps to x86_64-apple-darwin`() {
        assertEquals(
            "x86_64-apple-darwin",
            CliBinaryResolver.detectHostTriple("Mac OS X", "x86_64"),
        )
        assertEquals(
            "x86_64-apple-darwin",
            CliBinaryResolver.detectHostTriple("Mac OS X", "amd64"),
        )
    }

    @Test fun `Linux arm64 maps to aarch64-unknown-linux-gnu`() {
        assertEquals(
            "aarch64-unknown-linux-gnu",
            CliBinaryResolver.detectHostTriple("Linux", "aarch64"),
        )
    }

    @Test fun `Linux amd64 maps to x86_64-unknown-linux-gnu`() {
        assertEquals(
            "x86_64-unknown-linux-gnu",
            CliBinaryResolver.detectHostTriple("Linux", "amd64"),
        )
        assertEquals(
            "x86_64-unknown-linux-gnu",
            CliBinaryResolver.detectHostTriple("Linux", "x86_64"),
        )
    }

    @Test fun `Windows amd64 maps to x86_64-pc-windows-msvc`() {
        assertEquals(
            "x86_64-pc-windows-msvc",
            CliBinaryResolver.detectHostTriple("Windows 10", "amd64"),
        )
        assertEquals(
            "x86_64-pc-windows-msvc",
            CliBinaryResolver.detectHostTriple("Windows 11", "x64"),
        )
    }

    @Test fun `unsupported combinations return null`() {
        // dist does not publish these today; null lets the plugin warn
        // and fall back to the Kotlin uploader rather than 404 on download.
        assertNull(CliBinaryResolver.detectHostTriple("Windows 11", "aarch64"))
        assertNull(CliBinaryResolver.detectHostTriple("FreeBSD", "x86_64"))
        assertNull(CliBinaryResolver.detectHostTriple("Linux", "i386"))
        assertNull(CliBinaryResolver.detectHostTriple("", ""))
    }

    // ── Artifact URL ─────────────────────────────────────────────────

    @Test fun `unix artifact URL uses tar dot xz`() {
        assertEquals(
            "https://download.bugsee.com/cli/v0.1.0/bugsee-cli-aarch64-apple-darwin.tar.xz",
            CliBinaryResolver.artifactUrl("0.1.0", "aarch64-apple-darwin"),
        )
        assertEquals(
            "https://download.bugsee.com/cli/v1.2.3/bugsee-cli-x86_64-unknown-linux-gnu.tar.xz",
            CliBinaryResolver.artifactUrl("1.2.3", "x86_64-unknown-linux-gnu"),
        )
    }

    @Test fun `windows artifact URL uses zip`() {
        assertEquals(
            "https://download.bugsee.com/cli/v0.1.0/bugsee-cli-x86_64-pc-windows-msvc.zip",
            CliBinaryResolver.artifactUrl("0.1.0", "x86_64-pc-windows-msvc"),
        )
    }

    // ── sha256sum sidecar parsing ───────────────────────────────────

    @Test fun `sha256 sidecar with two-space delimiter`() {
        // Standard `sha256sum` format: hex, two spaces, filename.
        assertEquals(
            "1cc82da9dcfc64437fb6e68f6ed34e4c9846520c5d0dc09805f4fa0489d509ed",
            CliBinaryResolver.parseSha256Sidecar(
                "1cc82da9dcfc64437fb6e68f6ed34e4c9846520c5d0dc09805f4fa0489d509ed  " +
                    "bugsee-cli-aarch64-apple-darwin.tar.xz\n",
            ),
        )
    }

    @Test fun `sha256 sidecar with asterisk (binary mode) delimiter`() {
        // dist emits `<hex> *<filename>` (binary mode marker).
        assertEquals(
            "1cc82da9dcfc64437fb6e68f6ed34e4c9846520c5d0dc09805f4fa0489d509ed",
            CliBinaryResolver.parseSha256Sidecar(
                "1cc82da9dcfc64437fb6e68f6ed34e4c9846520c5d0dc09805f4fa0489d509ed " +
                    "*bugsee-cli-aarch64-apple-darwin.tar.xz",
            ),
        )
    }

    @Test fun `sha256 sidecar with only the hex (no filename)`() {
        // Some pipelines emit just the hex. Should still parse cleanly.
        assertEquals(
            "abcdef0123456789",
            CliBinaryResolver.parseSha256Sidecar("abcdef0123456789\n"),
        )
    }

    @Test fun `sha256 sidecar with tab delimiter`() {
        assertEquals(
            "deadbeef",
            CliBinaryResolver.parseSha256Sidecar("deadbeef\tfile.tar.xz"),
        )
    }

    // ── version gate for the `pack` subcommand ───────────────────────

    @Test fun `default pinned version now satisfies the pack gate (activated)`() {
        // Activation: DEFAULT_VERSION has been bumped to a release that ships
        // `pack`, so the CLI packer is live and size-analysis builds pack the
        // mapping with zstd. (Before the bump this asserted the opposite — the
        // inert contract — so a CLI without `pack` was never auto-downloaded.)
        // If DEFAULT_VERSION is ever rolled BACK below PACK_MIN_VERSION the
        // native DEFLATE packer takes over again; this test would flip with it.
        assertTrue(
            CliBinaryResolver.versionAtLeast(
                CliBinaryResolver.DEFAULT_VERSION,
                CliBinaryResolver.PACK_MIN_VERSION,
            ),
            "bumped DEFAULT_VERSION must satisfy the >=PACK_MIN_VERSION pack gate",
        )
    }

    @Test fun `default pinned version now satisfies the upload-build gate (activated)`() {
        // Activation: DEFAULT_VERSION has been bumped to 0.3.0, the first
        // release shipping `upload build`, so BundleUploadTask routes ALL
        // artefact uploads through the CLI (the native Bundle/ChunkedBundle
        // path becomes the fallback). Before this bump (DEFAULT_VERSION 0.2.0)
        // the gate was below UPLOAD_BUILD_MIN_VERSION and the path stayed inert.
        // If DEFAULT_VERSION is ever rolled BACK below UPLOAD_BUILD_MIN_VERSION
        // the native path takes over again and this test flips with it.
        assertTrue(
            CliBinaryResolver.versionAtLeast(
                CliBinaryResolver.DEFAULT_VERSION,
                CliBinaryResolver.UPLOAD_BUILD_MIN_VERSION,
            ),
            "bumped DEFAULT_VERSION must satisfy the >=UPLOAD_BUILD_MIN_VERSION gate",
        )
    }

    @Test fun `versionAtLeast compares numeric components`() {
        assertTrue(CliBinaryResolver.versionAtLeast("0.2.0", "0.2.0"), "equal is >=")
        assertTrue(CliBinaryResolver.versionAtLeast("0.2.1", "0.2.0"))
        assertTrue(CliBinaryResolver.versionAtLeast("0.10.0", "0.2.0"), "10 > 2 numerically, not lexically")
        assertTrue(CliBinaryResolver.versionAtLeast("1.0.0", "0.2.0"))
        assertFalse(CliBinaryResolver.versionAtLeast("0.1.9", "0.2.0"))
        assertFalse(CliBinaryResolver.versionAtLeast("0.1.0", "0.2.0"))
    }

    @Test fun `versionAtLeast treats a prerelease on its numeric core`() {
        // A 0.2.0 prerelease ships `pack`, so it should satisfy the gate —
        // we'd rather attempt pack on a 0.2.0-rc than skip it.
        assertTrue(CliBinaryResolver.versionAtLeast("0.2.0-rc1", "0.2.0"))
        assertTrue(CliBinaryResolver.versionAtLeast("0.2.0+build7", "0.2.0"))
        assertFalse(CliBinaryResolver.versionAtLeast("0.1.0-rc9", "0.2.0"))
    }

    @Test fun `versionAtLeast pads missing trailing components with zero`() {
        assertTrue(CliBinaryResolver.versionAtLeast("0.2", "0.2.0"))
        assertFalse(CliBinaryResolver.versionAtLeast("0.2", "0.2.1"))
    }

    @Test fun `versionAtLeast treats empty or garbage as below the gate`() {
        // A blank/unparseable pinned version must NOT be read as "supports
        // pack" — that would auto-download a CLI for every build only to have
        // it usage-error. Each unparseable segment contributes 0, so the
        // comparison stays conservative.
        assertFalse(CliBinaryResolver.versionAtLeast("", "0.2.0"))
        assertFalse(CliBinaryResolver.versionAtLeast("garbage", "0.2.0"))
        assertFalse(CliBinaryResolver.versionAtLeast("v-next", "0.2.0"))
    }

    // ── floor bump ───────────────────────────────────────────────────

    @Test fun `default floor version is 0_6_0`() {
        // The pinned download floor — must be >= 0.6.0, the first CLI release
        // with the `update` self-update command this resolver invokes. If this
        // is rolled back, the pack / upload-build gate tests above shift with
        // it — this pins the intended floor explicitly. Keeping the CLI current
        // beyond this floor is delegated to the CLI's own `update --max-age 12h`.
        assertEquals("0.6.0", CliBinaryResolver.DEFAULT_VERSION)
    }

    // ── self-update delegation (resolve → `update --max-age 12h`) ─────

    @Test fun `resolve on a cache hit with autoUpdate true invokes update --max-age 12h`() {
        // The CLI owns version discovery now: once a binary is on disk the
        // plugin just runs `<binary> update --max-age 12h`, best-effort. On a
        // cache hit (binary already present) there is NO network in resolve
        // itself — only the self-update exec, whose spec we capture here.
        val home = gradleHomeWithCachedBinary()
        val rec = RecordingExecOps()

        val resolved = CliBinaryResolver.resolve(
            cliVersion = null,
            cliPath = null,
            execOps = rec,
            gradleUserHome = home,
            logger = logger,
            debug = false,
            autoUpdate = true,
        )

        assertNotNull(resolved, "cache hit must resolve to the cached binary")
        assertEquals(1, rec.specs.size, "exactly one exec (the self-update) must run")
        val spec = rec.specs.single()
        assertEquals(
            resolved.absolutePath,
            spec.executable,
            "self-update must exec the resolved binary in place",
        )
        assertEquals(
            listOf("update", "--max-age", "12h"),
            spec.args,
            "self-update must delegate the whole update contract to the CLI",
        )
        assertTrue(
            spec.ignoreExitValue,
            "self-update is best-effort: a non-zero exit must NOT fail the build",
        )
    }

    @Test fun `resolve with autoUpdate false does NOT invoke update`() {
        // Opt-out path: a user who disabled cliAutoUpdate must never have the
        // binary self-replace. The cached binary is still returned verbatim.
        val home = gradleHomeWithCachedBinary()
        val rec = RecordingExecOps()

        val resolved = CliBinaryResolver.resolve(
            cliVersion = null,
            cliPath = null,
            execOps = rec,
            gradleUserHome = home,
            logger = logger,
            debug = false,
            autoUpdate = false,
        )

        assertNotNull(resolved, "cache hit must still resolve to the cached binary")
        assertTrue(rec.specs.isEmpty(), "autoUpdate=false must not exec `update`")
    }

    @Test fun `resolve still returns the binary when the self-update exec throws`() {
        // Self-update is strictly best-effort. If the exec itself blows up
        // (binary vanished mid-build, OS denies exec, etc.), resolution must
        // STILL return the binary — the failure can never break the upload.
        val home = gradleHomeWithCachedBinary()
        val throwing = object : ExecOperations {
            override fun exec(action: Action<in ExecSpec>): ExecResult =
                throw RuntimeException("exec blew up")
            override fun javaexec(action: Action<in JavaExecSpec>): ExecResult =
                throw AssertionError("javaexec must not be reached")
        }

        val resolved = CliBinaryResolver.resolve(
            cliVersion = null,
            cliPath = null,
            execOps = throwing,
            gradleUserHome = home,
            logger = logger,
            debug = false,
            autoUpdate = true,
        )

        assertNotNull(resolved, "a thrown self-update exec must NOT break resolution")
    }

    // ── fixtures ─────────────────────────────────────────────────────

    @get:Rule
    val tmp = TemporaryFolder()

    private val logger = Logging.getLogger("test")

    /**
     * A Gradle-user-home temp dir pre-seeded with an executable cached
     * `bugsee-cli` binary at the exact host-triple path `resolve` looks up,
     * so `resolve(autoUpdate=...)` takes the cache-hit fast path with no
     * network. Returns the home dir.
     */
    private fun gradleHomeWithCachedBinary(): File {
        val home = tmp.newFolder()
        val triple = CliBinaryResolver.detectHostTriple()
            ?: error("host triple unsupported; cannot seed a cache fixture on this host")
        val binaryName = if (triple.contains("windows")) "bugsee-cli.exe" else "bugsee-cli"
        val cacheDir = File(home, "caches/bugsee-cli/${CliBinaryResolver.DEFAULT_VERSION}/$triple")
        cacheDir.mkdirs()
        val binary = File(cacheDir, binaryName)
        binary.writeText("#!/bin/sh\nexit 0\n")
        binary.setExecutable(true)
        return home
    }

    /** Captured snapshot of the fields `maybeSelfUpdate` sets on the spec. */
    private class CapturedSpec(
        val executable: String?,
        val args: List<String>,
        val ignoreExitValue: Boolean,
    )

    /**
     * Fake [ExecOperations] that drives a recording [ExecSpec] proxy through
     * the supplied action and snapshots what was set on it. `exec` returns a
     * zero-exit [ExecResult] (no real process is spawned).
     */
    private class RecordingExecOps : ExecOperations {
        val specs = mutableListOf<CapturedSpec>()

        override fun exec(action: Action<in ExecSpec>): ExecResult {
            val recorder = SpecRecorder()
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                ExecSpec::class.java.classLoader,
                arrayOf(ExecSpec::class.java),
                recorder,
            ) as ExecSpec
            action.execute(proxy)
            specs.add(
                CapturedSpec(recorder.executable, recorder.args.toList(), recorder.ignoreExitValue),
            )
            return ZeroExitResult
        }

        override fun javaexec(action: Action<in JavaExecSpec>): ExecResult =
            throw AssertionError("javaexec must not be reached")
    }

    /**
     * `InvocationHandler` for an [ExecSpec] proxy. Records the three setters
     * `maybeSelfUpdate` calls (`setExecutable`, `setArgs`, `setIgnoreExitValue`)
     * and returns the proxy for any builder-style setter so chaining is safe.
     * Every other method returns a benign default.
     */
    private class SpecRecorder : java.lang.reflect.InvocationHandler {
        var executable: String? = null
        var args: List<String> = emptyList()
        var ignoreExitValue: Boolean = false

        override fun invoke(proxy: Any, method: java.lang.reflect.Method, rawArgs: Array<Any?>?): Any? {
            val a = rawArgs ?: emptyArray()
            when (method.name) {
                "setExecutable" -> executable = a.getOrNull(0)?.toString()
                "setArgs" -> {
                    @Suppress("UNCHECKED_CAST")
                    args = (a.getOrNull(0) as? List<Any?>)?.map { it.toString() } ?: emptyList()
                }
                "setIgnoreExitValue" -> ignoreExitValue = (a.getOrNull(0) as? Boolean) ?: false
            }
            // Builder-style setters on ExecSpec return the spec; mirror that so
            // any fluent chaining keeps working. Primitive returns default.
            return when (method.returnType) {
                Void.TYPE -> null
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> if (method.returnType.isAssignableFrom(proxy.javaClass)) proxy else null
            }
        }
    }

    /** Minimal zero-exit [ExecResult] for the recording fake. */
    private object ZeroExitResult : ExecResult {
        override fun getExitValue(): Int = 0
        override fun assertNormalExitValue(): ExecResult = this
        override fun rethrowFailure(): ExecResult = this
    }
}
