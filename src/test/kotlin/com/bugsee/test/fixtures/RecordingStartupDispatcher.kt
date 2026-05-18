package com.bugsee.test.fixtures

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test-only stand-in for `com.bugsee.library.adapters.BugseeAppStartupDispatcher`.
 *
 * Exposes the same six static {@code (String)V} entry points the plugin's
 * bytecode-injected INVOKESTATIC calls target, but records each call into
 * a list instead of buffering or dispatching anywhere. Tests configure the
 * AppStartupTracingClassVisitorFactory (via its `targetClass` parameter)
 * to use this dispatcher's FQN — `com.bugsee.test.fixtures.RecordingStartupDispatcher` —
 * as the INVOKESTATIC owner. The transformed bytecode then resolves
 * against this class at run time inside the [InMemoryClassLoader].
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
    }

    data class Event(
        val kind: Kind,
        val siteId: String,
        val threadId: Long,
    )
}
