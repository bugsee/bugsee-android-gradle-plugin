package com.bugsee.test.fixtures

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test-only stand-in for `com.bugsee.library.adapters.BugseeAppStartupDispatcher`.
 *
 * Exposes the same fourteen static {@code (String)V} entry points the
 * plugin's bytecode-injected INVOKESTATIC calls target — six generic
 * (`onMethodStart/End`, `onCallStart/End`, `onLoopStart/End`) plus eight
 * kind-distinguished (issue 2): `onProviderStart/End`,
 * `onApplicationStart/End`, `onActivityStart/End`, `onAnnotatedStart/End`.
 * Each call is recorded into a list instead of buffered or dispatched
 * anywhere. Tests configure the AppStartupTracingClassVisitorFactory
 * (via its `targetClass` parameter) to use this dispatcher's FQN —
 * `com.bugsee.test.fixtures.RecordingStartupDispatcher` — as the
 * INVOKESTATIC owner. The transformed bytecode then resolves against
 * this class at run time inside the [InMemoryClassLoader].
 *
 * <p>Note: `onActivityStart/End` exist for signature-completeness with
 * the SDK contract but are NOT reachable from plugin-emitted bytecode
 * (Activity events are self-emitted at runtime by the SDK's
 * `StartupLifecycleTracker`, not via the plugin). They're present so a
 * future plugin revision that decides to bytecode-inject Activity hooks
 * doesn't need a fixture update.
 *
 * Companion object methods are exposed as JVM static via `@JvmStatic` so
 * the bytecode signature matches the real dispatcher exactly.
 *
 * Tests are responsible for calling [reset] in a `@Before` / `@After`
 * since the recorded events live on a JVM-static [CopyOnWriteArrayList].
 */
class RecordingStartupDispatcher {

    companion object {
        /**
         * Thread-safe append-only event log. Cleared by [reset]. Reads
         * from tests are lock-free; writes from bytecode-injected calls
         * use the underlying [CopyOnWriteArrayList] semantics.
         */
        private val sEvents: CopyOnWriteArrayList<Event> = CopyOnWriteArrayList()

        @JvmStatic fun onMethodStart(siteId: String) {
            sEvents.add(Event(Kind.METHOD_START, siteId, Thread.currentThread().id))
        }

        @JvmStatic fun onMethodEnd(siteId: String) {
            sEvents.add(Event(Kind.METHOD_END, siteId, Thread.currentThread().id))
        }

        @JvmStatic fun onCallStart(siteId: String) {
            sEvents.add(Event(Kind.CALL_START, siteId, Thread.currentThread().id))
        }

        @JvmStatic fun onCallEnd(siteId: String) {
            sEvents.add(Event(Kind.CALL_END, siteId, Thread.currentThread().id))
        }

        @JvmStatic fun onLoopStart(siteId: String) {
            sEvents.add(Event(Kind.LOOP_START, siteId, Thread.currentThread().id))
        }

        @JvmStatic fun onLoopEnd(siteId: String) {
            sEvents.add(Event(Kind.LOOP_END, siteId, Thread.currentThread().id))
        }

        // Kind-distinguished entry points (issue 2). Tests asserting on
        // these record them under matching Kind enum values; tests that
        // only care about wrap-count / pairing can use the helpers below
        // to flatten kind variants into a single category.
        @JvmStatic fun onProviderStart(siteId: String) {
            sEvents.add(Event(Kind.PROVIDER_START, siteId, Thread.currentThread().id))
        }

        @JvmStatic fun onProviderEnd(siteId: String) {
            sEvents.add(Event(Kind.PROVIDER_END, siteId, Thread.currentThread().id))
        }

        @JvmStatic fun onApplicationStart(siteId: String) {
            sEvents.add(Event(Kind.APPLICATION_START, siteId, Thread.currentThread().id))
        }

        @JvmStatic fun onApplicationEnd(siteId: String) {
            sEvents.add(Event(Kind.APPLICATION_END, siteId, Thread.currentThread().id))
        }

        @JvmStatic fun onActivityStart(siteId: String) {
            sEvents.add(Event(Kind.ACTIVITY_START, siteId, Thread.currentThread().id))
        }

        @JvmStatic fun onActivityEnd(siteId: String) {
            sEvents.add(Event(Kind.ACTIVITY_END, siteId, Thread.currentThread().id))
        }

        @JvmStatic fun onAnnotatedStart(siteId: String) {
            sEvents.add(Event(Kind.ANNOTATED_START, siteId, Thread.currentThread().id))
        }

        @JvmStatic fun onAnnotatedEnd(siteId: String) {
            sEvents.add(Event(Kind.ANNOTATED_END, siteId, Thread.currentThread().id))
        }

        /** Snapshot of recorded events, oldest first. */
        fun events(): List<Event> = sEvents.toList()

        /** Clears the recorded events. Call this in `@Before` and `@After`. */
        fun reset() {
            sEvents.clear()
        }

        /** Returns the number of recorded events. */
        fun size(): Int = sEvents.size
    }

    enum class Kind {
        METHOD_START, METHOD_END,
        CALL_START, CALL_END,
        LOOP_START, LOOP_END,
        PROVIDER_START, PROVIDER_END,
        APPLICATION_START, APPLICATION_END,
        ACTIVITY_START, ACTIVITY_END,
        ANNOTATED_START, ANNOTATED_END,
    }

    data class Event(
        val kind: Kind,
        val siteId: String,
        val threadId: Long,
    )
}
