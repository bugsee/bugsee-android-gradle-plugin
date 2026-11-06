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
        // Credentials (http(s).proxyUser/proxyPassword) go in the URL, percent-encoded, so the
        // CLI authenticates like the Kotlin path. The environment is not world-readable.
        val user = sysProps["$scheme.proxyUser"].orEmpty()
        val creds = if (user.isEmpty()) "" else {
            val pass = sysProps["$scheme.proxyPassword"].orEmpty()
            enc(user) + (if (pass.isEmpty()) "" else ":" + enc(pass)) + "@"
        }
        return "http://$creds$authority:$port"
    }

    val result = LinkedHashMap<String, String>()
    if (!has("HTTPS_PROXY", "https_proxy")) proxyUrl("https")?.let { result["HTTPS_PROXY"] = it }
    if (!has("HTTP_PROXY", "http_proxy")) proxyUrl("http")?.let { result["HTTP_PROXY"] = it }

    if (!has("NO_PROXY", "no_proxy")) {
        val raw = sysProps["http.nonProxyHosts"]
        if (raw == null) {
            // Mirror the JDK built-in default so loopback stays direct.
            if (result.isNotEmpty()) result["NO_PROXY"] = JDK_LOOPBACK_NO_PROXY
        } else if (raw.isNotEmpty()) {
            // The JDK appends its loopback set to any non-empty list; an empty value means empty.
            val translated = raw.split('|').filter { it.isNotEmpty() }
                // The JDK splits only on `|` and does not trim, so a comma/space in an entry is a literal that
                // matches no host, whereas NO_PROXY would split it (`foo, *, bar` would bypass all).
                .filter { e -> e.none { it == ',' || it.isWhitespace() } }
                .mapNotNull { nonProxyEntryToNoProxy(it) }
            result["NO_PROXY"] = (translated + JDK_LOOPBACK_NO_PROXY).joinToString(",")
        }
    }
    return result
}

/** Production wrapper: the live JVM system properties and process environment. */
internal fun proxyEnvForCli(): Map<String, String> =
    proxyEnvFromSystemProperties(
        System.getProperties().stringPropertyNames().associateWith { System.getProperty(it) },
        System.getenv(),
    )

private fun enc(v: String): String = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

/**
 * One JDK `http.nonProxyHosts` pattern (`*` allowed only at the start or end) to a NO_PROXY entry.
 * NO_PROXY has no globs: exact host, domain suffix (`.b`) or CIDR. Returns null (dropped) for a
 * pattern that cannot be expressed.
 *
 * The pinned bugsee-cli's matching is pinned by `CliNoProxyContractTest`: an entry `b` or `.b`
 * matches `b` AND every subdomain (case-insensitively; `notb` is not matched), so `*.b` -> `.b`
 * also bypasses the apex `b`, and a plain host entry `b` also bypasses its subdomains, where the
 * JDK matches subdomains only / the exact host only. No NO_PROXY form is narrower, so these
 * slight widenings are accepted (a widened bypass connects directly instead of via the proxy:
 * the apex of a listed `*.b` domain, or the subdomains of a listed plain host); a `*x` suffix pattern
 * becomes `x`, which is narrower than the JDK's. A `.b` or CIDR-looking entry is a literal that
 * matches nothing in the JDK but is honoured by the CLI, which is what the user meant.
 * Bracketed IPv6 (`[::1]`) does NOT match in the CLI, hence the brackets are stripped.
 */
private fun nonProxyEntryToNoProxy(p: String): String? = when {
    p == "*" -> "*"
    p.length > 1 && p.startsWith("*") && p.endsWith("*") -> null
    p.startsWith("*.") -> p.substring(1).takeUnless { '*' in it || '/' in it }
    p.startsWith("*") -> p.substring(1).takeUnless { '*' in it || '/' in it }
    p.endsWith(".*") -> {
        val octets = p.dropLast(2).split('.')
        if (octets.size in 1..3 && octets.all { o -> o.all { c -> c in '0'..'9' } && (o == "0" || !o.startsWith("0")) && o.toIntOrNull()?.let { it in 0..255 } == true }) {
            val full = octets + List(4 - octets.size) { "0" }
            full.joinToString(".") + "/" + octets.size * 8
        } else {
            null
        }
    }
    p.endsWith("*") -> null
    p.length > 2 && p.startsWith("[") && p.endsWith("]") -> p.substring(1, p.length - 1).takeIf { ':' in it && '*' !in it && '/' !in it }
    else -> p
}

/** The JDK's built-in loopback bypass (`localhost|127.*|[::1]|0.0.0.0|[::0]`) in NO_PROXY syntax. */
private const val JDK_LOOPBACK_NO_PROXY = "localhost,127.0.0.0/8,::1,0.0.0.0,::"
