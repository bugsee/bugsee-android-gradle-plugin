package com.bugsee.android.gradle.upload

import com.sun.net.httpserver.HttpServer
import org.gradle.api.logging.Logging
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

/**
 * The uploaders' Apache HttpClients must honour the JVM proxy system
 * properties (`-Dhttp.proxyHost` / `systemProp.https.proxyHost` from
 * gradle.properties). The target host is unresolvable, so the only way a
 * request can be observed is through the proxy stub.
 */
class UploaderProxySystemPropertiesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val proxyHits = AtomicInteger(0)
    private lateinit var proxy: HttpServer
    private val saved = mutableMapOf<String, String?>()
    private val logger = Logging.getLogger("proxy-test")
    private val targetEndpoint = "http://bugsee-unresolvable.invalid"

    @Before fun setUp() {
        proxy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        proxy.executor = Executors.newSingleThreadExecutor()
        proxy.createContext("/") { exchange ->
            proxyHits.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(503, -1L)
            exchange.close()
        }
        proxy.start()
        for (key in listOf("http.proxyHost", "http.proxyPort", "http.nonProxyHosts", "https.proxyHost", "https.proxyPort")) saved[key] = System.getProperty(key)
        System.setProperty("http.proxyHost", "127.0.0.1")
        System.setProperty("http.proxyPort", proxy.address.port.toString())
        // Empty list: do not bypass the proxy for any host.
        System.setProperty("http.nonProxyHosts", "")
    }

    @After fun tearDown() {
        proxy.stop(0)
        for ((key, value) in saved) {
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
    }

    private fun payload() = tempFolder.newFile().apply { writeBytes(ByteArray(16)) }

    @Test fun `SymbolUploader routes through the http proxy system properties`() {
        SymbolUploader.uploadData(
            file = payload(), json = "{}", appToken = "tok", endpoint = targetEndpoint,
            logger = logger, debug = false,
        )
        assertTrue(proxyHits.get() > 0, "request must reach the proxy stub")
    }

    @Test fun `BundleUploader routes through the http proxy system properties`() {
        // Throws on the stub 503 (step 1 failed); that proves the request got there.
        runCatching {
            BundleUploader.uploadData(
                file = payload(), json = "{}", appToken = "tok", endpoint = targetEndpoint,
                requestArtifactUpload = false, logger = logger, debug = false,
            )
        }
        assertTrue(proxyHits.get() > 0, "request must reach the proxy stub")
    }

    @Test fun `BaselineClient routes through the http proxy system properties`() {
        BaselineClient.fetchBaseline(
            endpoint = targetEndpoint, appToken = "tok", packageId = "com.x", format = "apk",
            buildConfiguration = null, logger = logger, debug = false,
        )
        assertTrue(proxyHits.get() > 0, "request must reach the proxy stub")
    }

    @Test fun `ChunkedBundleUploader routes through the http proxy system properties`() {
        runCatching {
            ChunkedBundleUploader.upload(payload(), JSONObject(), "tok", targetEndpoint, logger, false)
        }
        assertTrue(proxyHits.get() > 0, "request must reach the proxy stub")
    }

    @Test fun `https endpoint routes through the https proxy system properties (CONNECT)`() {
        // Raw socket stub: an https request through a proxy starts with a CONNECT line.
        val connectLines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val stub = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val acceptor = Thread {
            while (!stub.isClosed) {
                try {
                    stub.accept().use { s ->
                        s.soTimeout = 2000
                        runCatching { s.getInputStream().bufferedReader().readLine() }.getOrNull()?.let { connectLines.add(it) }
                    }
                } catch (_: Exception) { }
            }
        }.apply { isDaemon = true; start() }
        System.clearProperty("http.proxyHost")
        System.setProperty("https.proxyHost", "127.0.0.1")
        System.setProperty("https.proxyPort", stub.localPort.toString())
        try {
            runCatching {
                SymbolUploader.uploadData(
                    file = payload(), json = "{}", appToken = "tok",
                    endpoint = "https://bugsee-unresolvable.invalid", logger = logger, debug = false,
                )
            }
        } finally {
            stub.close()
            acceptor.join(3000)
        }
        assertTrue(
            connectLines.any { it.startsWith("CONNECT bugsee-unresolvable.invalid:443") },
            "expected a CONNECT through the https proxy; saw: $connectLines",
        )
    }
}
