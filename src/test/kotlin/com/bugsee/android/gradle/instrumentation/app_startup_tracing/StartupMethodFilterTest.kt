package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupMethodFilterTest {

    // ── candidateMethodsFor ──────────────────────────────────────────

    @Test fun `empty kinds → empty methods`() {
        assertTrue(StartupMethodFilter.candidateMethodsFor(emptySet()).isEmpty())
    }

    @Test fun `Application kind picks attachBaseContext + onCreate`() {
        val ms = StartupMethodFilter.candidateMethodsFor(setOf(ClassKind.APPLICATION))
        assertEquals(2, ms.size)
        assertTrue(MethodKey("attachBaseContext", "(Landroid/content/Context;)V") in ms)
        assertTrue(MethodKey("onCreate", "()V") in ms)
    }

    @Test fun `ContentProvider kind picks attachInfo + onCreate boolean`() {
        val ms = StartupMethodFilter.candidateMethodsFor(setOf(ClassKind.CONTENT_PROVIDER))
        assertEquals(2, ms.size)
        assertTrue(MethodKey(
            "attachInfo",
            "(Landroid/content/Context;Landroid/content/pm/ProviderInfo;)V"
        ) in ms)
        assertTrue(MethodKey("onCreate", "()Z") in ms)
    }

    @Test fun `Initializer kind picks create(Context)Object`() {
        val ms = StartupMethodFilter.candidateMethodsFor(setOf(ClassKind.INITIALIZER))
        assertEquals(setOf(MethodKey("create", "(Landroid/content/Context;)Ljava/lang/Object;")), ms)
    }

    @Test fun `ComponentRegistrar kind picks getComponents()List`() {
        val ms = StartupMethodFilter.candidateMethodsFor(setOf(ClassKind.COMPONENT_REGISTRAR))
        assertEquals(setOf(MethodKey("getComponents", "()Ljava/util/List;")), ms)
    }

    @Test fun `ConfigurationProvider kind picks getWorkManagerConfiguration()Configuration`() {
        val ms = StartupMethodFilter.candidateMethodsFor(setOf(ClassKind.CONFIGURATION_PROVIDER))
        assertEquals(
            setOf(MethodKey("getWorkManagerConfiguration", "()Landroidx/work/Configuration;")),
            ms,
        )
    }

    @Test fun `single-kind fast path returns the same set reference on repeated calls`() {
        // Mutation rationale: catches a regression where the single-kind
        // fast path is removed and a fresh LinkedHashSet is allocated per
        // call. Reference identity proves the implementation returns the
        // canonical per-kind table without copying — the documented
        // allocation-free behavior the perf review relied on.
        val a = StartupMethodFilter.candidateMethodsFor(setOf(ClassKind.APPLICATION))
        val b = StartupMethodFilter.candidateMethodsFor(setOf(ClassKind.APPLICATION))
        assertSame(a, b)
        // Same property for every other kind — guards against a partial
        // fast-path that only covers Application.
        for (kind in ClassKind.entries) {
            val first = StartupMethodFilter.candidateMethodsFor(setOf(kind))
            val second = StartupMethodFilter.candidateMethodsFor(setOf(kind))
            assertSame("single-kind fast path must return same ref for $kind", first, second)
        }
    }

    @Test fun `multi-kind path still allocates a fresh union set`() {
        // Sanity that the fast path doesn't accidentally short-circuit
        // the multi-kind union — multiple calls must yield equal
        // contents (the union is deterministic) but distinct instances.
        val a = StartupMethodFilter.candidateMethodsFor(setOf(
            ClassKind.APPLICATION, ClassKind.INITIALIZER,
        ))
        val b = StartupMethodFilter.candidateMethodsFor(setOf(
            ClassKind.APPLICATION, ClassKind.INITIALIZER,
        ))
        assertEquals(a, b)
        assertNotSame("multi-kind union must allocate a fresh set per call", a, b)
    }

    @Test fun `multiple kinds union their method sets`() {
        val ms = StartupMethodFilter.candidateMethodsFor(setOf(
            ClassKind.APPLICATION,
            ClassKind.INITIALIZER,
        ))
        // 2 Application + 1 Initializer = 3 unique methods
        assertEquals(3, ms.size)
        assertTrue(MethodKey("onCreate", "()V") in ms)
        assertTrue(MethodKey("create", "(Landroid/content/Context;)Ljava/lang/Object;") in ms)
    }

    @Test fun `Application + ContentProvider — both onCreates retained (different descriptors)`() {
        val ms = StartupMethodFilter.candidateMethodsFor(setOf(
            ClassKind.APPLICATION,
            ClassKind.CONTENT_PROVIDER,
        ))
        // The two onCreate methods have different descriptors (Application
        // returns void, ContentProvider returns boolean) — both must be
        // retained, the filter must not collapse them by name alone.
        assertTrue(MethodKey("onCreate", "()V") in ms)
        assertTrue(MethodKey("onCreate", "()Z") in ms)
        assertEquals(4, ms.size) // 2 Application + 2 ContentProvider
    }

    // ── isSuspendDescriptor ──────────────────────────────────────────

    @Test fun `suspend function descriptor recognized`() {
        // suspend fun foo(): Unit  →  (Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
        assertTrue(StartupMethodFilter.isSuspendDescriptor(
            "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"
        ))
    }

    @Test fun `suspend with extra params recognized`() {
        // suspend fun foo(s: String, i: Int): Unit
        assertTrue(StartupMethodFilter.isSuspendDescriptor(
            "(Ljava/lang/String;ILkotlin/coroutines/Continuation;)Ljava/lang/Object;"
        ))
    }

    @Test fun `Continuation as middle parameter NOT recognized as suspend`() {
        // Real method that just happens to take a Continuation. Not suspend
        // because Continuation must be the LAST parameter for the suspend
        // ABI.
        assertFalse(StartupMethodFilter.isSuspendDescriptor(
            "(Lkotlin/coroutines/Continuation;Ljava/lang/String;)Ljava/lang/Object;"
        ))
    }

    @Test fun `returns Object but no Continuation param NOT recognized`() {
        assertFalse(StartupMethodFilter.isSuspendDescriptor(
            "(Ljava/lang/String;)Ljava/lang/Object;"
        ))
    }

    @Test fun `takes Continuation but returns String NOT recognized`() {
        assertFalse(StartupMethodFilter.isSuspendDescriptor(
            "(Lkotlin/coroutines/Continuation;)Ljava/lang/String;"
        ))
    }

    @Test fun `void no-arg method NOT recognized`() {
        assertFalse(StartupMethodFilter.isSuspendDescriptor("()V"))
    }

    @Test fun `empty or malformed descriptor returns false`() {
        assertFalse(StartupMethodFilter.isSuspendDescriptor(""))
        assertFalse(StartupMethodFilter.isSuspendDescriptor("garbage"))
    }

    // ── kindForMethodKey ─────────────────────────────────────────────

    // Catches a mutation that swaps APPLICATION_METHODS entries into the wrong
    // table — e.g. moving `attachBaseContext` into CONTENT_PROVIDER_METHODS
    // would return CONTENT_PROVIDER instead of APPLICATION here.
    @Test fun `kindForMethodKey routes every APPLICATION_METHODS entry to APPLICATION`() {
        // Iterates the full APPLICATION_METHODS table via candidateMethodsFor
        // (which exposes it) — so this test stays in sync if a method is
        // added/removed without a hard-coded duplicate list.
        val appKeys = StartupMethodFilter.candidateMethodsFor(setOf(ClassKind.APPLICATION))
        assertTrue("expected APPLICATION_METHODS to be non-empty", appKeys.isNotEmpty())
        for (key in appKeys) {
            assertEquals(
                "APPLICATION method $key must route to APPLICATION kind",
                ClassKind.APPLICATION,
                StartupMethodFilter.kindForMethodKey(key),
            )
        }
    }

    // Catches a mutation that flips CONTENT_PROVIDER_METHODS entries into
    // another kind's table.
    @Test fun `kindForMethodKey routes every CONTENT_PROVIDER_METHODS entry to CONTENT_PROVIDER`() {
        val cpKeys = StartupMethodFilter.candidateMethodsFor(setOf(ClassKind.CONTENT_PROVIDER))
        assertTrue("expected CONTENT_PROVIDER_METHODS to be non-empty", cpKeys.isNotEmpty())
        for (key in cpKeys) {
            assertEquals(
                "CONTENT_PROVIDER method $key must route to CONTENT_PROVIDER kind",
                ClassKind.CONTENT_PROVIDER,
                StartupMethodFilter.kindForMethodKey(key),
            )
        }
    }

    // Catches a mutation that moves the INITIALIZER `create` key into a
    // different table.
    @Test fun `kindForMethodKey routes every INITIALIZER_METHODS entry to INITIALIZER`() {
        val initKeys = StartupMethodFilter.candidateMethodsFor(setOf(ClassKind.INITIALIZER))
        assertTrue("expected INITIALIZER_METHODS to be non-empty", initKeys.isNotEmpty())
        for (key in initKeys) {
            assertEquals(
                "INITIALIZER method $key must route to INITIALIZER kind",
                ClassKind.INITIALIZER,
                StartupMethodFilter.kindForMethodKey(key),
            )
        }
    }

    // Catches a mutation that moves the COMPONENT_REGISTRAR `getComponents`
    // key out of its table or into another kind's table.
    @Test fun `kindForMethodKey routes every COMPONENT_REGISTRAR_METHODS entry to COMPONENT_REGISTRAR`() {
        val crKeys = StartupMethodFilter.candidateMethodsFor(setOf(ClassKind.COMPONENT_REGISTRAR))
        assertTrue("expected COMPONENT_REGISTRAR_METHODS to be non-empty", crKeys.isNotEmpty())
        for (key in crKeys) {
            assertEquals(
                "COMPONENT_REGISTRAR method $key must route to COMPONENT_REGISTRAR kind",
                ClassKind.COMPONENT_REGISTRAR,
                StartupMethodFilter.kindForMethodKey(key),
            )
        }
    }

    // Catches a mutation that moves the CONFIGURATION_PROVIDER
    // `getWorkManagerConfiguration` key out of its table.
    @Test fun `kindForMethodKey routes every CONFIGURATION_PROVIDER_METHODS entry to CONFIGURATION_PROVIDER`() {
        val cpKeys = StartupMethodFilter.candidateMethodsFor(setOf(ClassKind.CONFIGURATION_PROVIDER))
        assertTrue("expected CONFIGURATION_PROVIDER_METHODS to be non-empty", cpKeys.isNotEmpty())
        for (key in cpKeys) {
            assertEquals(
                "CONFIGURATION_PROVIDER method $key must route to CONFIGURATION_PROVIDER kind",
                ClassKind.CONFIGURATION_PROVIDER,
                StartupMethodFilter.kindForMethodKey(key),
            )
        }
    }

    // Catches a mutation that collapses the descriptor disambiguation — e.g.
    // matching on name only would route `onCreate ()Z` to APPLICATION.
    @Test fun `kindForMethodKey disambiguates onCreate by descriptor`() {
        // Same name, different descriptors: must route to different kinds.
        assertEquals(
            ClassKind.APPLICATION,
            StartupMethodFilter.kindForMethodKey(MethodKey("onCreate", "()V")),
        )
        assertEquals(
            ClassKind.CONTENT_PROVIDER,
            StartupMethodFilter.kindForMethodKey(MethodKey("onCreate", "()Z")),
        )
    }

    // Catches a mutation that broadens membership (e.g. an `or true` slipped
    // into the `in` check) or accidentally caches the wrong default.
    @Test fun `kindForMethodKey returns null for non-candidate keys`() {
        assertNull(StartupMethodFilter.kindForMethodKey(MethodKey("compute", "()I")))
        assertNull(StartupMethodFilter.kindForMethodKey(MethodKey("foo", "()V")))
        assertNull(StartupMethodFilter.kindForMethodKey(MethodKey("", "")))
    }

    // Catches a mutation that loosens the lookup to name-only matching. The
    // `onCreate` name is in two tables (APPLICATION ()V, CONTENT_PROVIDER ()Z)
    // — a wrong descriptor like (I)V must miss both, not silently route to
    // APPLICATION.
    @Test fun `kindForMethodKey is descriptor-sensitive`() {
        // `onCreate (I)V` is neither in APPLICATION (which expects ()V) nor
        // CONTENT_PROVIDER (which expects ()Z) — must return null, NOT
        // APPLICATION.
        assertNull(StartupMethodFilter.kindForMethodKey(MethodKey("onCreate", "(I)V")))
        // `attachBaseContext` with the wrong descriptor (Object instead of
        // Context) must miss APPLICATION_METHODS.
        assertNull(StartupMethodFilter.kindForMethodKey(
            MethodKey("attachBaseContext", "(Ljava/lang/Object;)V")
        ))
        // `create` with no-args descriptor must miss INITIALIZER_METHODS.
        assertNull(StartupMethodFilter.kindForMethodKey(
            MethodKey("create", "()Ljava/lang/Object;")
        ))
    }
}
