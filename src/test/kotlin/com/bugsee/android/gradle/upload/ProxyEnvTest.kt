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
        assertEquals("http://ph:3128", out["HTTPS_PROXY"])
        assertEquals("http://ph2:8080", out["HTTP_PROXY"])
    }

    @Test fun `missing port defaults per scheme and IPv6 host is bracketed`() {
        val out = proxyEnvFromSystemProperties(
            mapOf("https.proxyHost" to "::1", "http.proxyHost" to "h"),
            emptyMap(),
        )
        assertEquals("http://[::1]:443", out["HTTPS_PROXY"])
        assertEquals("http://h:80", out["HTTP_PROXY"])
    }

    @Test fun `entries containing a comma or space are dropped instead of being split by NO_PROXY`() {
        val out = proxyEnvFromSystemProperties(
            mapOf("http.nonProxyHosts" to "foo, *, bar|*.corp,foo|a b|[*]|[.b]| * | *.corp |010.*|*.10.0.0.0/8|*10.0.0.0/8|[host]|[a/b:c]|\t|ok.example"),
            emptyMap(),
        )
        assertEquals("ok.example,localhost,127.0.0.0/8,::1,0.0.0.0,::", out["NO_PROXY"])
    }

    @Test fun `nonProxyHosts converts to NO_PROXY`() {
        val out = proxyEnvFromSystemProperties(
            mapOf("http.nonProxyHosts" to "localhost|*.corp.example|10.*|192.168.1.*|*suffix.example|foo*|*corp*|[fd00::5]|*|internal"),
            emptyMap(),
        )
        assertEquals(mapOf("NO_PROXY" to "localhost,.corp.example,10.0.0.0/8,192.168.1.0/24,suffix.example,fd00::5,*,internal,localhost,127.0.0.0/8,::1,0.0.0.0,::"), out)
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

    @Test fun `unset nonProxyHosts mirrors the JDK loopback default only when a proxy is derived`() {
        assertEquals(
            mapOf("HTTPS_PROXY" to "http://ph:1", "NO_PROXY" to "localhost,127.0.0.0/8,::1,0.0.0.0,::"),
            proxyEnvFromSystemProperties(mapOf("https.proxyHost" to "ph", "https.proxyPort" to "1"), emptyMap()),
        )
    }

    @Test fun `proxy credentials are percent-encoded into the URL`() {
        val out = proxyEnvFromSystemProperties(
            mapOf(
                "https.proxyHost" to "ph", "https.proxyPort" to "1",
                "https.proxyUser" to "me@corp", "https.proxyPassword" to "p w:/",
            ),
            emptyMap(),
        )
        assertEquals("http://me%40corp:p%20w%3A%2F@ph:1", out["HTTPS_PROXY"])
    }

    @Test fun `loopback is appended to a non-empty list and an empty list stays empty`() {
        val props = mapOf("https.proxyHost" to "ph", "https.proxyPort" to "1")
        assertEquals(
            ".corp.example,localhost,127.0.0.0/8,::1,0.0.0.0,::",
            proxyEnvFromSystemProperties(props + ("http.nonProxyHosts" to "*.corp.example"), emptyMap())["NO_PROXY"],
        )
        assertEquals(null, proxyEnvFromSystemProperties(props + ("http.nonProxyHosts" to ""), emptyMap())["NO_PROXY"])
    }
}
