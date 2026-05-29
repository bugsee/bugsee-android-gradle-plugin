package com.bugsee.android.gradle.instrumentation.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Matches a class against the user-configured instrumentation exclude
 * patterns (`bugsee { instrumentation { excludes.add(...) } }`).
 *
 * Each factory consults this in `isInstrumentable` so a consumer can opt a
 * class OUT of all Bugsee bytecode instrumentation — the escape hatch for
 * the (rare) case where instrumenting a specific class fails (see
 * [CatchingMethodVisitor]'s attributed error).
 *
 * Class names are the dot-separated FQNs AGP exposes via
 * `ClassData.className` / `ClassContext.currentClassData.className`
 * (e.g. `com.example.Foo`). A pattern matches when:
 *  - it equals the class name exactly (`com.example.Foo`), or
 *  - it names a package the class lives under (`com.example` matches
 *    `com.example.Foo` and `com.example.sub.Bar`), or
 *  - it contains `*` wildcards, treated as "any characters" — e.g.
 *    `com.example.*` or `*.databinding.*Binding`.
 */
internal object InstrumentationExcludes {

    /**
     * Compiled-glob cache. `isExcluded` runs in every factory's
     * `isInstrumentable`, i.e. once per class on the classpath (thousands at
     * the FULL startup tier). Without this, each glob pattern would recompile
     * a fresh [Regex] on every call — a real per-class allocation/CPU cost.
     * Keyed by the trimmed pattern; bounded by the (tiny) number of distinct
     * user-configured glob patterns, so no eviction is needed.
     * [ConcurrentHashMap] because AGP runs instrumentation across worker
     * threads.
     */
    private val globCache = ConcurrentHashMap<String, Regex>()

    fun isExcluded(className: String, patterns: Collection<String>): Boolean {
        if (patterns.isEmpty()) return false
        return patterns.any { matches(className, it.trim()) }
    }

    private fun matches(className: String, pattern: String): Boolean {
        if (pattern.isEmpty()) return false
        if (pattern.indexOf('*') >= 0) {
            return globRegex(pattern).matches(className)
        }
        // No wildcard: exact class OR package-prefix match.
        return className == pattern || className.startsWith("$pattern.")
    }

    /** Memoized accessor for [compileGlob] — compiles each pattern once. */
    private fun globRegex(pattern: String): Regex =
        globCache.computeIfAbsent(pattern) { compileGlob(it) }

    /**
     * Treats `*` as "any run of characters" (including `.`) and every other
     * character as a literal. Built by splitting on `*` and quoting the
     * literal segments so regex metacharacters in class names can't leak in.
     */
    private fun compileGlob(pattern: String): Regex =
        pattern.split("*")
            .joinToString(separator = ".*") { Regex.escape(it) }
            .toRegex()
}
