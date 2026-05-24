package com.bugsee.android.gradle.instrumentation.extensions_init

/**
 * Test-only registration sink used by [ExtensionsInitClassVisitorTest].
 * The visitor under test emits `INVOKESTATIC` calls to whatever facade
 * class names the test passes in via [ExtensionSpec]; pointing those at
 * this object lets the test load the transformed class and assert which
 * methods actually ran (and in what order).
 *
 * Each method is `@JvmStatic` so the JVM resolves the bytecode-emitted
 * static call without needing a singleton load.
 */
object RecordingRegister {

    private val callsInternal: MutableList<String> = mutableListOf()

    val calls: List<String>
        get() = callsInternal.toList()

    @JvmStatic
    fun registerFooExtension() {
        callsInternal.add("foo")
    }

    @JvmStatic
    fun registerBarExtension() {
        callsInternal.add("bar")
    }

    @JvmStatic
    fun registerBazExtension() {
        callsInternal.add("baz")
    }

    /**
     * Always throws — used to verify the visitor's per-call try/catch
     * actually contains the failure to the bad extension and lets the
     * remainder of the registration list run.
     */
    @JvmStatic
    fun registerExplodingExtension() {
        callsInternal.add("exploding-entered")
        throw RuntimeException("boom from RecordingRegister.registerExplodingExtension")
    }

    fun reset() {
        callsInternal.clear()
    }
}
