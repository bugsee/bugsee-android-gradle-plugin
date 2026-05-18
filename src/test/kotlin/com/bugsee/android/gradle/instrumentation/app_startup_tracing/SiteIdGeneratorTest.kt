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
}
