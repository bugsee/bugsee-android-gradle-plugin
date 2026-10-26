package com.bugsee.android.gradle.upload

import org.junit.Test
import kotlin.test.assertEquals

class ProxyEnvTest {

    @Test fun `https and http proxies map to HTTPS_PROXY and HTTP_PROXY`() {
        val out = proxyEnvFromSystemProperties(
            mapOf(
                "https.proxyHost" to "ph", "https.proxyPort" to "3128",
                "http.proxyHost" to "ph2", "http.proxyPort" to "8080",
            ),
            emptyMap(),
        )
        assertEquals(mapOf("HTTPS_PROXY" to "http://ph:3128", "HTTP_PROXY" to "http://ph2:8080"), out)
    }

    @Test fun `missing port defaults per scheme and IPv6 host is bracketed`() {
        val out = proxyEnvFromSystemProperties(
            mapOf("https.proxyHost" to "::1", "http.proxyHost" to "h"),
            emptyMap(),
        )
        assertEquals("http://[::1]:443", out["HTTPS_PROXY"])
        assertEquals("http://h:80", out["HTTP_PROXY"])
    }

    @Test fun `nonProxyHosts converts to NO_PROXY`() {
        val out = proxyEnvFromSystemProperties(
            mapOf("http.nonProxyHosts" to "localhost|*.corp.example|10.*|internal"),
            emptyMap(),
        )
        assertEquals(mapOf("NO_PROXY" to "localhost,.corp.example,10.*,internal"), out)
    }

    @Test fun `variables already in the environment are not overridden`() {
        val out = proxyEnvFromSystemProperties(
            mapOf(
                "https.proxyHost" to "ph", "http.proxyHost" to "ph", "http.nonProxyHosts" to "a",
            ),
            mapOf("https_proxy" to "http://x:1", "HTTP_PROXY" to "http://y:2", "no_proxy" to "z"),
        )
        assertEquals(emptyMap(), out)
    }

    @Test fun `nothing configured yields nothing`() {
        assertEquals(emptyMap(), proxyEnvFromSystemProperties(emptyMap(), emptyMap()))
        assertEquals(
            emptyMap(),
            proxyEnvFromSystemProperties(mapOf("https.proxyHost" to " ", "http.nonProxyHosts" to ""), emptyMap()),
        )
    }
}
