package com.bugsee.android.gradle.upload

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test fun `default pinned version does NOT yet support pack`() {
        // The activation contract: until DEFAULT_VERSION is bumped to a
        // release shipping `pack`, the CLI packer stays inert and builds
        // use the native DEFLATE packer. If this flips unexpectedly, the
        // plugin would start auto-downloading a CLI that usage-errors on
        // an unknown subcommand for every size-analysis build.
        assertFalse(
            CliBinaryResolver.versionAtLeast(
                CliBinaryResolver.DEFAULT_VERSION,
                CliBinaryResolver.PACK_MIN_VERSION,
            ),
            "0.1.0 must not satisfy the >=0.2.0 pack gate",
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
}
