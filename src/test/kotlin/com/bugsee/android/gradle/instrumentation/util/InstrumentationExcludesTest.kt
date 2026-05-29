package com.bugsee.android.gradle.instrumentation.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentationExcludesTest {

    @Test
    fun emptyPatternsExcludeNothing() {
        assertFalse(InstrumentationExcludes.isExcluded("com.foo.Bar", emptyList()))
    }

    @Test
    fun exactClassNameMatches() {
        assertTrue(InstrumentationExcludes.isExcluded("com.foo.Bar", listOf("com.foo.Bar")))
        assertFalse(InstrumentationExcludes.isExcluded("com.foo.Baz", listOf("com.foo.Bar")))
    }

    @Test
    fun barePackageExcludesEverythingUnderIt() {
        val p = listOf("com.foo")
        assertTrue(InstrumentationExcludes.isExcluded("com.foo.Bar", p))
        assertTrue(InstrumentationExcludes.isExcluded("com.foo.sub.Baz", p))
        // A sibling package that merely shares the string prefix must NOT match.
        assertFalse(InstrumentationExcludes.isExcluded("com.foobar.Bar", p))
    }

    @Test
    fun trailingGlobMatchesSubtree() {
        val p = listOf("com.foo.*")
        assertTrue(InstrumentationExcludes.isExcluded("com.foo.Bar", p))
        assertTrue(InstrumentationExcludes.isExcluded("com.foo.sub.Baz", p))
        assertFalse(InstrumentationExcludes.isExcluded("com.other.Bar", p))
    }

    @Test
    fun midGlobMatches() {
        val p = listOf("*.databinding.*Binding")
        assertTrue(InstrumentationExcludes.isExcluded("a.b.databinding.FooBinding", p))
        assertFalse(InstrumentationExcludes.isExcluded("a.b.Foo", p))
    }

    @Test
    fun globWildcardMatchesEmptyRun() {
        // A `*` must match ZERO characters too (`.*`, not `.+`). Without a
        // case where the wildcard matches nothing, a `.*`->`.+` regression
        // escapes — every other glob fixture has >= 1 char around the `*`.
        assertTrue(
            "trailing * must match an empty suffix",
            InstrumentationExcludes.isExcluded("com.foo.Bar", listOf("com.foo.Bar*")),
        )
        assertTrue(
            "leading * must match an empty prefix",
            InstrumentationExcludes.isExcluded("com.foo.Bar", listOf("*com.foo.Bar")),
        )
    }

    @Test
    fun packageGlobRequiresTheSeparatorDot() {
        // `com.foo.*` anchors a trailing dot, so it matches the SUBTREE but
        // not the bare package name itself — unlike the no-wildcard form
        // `com.foo`, which matches `com.foo` exactly. Pins that asymmetry.
        assertFalse(
            "com.foo.* must NOT match the bare package `com.foo`",
            InstrumentationExcludes.isExcluded("com.foo", listOf("com.foo.*")),
        )
        assertTrue(
            "the no-wildcard form DOES match the bare package",
            InstrumentationExcludes.isExcluded("com.foo", listOf("com.foo")),
        )
    }

    @Test
    fun whitespaceIsTrimmed() {
        assertTrue(InstrumentationExcludes.isExcluded("com.foo.Bar", listOf("  com.foo.Bar  ")))
    }

    @Test
    fun dotInPatternIsLiteralNotRegexWildcard() {
        // A '.' must match only a literal '.', not "any character".
        assertFalse(InstrumentationExcludes.isExcluded("comXfoo", listOf("com.foo")))
        assertFalse(InstrumentationExcludes.isExcluded("comXfooXBar", listOf("com.foo.*")))
    }

    @Test
    fun anyMatchingPatternExcludes() {
        val p = listOf("com.a.Keep", "com.b.*")
        assertTrue(InstrumentationExcludes.isExcluded("com.b.Bar", p))
        assertFalse(InstrumentationExcludes.isExcluded("com.c.Bar", p))
    }
}
