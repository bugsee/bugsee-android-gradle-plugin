package com.bugsee.android.gradle.upload

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * REAL-BINARY contract test (issue #102): pins how the pinned `bugsee-cli` matches `NO_PROXY`,
 * which [proxyEnvFromSystemProperties] relies on when it translates the JDK `http.nonProxyHosts`.
 *
 * Method: a recording HTTP proxy on loopback answers every request with 502. The CLI runs
 * `upload build` against `http://<host>:<closed port>` with `HTTP_PROXY` pointing at that proxy
 * and a given `NO_PROXY`. A recorded hit means the CLI went through the proxy; no hit means it
 * bypassed it (and then failed to connect, which is irrelevant here). The target hosts never
 * need to resolve: a proxied request is resolved by the proxy, so hosts under the reserved
 * `.invalid` TLD are enough.
 *
 * Like [CliUploaderRealBinaryTest] it Assume-skips without `BUGSEE_CLI_BIN` / `-Dbugsee.cli.bin`;
 * CI's real-binary step provides the binary (Linux only).
 */
class CliNoProxyContractTest {

    private class RecordingProxy : AutoCloseable {
        val hits = CopyOnWriteArrayList<String>()
        private val server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        val url: String get() = "http://127.0.0.1:${server.localPort}"

        init {
            thread(isDaemon = true, name = "recording-proxy") {
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) { serve(s) }
                }
            }
        }

        private fun serve(s: Socket) {
            s.use { serveOne(it) }
        }

        private fun serveOne(sock: Socket) {
            try {
                val reader = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = reader.readLine() ?: return
                var contentLength = 0
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("content-length:", ignoreCase = true)) {
                        contentLength = h.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                hits += requestLine
                // Drain the body (ISO_8859_1: one char per byte) so closing never RSTs the client.
                var left = contentLength
                while (left > 0) {
                    val n = reader.skip(left.toLong()).toInt()
                    if (n <= 0) break
                    left -= n
                }
                sock.getOutputStream().apply {
                    write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
            } catch (_: Exception) {
                // a client that hung up early; the hit (if any) is already recorded
            }
        }

        override fun close() = server.close()
    }

    private fun requireBinary(): File {
        val raw = System.getProperty("bugsee.cli.bin") ?: System.getenv("BUGSEE_CLI_BIN")
        val bin = raw?.let(::File)?.takeIf { it.isFile && it.canExecute() }
        assumeTrue("no bugsee-cli binary; set BUGSEE_CLI_BIN or -Dbugsee.cli.bin=", bin != null)
        return bin!!
    }

    /** A port nothing listens on, so a bypassed request fails fast instead of reaching anything. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private class Outcome(val proxied: Boolean, val output: String)

    /** Runs the CLI once; [Outcome.proxied] is true when the request went through the proxy. */
    private fun RecordingProxy.run(bin: File, work: File, c: Case, port: Int): Outcome {
        hits.clear()
        val scheme = if (c.https) "https" else "http"
        val noProxy = c.noProxy?.replace("{port}", port.toString())
        val payload = File(work, "payload.json").apply { writeText("""{"uuid":"u","version":"1","package":"p"}""") }
        val pb = ProcessBuilder(
            bin.absolutePath, "--endpoint", "$scheme://${c.host}:$port", "--app-token", "x",
            "upload", "build", "--payload-json", payload.absolutePath,
        ).directory(work).redirectErrorStream(true)
        pb.environment().apply {
            clear()
            put("HOME", work.absolutePath)
            put("USERPROFILE", work.absolutePath)
            System.getenv("SystemRoot")?.let { put("SystemRoot", it) }
            put(if (c.https) "HTTPS_PROXY" else "HTTP_PROXY", url)
            if (noProxy != null) put("NO_PROXY", noProxy)
        }
        val p = pb.start()
        var text = ""
        val reader = thread(isDaemon = true) { text = p.inputStream.readBytes().toString(Charsets.UTF_8) }
        if (!p.waitFor(90, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            fail("bugsee-cli did not exit for host=${c.host} NO_PROXY=$noProxy")
        }
        reader.join(5_000)
        return Outcome(hits.isNotEmpty(), text)
    }

    private class Case(val host: String, val noProxy: String?, val bypass: Boolean, val https: Boolean = false)

    private fun check(cases: List<Case>) {
        val bin = requireBinary()
        val work = Files.createTempDirectory("bugsee-noproxy").toFile()
        try {
            RecordingProxy().use { proxy ->
                // After the proxy is bound, so the two can never share a port.
                val port = closedPort()
                val wrong = cases.mapNotNull { c ->
                    val o = proxy.run(bin, work, c, port)
                    // The hit alone is not enough: a CLI that failed before sending anything
                    // would look like a bypass. Require the failure each path really produces.
                    val sane = when {
                        c.https && o.proxied -> true // a recorded CONNECT is evidence enough
                        o.proxied -> "502" in o.output
                        else -> "error sending request" in o.output
                    }
                    if (o.proxied != c.bypass && sane) {
                        null
                    } else {
                        "host=${c.host} NO_PROXY=${c.noProxy} https=${c.https} expected " +
                            "${if (c.bypass) "bypass" else "proxied"}, got proxied=${o.proxied}: ${o.output.take(200)}"
                    }
                }
                assertEquals(emptyList(), wrong, "bugsee-cli NO_PROXY matching changed; revisit ProxyEnv")
            }
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `without NO_PROXY, or with an empty one, every host goes through the proxy`() = check(
        listOf(
            Case("corp.invalid", null, bypass = false),
            Case("127.0.0.1", null, bypass = false),
            Case("localhost", null, bypass = false),
            Case("corp.invalid", "", bypass = false),
        ),
    )

    @Test
    fun `a leading-dot entry bypasses subdomains and the apex but not lookalike hosts`() = check(
        listOf(
            Case("www.corp.invalid", ".corp.invalid", bypass = true),
            Case("a.b.corp.invalid", ".corp.invalid", bypass = true),
            // reqwest semantics: the apex is bypassed too (JDK `*.corp.invalid` would not match it).
            Case("corp.invalid", ".corp.invalid", bypass = true),
            Case("notcorp.invalid", ".corp.invalid", bypass = false),
            Case("corp.invalid", ".", bypass = false),
            // HTTPS_PROXY (CONNECT) follows the same rules.
            Case("www.corp.invalid", ".corp.invalid", bypass = true, https = true),
            Case("notcorp.invalid", ".corp.invalid", bypass = false, https = true),
        ),
    )

    @Test
    fun `a bare host entry matches the host and its subdomains, case-insensitively`() = check(
        listOf(
            Case("corp.invalid", "corp.invalid", bypass = true),
            // Unlike the JDK, an exact host entry also covers subdomains.
            Case("www.corp.invalid", "corp.invalid", bypass = true),
            Case("notcorp.invalid", "corp.invalid", bypass = false),
            Case("CORP.INVALID", "corp.invalid", bypass = true),
            Case("corp.invalid", "CORP.INVALID", bypass = true),
            Case("corp.invalid", "other.invalid, corp.invalid", bypass = true),
            // No port syntax in entries: a `host:port` entry (port of the request) never matches.
            Case("corp.invalid", "corp.invalid:{port}", bypass = false),
        ),
    )

    @Test
    fun `globs are not supported except for a lone star`() = check(
        listOf(
            Case("corp.invalid", "*", bypass = true),
            Case("corp.invalid", "*.invalid", bypass = false),
            Case("corp.invalid", "*corp.invalid", bypass = false),
            Case("corp.invalid", "corp.*", bypass = false),
        ),
    )

    @Test
    fun `IPv4 literals match by exact address and CIDR, never by name resolution`() = check(
        listOf(
            Case("127.0.0.1", "127.0.0.1", bypass = true),
            Case("127.0.0.1", "127.0.0.0/8", bypass = true),
            Case("127.0.0.1", "10.0.0.0/8", bypass = false),
            Case("127.0.0.1", "127.0.1.0/24", bypass = false),
            Case("127.0.0.1", "localhost", bypass = false),
            Case("0.0.0.0", "0.0.0.0", bypass = true),
        ),
    )

    @Test
    fun `IPv6 literals match unbracketed, not bracketed, and by CIDR`() = check(
        listOf(
            Case("[::1]", "::1", bypass = true),
            Case("[::1]", "::1/128", bypass = true),
            // Only loopback targets are ever bypassed: a direct connect to a routable address could hang.
            Case("[::1]", "::/64", bypass = true),
            Case("[::1]", "fd00::/8", bypass = false),
            Case("[::]", "::", bypass = true),
            // A bracketed entry does not match, which is why ProxyEnv strips the brackets.
            Case("[::1]", "[::1]", bypass = false),
            Case("[fd00::5]", "[fd00::5]", bypass = false),
            Case("[fd00::5]", "fd01::/16", bypass = false),
        ),
    )

    // The translation end to end ------------------------------------------------------------

    private fun noProxyFor(nonProxyHosts: String): String =
        proxyEnvFromSystemProperties(mapOf("http.nonProxyHosts" to nonProxyHosts), emptyMap()).getValue("NO_PROXY")

    @Test
    fun `translated JDK patterns bypass no more than the JDK does, apart from the documented widening`() {
        val corp = noProxyFor("*.corp.invalid")
        val exact = noProxyFor("internal.invalid")
        val suffix = noProxyFor("*corp.invalid")
        // Without the appended loopback set, which would bypass 127.0.0.1 regardless.
        val loopback = ",localhost,127.0.0.0/8,::1,0.0.0.0,::"
        val net = noProxyFor("127.*").removeSuffix(loopback)
        val narrowNet = noProxyFor("127.0.1.*").removeSuffix(loopback)
        check(
            listOf(
                Case("www.corp.invalid", corp, bypass = true),
                Case("a.b.corp.invalid", corp, bypass = true),
                Case("notcorp.invalid", corp, bypass = false),
                Case("other.invalid", corp, bypass = false),
                Case("internal.invalid", exact, bypass = true),
                Case("other.invalid", exact, bypass = false),
                Case("xcorp.invalid", suffix, bypass = false),
                Case("127.0.0.1", net, bypass = true),
                Case("127.0.0.1", narrowNet, bypass = false),
            ),
        )
    }

    @Test
    fun `the always-appended loopback set covers the JDK loopback set (a superset for IPv6 spellings)`() {
        val env = proxyEnvFromSystemProperties(mapOf("https.proxyHost" to "ph"), emptyMap()).getValue("NO_PROXY")
        check(
            listOf(
                Case("localhost", env, bypass = true),
                Case("127.0.0.1", env, bypass = true),
                Case("[::1]", env, bypass = true),
                Case("0.0.0.0", env, bypass = true),
                Case("[::]", env, bypass = true),
                Case("corp.invalid", env, bypass = false),
            ),
        )
    }
}
