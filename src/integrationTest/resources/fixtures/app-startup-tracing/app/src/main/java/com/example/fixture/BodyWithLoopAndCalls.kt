package com.example.fixture

import android.content.Context

/**
 * Subject under test for STANDARD-tier per-call wraps and DETAILED-tier
 * per-loop wraps. Has a method that contains BOTH top-level INVOKE* calls
 * and a top-level for-loop so a single method spans both feature flags.
 *
 * The plugin only wraps calls/loops INSIDE init-kind methods. This class
 * is intentionally NOT itself an init class (no Application/etc.) — it
 * exposes a static-style `runInitWork()` that `SampleApp.onCreate` calls.
 * So wraps on this class's methods should be ZERO; the call/loop wraps
 * land inside `SampleApp.onCreate` where the loop appears textually.
 *
 * Actually for the loop wrap to land inside `SampleApp.onCreate`, the loop
 * must be in that method. Since Kotlin compiles top-level functions into a
 * separate class, we use a *direct* loop inside SampleApp.onCreate via a
 * lambda-free for-each loop. To keep the wrap inside the actual init
 * method (SampleApp.onCreate), see `forceInitBody` below — it returns a
 * companion sentinel and is referenced inside SampleApp.onCreate. The
 * loop body lives in SampleApp.
 */
object BodyWithLoopAndCalls {
    @JvmStatic
    fun runInitWork() {
        // INVOKE* call site
        helperOne()
        // a small loop — when DETAILED tier wraps loops inside instrumented
        // methods, this would NOT be wrapped because BodyWithLoopAndCalls
        // is not itself an init class. Coverage of the loop wrap therefore
        // lives in SampleApp.onCreate (it contains the actual `for`).
        var s = 0
        for (i in 0..3) s += i
        helperTwo(s)
    }

    @JvmStatic
    fun helperOne() {
        // body intentionally trivial
    }

    @JvmStatic
    fun helperTwo(@Suppress("UNUSED_PARAMETER") x: Int) {
        // body intentionally trivial
    }

    @JvmStatic
    fun helperWithContext(@Suppress("UNUSED_PARAMETER") ctx: Context?) {
        // body intentionally trivial — used by TracedHelper to make sure
        // the FULL-tier annotated method body has at least one INVOKE*
        // (so the wrap injection has something to wrap around).
    }
}
