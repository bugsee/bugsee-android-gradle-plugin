package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import org.junit.Assert.assertEquals
import org.junit.Test

class SiteIdGeneratorTest {

    @Test fun `forMethod converts slashes to dots and appends method`() {
        assertEquals(
            "com.example.MyApp#onCreate",
            SiteIdGenerator.forMethod("com/example/MyApp", "onCreate"),
        )
    }

    @Test fun `forMethod with default package`() {
        assertEquals(
            "MyApp#onCreate",
            SiteIdGenerator.forMethod("MyApp", "onCreate"),
        )
    }

    @Test fun `forMethod handles deeply nested package`() {
        assertEquals(
            "com.example.deep.nested.pkg.Type#method",
            SiteIdGenerator.forMethod("com/example/deep/nested/pkg/Type", "method"),
        )
    }

    @Test fun `forMethod handles inner class internal-dollar form`() {
        // Inner classes use $ in internal names; the conversion is just
        // slash→dot, so $ is preserved as-is.
        assertEquals(
            "com.example.Outer\$Inner#run",
            SiteIdGenerator.forMethod("com/example/Outer\$Inner", "run"),
        )
    }

    @Test fun `forMethod handles JVM-special init and clinit names`() {
        assertEquals("Foo#<init>", SiteIdGenerator.forMethod("Foo", "<init>"))
        assertEquals("Foo#<clinit>", SiteIdGenerator.forMethod("Foo", "<clinit>"))
    }

    @Test fun `forCall uses same format as forMethod`() {
        // Format symmetry is deliberate — the dashboard renders method
        // sites and call sites with the same UI, and many of them
        // happen to refer to the same JVM method (e.g. the entry
        // method of a Component init and a separate caller invoking
        // that same Component would both yield the same site id).
        assertEquals(
            "java.lang.String#valueOf",
            SiteIdGenerator.forCall("java/lang/String", "valueOf"),
        )
        assertEquals(
            SiteIdGenerator.forMethod("com/example/Foo", "bar"),
            SiteIdGenerator.forCall("com/example/Foo", "bar"),
        )
    }

    // ── forLoop ──────────────────────────────────────────────────────
    // forLoop is exercised end-to-end via DetailedTierTransformTest but
    // never directly unit-tested. These tests pin the canonical format
    // and ordinal stability so a typo in the suffix (e.g. "loop-1" or
    // "Loop_1") would fail loudly here rather than silently breaking
    // dashboard aggregation prefix matches.

    @Test fun `forLoop basic format owner method ordinal`() {
        assertEquals(
            "com.example.Foo#onCreate#loop_1",
            SiteIdGenerator.forLoop("com/example/Foo", "onCreate", 1),
        )
    }

    @Test fun `forLoop converts slashes to dots in owner`() {
        assertEquals(
            "com.example.deep.nested.Type#run#loop_2",
            SiteIdGenerator.forLoop("com/example/deep/nested/Type", "run", 2),
        )
    }

    @Test fun `forLoop preserves inner-class dollar separator`() {
        // Inner-class internal names use `$`, which is not a slash and
        // is therefore preserved through the slash-to-dot rewrite.
        assertEquals(
            "com.example.Foo\$Inner#run#loop_1",
            SiteIdGenerator.forLoop("com/example/Foo\$Inner", "run", 1),
        )
    }

    @Test fun `forLoop handles JVM-special init and clinit method names`() {
        assertEquals("Foo#<init>#loop_1", SiteIdGenerator.forLoop("Foo", "<init>", 1))
        assertEquals("Foo#<clinit>#loop_3", SiteIdGenerator.forLoop("Foo", "<clinit>", 3))
    }

    @Test fun `forLoop ordinal stability — distinct ordinals produce distinct site ids`() {
        // Multiple loops in the same method must each get a unique site
        // id so the dashboard can attribute time to the right loop. A
        // mutator that hard-coded `1` would collapse all loops onto a
        // single bucket; this test pins that escape.
        val s1 = SiteIdGenerator.forLoop("pkg/Cls", "m", 1)
        val s2 = SiteIdGenerator.forLoop("pkg/Cls", "m", 2)
        val s3 = SiteIdGenerator.forLoop("pkg/Cls", "m", 3)
        assertEquals(setOf("pkg.Cls#m#loop_1", "pkg.Cls#m#loop_2", "pkg.Cls#m#loop_3"),
            setOf(s1, s2, s3))
        // Pin them individually too, so a reordering mutator would fail
        // on a specific ordinal rather than just on set inequality.
        assertEquals("pkg.Cls#m#loop_1", s1)
        assertEquals("pkg.Cls#m#loop_2", s2)
        assertEquals("pkg.Cls#m#loop_3", s3)
    }

    @Test fun `forLoop default package — no slash in owner`() {
        assertEquals(
            "TopLevel#onCreate#loop_1",
            SiteIdGenerator.forLoop("TopLevel", "onCreate", 1),
        )
    }
}
