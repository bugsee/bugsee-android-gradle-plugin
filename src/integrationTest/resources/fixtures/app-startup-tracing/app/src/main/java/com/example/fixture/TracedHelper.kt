package com.example.fixture

import com.bugsee.library.contracts.performance.BugseeTrace

/**
 * Subject under test for FULL-tier annotation pickup. The plugin should
 * wrap `tracedWork()` with a method span at FULL tier — even though the
 * class is NOT an init kind — because the method carries `@BugseeTrace`.
 *
 * At any tier below FULL, this class should be untouched.
 */
class TracedHelper {

    @BugseeTrace
    fun tracedWork() {
        // Body contains a call so we can also assert that, at FULL tier,
        // the annotated method gets ONLY a method-level wrap — NO
        // automatic call wrap is injected (per the documented FULL tier
        // contract: "annotated methods: method start/end only — no auto
        // call/loop wrap").
        BodyWithLoopAndCalls.helperWithContext(null)
    }

    fun untracedWork() {
        // No annotation, not an init class → zero dispatcher calls at every tier.
        BodyWithLoopAndCalls.helperWithContext(null)
    }
}
