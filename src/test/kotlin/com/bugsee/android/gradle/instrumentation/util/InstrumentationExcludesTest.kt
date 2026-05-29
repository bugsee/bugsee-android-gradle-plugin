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
