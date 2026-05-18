package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
