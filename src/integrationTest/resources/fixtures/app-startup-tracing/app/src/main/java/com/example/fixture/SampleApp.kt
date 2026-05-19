package com.example.fixture

import android.app.Application
import android.content.Context

/**
 * Subject under test for the kind-candidate (MINIMAL+) wraps. The plugin
 * should wrap `attachBaseContext` and `onCreate` with method spans.
 *
 * `onCreate` is also where the STANDARD/DETAILED feature flags get
 * exercised: it contains both top-level `INVOKE*` instructions (helper
 * calls — drives `onCallStart`/`onCallEnd` wraps at STANDARD+) and a
 * top-level `for` loop (drives `onLoopStart`/`onLoopEnd` wraps at
 * DETAILED+).
 *
 * Body is intentionally trivial — the goal is to verify the BYTECODE
 * shape after transform, not to execute it. The fixture is assembled and
 * the post-transform .class files inspected via ASM.
 */
class SampleApp : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()

        // STANDARD-tier coverage: top-level INVOKE* calls inside an
        // init method. These each get wrapped with
        // BugseeAppStartupDispatcher.onCallStart / onCallEnd.
        BodyWithLoopAndCalls.helperOne()
        BodyWithLoopAndCalls.helperTwo(42)

        // DETAILED-tier coverage: a top-level loop inside an init method.
        // Wrapped with BugseeAppStartupDispatcher.onLoopStart / onLoopEnd.
        var sum = 0
        for (i in 0..3) {
            sum += i
        }
        BodyWithLoopAndCalls.helperTwo(sum)
    }
}
