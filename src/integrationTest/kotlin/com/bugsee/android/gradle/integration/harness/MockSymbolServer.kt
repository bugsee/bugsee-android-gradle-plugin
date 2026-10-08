package com.bugsee.android.gradle.integration.harness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process stand-in for the Bugsee symbols endpoint, speaking the two-stage
 * protocol bugsee-cli uses: `POST /apps/<token>/symbols` returns a presigned
 * URL, `PUT /put/<n>` receives the bytes. Every other request is answered
 * `200 {}` so unrelated plugin tasks (mapping, build-info) do not fail the build.
 *
 * [alreadyHasSymbols] models a server that already stores the library: it
 * answers the appserver's nested `DuplicateSymbolsFoundError` (16004) unless the
 * POST asks to `"overwrite":true`.
 *
 * [storedVariant] (`"symtab"` or `"dwarf"`) models a stored copy of known richness
 * (bugsee-cli >= 0.8.1 / appserver `replace_if_richer`): a POST declaring a richer
 * `format_variant` with `"replace_if_richer":true` replaces a `symtab` copy; the
 * server never downgrades and otherwise answers 16004.
 */
internal class MockSymbolServer(
    var alreadyHasSymbols: Boolean = false,
    var storedVariant: String? = null,
) : AutoCloseable {

    data class Req(val method: String, val path: String, val body: ByteArray) {
        val text: String get() = String(body, Charsets.ISO_8859_1)
    }

    val requests = CopyOnWriteArrayList<Req>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    val symbolPosts: List<Req>
        get() = requests.filter { it.method == "POST" && it.path.endsWith("/symbols") }
    val puts: List<Req>
        get() = requests.filter { it.method == "PUT" && it.path.startsWith("/put/") }

    init {
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readBytes()
        val req = Req(ex.requestMethod, ex.requestURI.path, body)
        requests += req
        val reply: String = when {
            req.method == "POST" && req.path.endsWith("/symbols") -> {
                val overwrite = req.text.contains("\"overwrite\":true")
                val upgrade = storedVariant == "symtab" &&
                    req.text.contains("\"replace_if_richer\":true") &&
                    req.text.contains("\"format_variant\":\"dwarf\"")
                if ((alreadyHasSymbols || storedVariant != null) && !overwrite && !upgrade) {
                    """{"ok":false,"error":{"type":"DuplicateSymbolsFoundError","code":16004}}"""
                } else {
                    if (upgrade) storedVariant = "dwarf"
                    """{"code":0,"endpoint":"$url/put/${requests.size}"}"""
                }
            }
            else -> "{}"
        }
        val bytes = reply.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, if (req.method == "PUT") -1 else bytes.size.toLong())
        if (req.method != "PUT") ex.responseBody.use { it.write(bytes) } else ex.close()
    }

    override fun close() = server.stop(0)
}
