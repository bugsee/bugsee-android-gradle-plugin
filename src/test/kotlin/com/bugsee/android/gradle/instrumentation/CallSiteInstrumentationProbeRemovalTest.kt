package com.bugsee.android.gradle.instrumentation

import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationContext
import com.bugsee.android.gradle.instrumentation.http_engine.HttpEngineClassVisitor
import com.bugsee.android.gradle.instrumentation.http_engine.HttpEngineClassVisitorFactory
import com.bugsee.android.gradle.instrumentation.log.LogClassVisitor
import com.bugsee.android.gradle.instrumentation.log.LogClassVisitorFactory
import com.bugsee.android.gradle.instrumentation.main_thread_misuse.MainThreadMisuseClassVisitor
import com.bugsee.android.gradle.instrumentation.main_thread_misuse.MainThreadMisuseClassVisitorFactory
import com.bugsee.android.gradle.instrumentation.okhttp.OkHttpClassVisitor
import com.bugsee.android.gradle.instrumentation.okhttp.OkHttpClassVisitorFactory
import com.bugsee.android.gradle.instrumentation.okhttp.OkHttpInstrumentationParameters
import com.bugsee.android.gradle.instrumentation.operation_dispatch.OperationDispatchClassVisitor
import com.bugsee.android.gradle.instrumentation.operation_dispatch.OperationDispatchClassVisitorFactory
import com.bugsee.android.gradle.instrumentation.thread.ThreadClassVisitor
import com.bugsee.android.gradle.instrumentation.thread.ThreadClassVisitorFactory
import org.gradle.api.provider.Property
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassVisitor
import org.gradle.testfixtures.ProjectBuilder
import org.objectweb.asm.Opcodes

private val noParams = UnsupportedOperationException("createClassVisitor must not read parameters")
private val noContext = UnsupportedOperationException("createClassVisitor must not read instrumentationContext")

/**
 * Guards that the call-site instrumentation factories do NOT re-probe the SDK's
 * presence per-class via `ClassContext.loadClassData`.
 *
 * **Regression context.** These factories (OkHttp, HttpEngine, Log,
 * MainThreadMisuse, OperationDispatch, Thread) used to gate `createClassVisitor`
 * on `classContext.loadClassData(<adapter FQN>) == null -> skip`. That probe is
 * unreliable across AGP's artifact-transform isolation boundaries: a class from a
 * third-party JAR is transformed on a classpath that cannot see the consumer's
 * `:library` dependency, so the probe returned `null` and silently skipped every
 * call site living inside third-party JARs (OkHttp/Cronet clients, `android.util.Log`
 * calls, guarded I/O, `HandlerThread` subclasses, ...). It happened to work for the
 * common case only because app-module call sites CAN see `:library`. SDK presence is
 * now gated once at configuration time in each `Instrumentation.shouldApply`. Same
 * failure mode that fully broke `ComposeInputClassVisitorFactory` (its target class
 * lives ONLY in a third-party JAR) — see [com.bugsee.android.gradle.instrumentation.compose_input.ComposeInputClassVisitorFactoryTest].
 *
 * Each test drives `createClassVisitor` with a fake [ClassContext] whose
 * `loadClassData` throws if consulted, and asserts the factory still wraps the
 * class (returns its visitor, not the pass-through `nextClassVisitor`).
 */
class CallSiteInstrumentationProbeRemovalTest {

    private val nextVisitor: ClassVisitor = object : ClassVisitor(Opcodes.ASM9) {}

    @Test
    fun `OkHttp wraps call-site classes without probing loadClassData`() {
        val ctx = throwingProbeContext("com.thirdparty.sdk.ApiClient")
        val result = TestOkHttp().createClassVisitor(ctx, nextVisitor)
        assertTrue(result is OkHttpClassVisitor)
        assertEquals(0, ctx.loadClassDataCallCount)
    }

    @Test
    fun `HttpEngine wraps call-site classes without probing loadClassData`() {
        val ctx = throwingProbeContext("com.thirdparty.sdk.CronetClient")
        val result = TestHttpEngine().createClassVisitor(ctx, nextVisitor)
        assertTrue(result is HttpEngineClassVisitor)
        assertEquals(0, ctx.loadClassDataCallCount)
    }

    @Test
    fun `Log wraps call-site classes without probing loadClassData`() {
        val ctx = throwingProbeContext("com.thirdparty.sdk.Logger")
        val result = TestLog().createClassVisitor(ctx, nextVisitor)
        assertTrue(result is LogClassVisitor)
        assertEquals(0, ctx.loadClassDataCallCount)
    }

    @Test
    fun `MainThreadMisuse wraps call-site classes without probing loadClassData`() {
        val ctx = throwingProbeContext("com.thirdparty.sdk.DiskCache")
        val result = TestMainThreadMisuse().createClassVisitor(ctx, nextVisitor)
        assertTrue(result is MainThreadMisuseClassVisitor)
        assertEquals(0, ctx.loadClassDataCallCount)
    }

    @Test
    fun `OperationDispatch wraps call-site classes without probing loadClassData`() {
        val ctx = throwingProbeContext("com.thirdparty.sdk.Db")
        val result = TestOperationDispatch().createClassVisitor(ctx, nextVisitor)
        assertTrue(result is OperationDispatchClassVisitor)
        assertEquals(0, ctx.loadClassDataCallCount)
    }

    @Test
    fun `Thread wraps call-site classes without probing loadClassData`() {
        // NB: this drives createClassVisitor directly, bypassing isInstrumentable
        // (which in production also requires Runnable/Thread on the class). We are
        // pinning the createClassVisitor gate, so the fixture's empty superClasses
        // is intentional.
        val ctx = throwingProbeContext("com.thirdparty.sdk.Worker")
        val result = TestThread().createClassVisitor(ctx, nextVisitor)
        assertTrue(result is ThreadClassVisitor)
        assertEquals(0, ctx.loadClassDataCallCount)
    }

    /**
     * Every instrumenting visitor must wrap and DELEGATE to the supplied
     * `nextClassVisitor`. Pins against a mutant that constructs the visitor
     * around a fresh throw-away `ClassVisitor` instead of `nextClassVisitor` —
     * that severs the chain (downstream ClassWriter receives nothing → corrupt
     * output) yet still passes the `instanceof` assertions above.
     */
    @Test
    fun `instrumenting visitors delegate class events to the next visitor`() {
        // OkHttp is checked separately below: its factory is parameterised by
        // OkHttpInstrumentationParameters, a subtype, so it does not fit this map.
        val factories = mapOf<String, com.android.build.api.instrumentation.AsmClassVisitorFactory<BugseeInstrumentationParameters>>(
            "HttpEngine" to TestHttpEngine(),
            "Log" to TestLog(),
            "MainThreadMisuse" to TestMainThreadMisuse(),
            "OperationDispatch" to TestOperationDispatch(),
            "Thread" to TestThread(),
        )
        for ((name, factory) in factories) {
            val recording = RecordingClassVisitor()
            val result = factory.createClassVisitor(throwingProbeContext("com.thirdparty.$name.Client"), recording)
            result.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/thirdparty/$name/Client",
                    null, "java/lang/Object", null)
            assertTrue("$name visitor must delegate to nextClassVisitor", recording.visited)
        }

        val okHttpRecording = RecordingClassVisitor()
        TestOkHttp().createClassVisitor(throwingProbeContext("com.thirdparty.OkHttp.Client"), okHttpRecording)
            .visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/thirdparty/OkHttp/Client",
                    null, "java/lang/Object", null)
        assertTrue("OkHttp visitor must delegate to nextClassVisitor", okHttpRecording.visited)
    }

    // ── test doubles ─────────────────────────────────────────────────────

    private fun throwingProbeContext(className: String) = FakeClassContext(
        current = FakeClassData(className),
        behavior = {
            throw AssertionError(
                "createClassVisitor must NOT probe ClassContext.loadClassData — " +
                        "it is unreliable across AGP transform-boundary isolation " +
                        "(third-party-JAR call sites cannot see the consumer's :library dep)."
            )
        },
    )

    // Concrete factories for tests. Only `createClassVisitor` is exercised; the
    // Gradle-managed `parameters` / `instrumentationContext` are never read on the
    // fixed code path, so they throw to catch any accidental new dependency.
    /**
     * Unlike its siblings this double supplies REAL parameters, because
     * OkHttp's createClassVisitor reads `webSocketCapture` from them (the
     * WebSocket rewrite is gated on the resolved SDK version). That is a
     * legitimate, long-established pattern — AppStartupTracing does the same.
     * The invariant this test exists to protect is the ClassContext probe, and
     * `throwingProbeContext` still enforces it.
     */
    private class TestOkHttp : OkHttpClassVisitorFactory() {
        private val objects = ProjectBuilder.builder().build().objects

        override val parameters: Property<OkHttpInstrumentationParameters> =
            objects.property(OkHttpInstrumentationParameters::class.java)
                .value(objects.newInstance(OkHttpInstrumentationParameters::class.java))

        override val instrumentationContext: InstrumentationContext get() = throw noContext
    }

    private class TestHttpEngine : HttpEngineClassVisitorFactory() {
        override val parameters: Property<BugseeInstrumentationParameters> get() = throw noParams
        override val instrumentationContext: InstrumentationContext get() = throw noContext
    }

    private class TestLog : LogClassVisitorFactory() {
        override val parameters: Property<BugseeInstrumentationParameters> get() = throw noParams
        override val instrumentationContext: InstrumentationContext get() = throw noContext
    }

    private class TestMainThreadMisuse : MainThreadMisuseClassVisitorFactory() {
        override val parameters: Property<BugseeInstrumentationParameters> get() = throw noParams
        override val instrumentationContext: InstrumentationContext get() = throw noContext
    }

    private class TestOperationDispatch : OperationDispatchClassVisitorFactory() {
        override val parameters: Property<BugseeInstrumentationParameters> get() = throw noParams
        override val instrumentationContext: InstrumentationContext get() = throw noContext
    }

    private class TestThread : ThreadClassVisitorFactory() {
        override val parameters: Property<BugseeInstrumentationParameters> get() = throw noParams
        override val instrumentationContext: InstrumentationContext get() = throw noContext
    }

    /** Records whether the wrapping visitor delegated `visit` downstream. */
    private class RecordingClassVisitor : ClassVisitor(Opcodes.ASM9) {
        var visited = false
            private set

        override fun visit(
            version: Int, access: Int, name: String?, signature: String?,
            superName: String?, interfaces: Array<out String>?,
        ) {
            visited = true
        }
    }

    private class FakeClassData(override val className: String) : ClassData {
        override val classAnnotations: List<String> = emptyList()
        override val interfaces: List<String> = emptyList()
        override val superClasses: List<String> = emptyList()
    }

    private class FakeClassContext(
        private val current: ClassData,
        private val behavior: (String) -> ClassData?,
    ) : ClassContext {
        var loadClassDataCallCount = 0
            private set

        override val currentClassData: ClassData get() = current

        override fun loadClassData(className: String): ClassData? {
            loadClassDataCallCount++
            return behavior(className)
        }
    }
}
