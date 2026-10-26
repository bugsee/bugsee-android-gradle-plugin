package com.bugsee.android.gradle.upload

/**
 * Derives the `HTTPS_PROXY` / `HTTP_PROXY` / `NO_PROXY` environment variables for
 * `bugsee-cli` from the JVM proxy system properties.
 *
 * Proxies are normally configured for Gradle through `systemProp.https.proxyHost` etc. in
 * `gradle.properties`. Those become JVM system properties, never environment variables, so the
 * CLI (which reads only the environment) would not see them. A variable that is already set in
 * [env] is never overridden — an explicit environment always wins.
 *
 * Pure function so it is unit-testable without touching the real process state.
 */
internal fun proxyEnvFromSystemProperties(
    sysProps: Map<String, String?>,
    env: Map<String, String>,
): Map<String, String> {
    fun has(vararg names: String) = names.any { !env[it].isNullOrEmpty() }

    fun proxyUrl(scheme: String): String? {
        val host = sysProps["$scheme.proxyHost"]?.trim().orEmpty()
        if (host.isEmpty()) return null
        val port = sysProps["$scheme.proxyPort"]?.trim().orEmpty()
            .ifEmpty { if (scheme == "https") "443" else "80" }
        // IPv6 literals must be bracketed in a URL authority.
        val authority = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "http://$authority:$port"
    }

    val result = LinkedHashMap<String, String>()
    if (!has("HTTPS_PROXY", "https_proxy")) proxyUrl("https")?.let { result["HTTPS_PROXY"] = it }
    if (!has("HTTP_PROXY", "http_proxy")) proxyUrl("http")?.let { result["HTTP_PROXY"] = it }

    if (!has("NO_PROXY", "no_proxy")) {
        // The JDK reads only `http.nonProxyHosts` (for both schemes): `a|*.b|c` — `*` wildcard
        // at the start or end. NO_PROXY is comma-separated and uses `.b` for a domain suffix.
        val noProxy = sysProps["http.nonProxyHosts"].orEmpty()
            .split('|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith("*.")) it.substring(1) else it }
            .joinToString(",")
        if (noProxy.isNotEmpty()) result["NO_PROXY"] = noProxy
    }
    return result
}

/** Production wrapper: the live JVM system properties and process environment. */
internal fun proxyEnvForCli(): Map<String, String> =
    proxyEnvFromSystemProperties(
        System.getProperties().stringPropertyNames().associateWith { System.getProperty(it) },
        System.getenv(),
    )
