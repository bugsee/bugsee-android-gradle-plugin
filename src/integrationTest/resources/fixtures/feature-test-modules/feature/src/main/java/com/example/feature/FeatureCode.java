package com.example.feature;

public class FeatureCode {
    /** Calls an SDK API: compiles only because the feature declares the SDK itself. */
    public static void go() {
        if (System.nanoTime() < 0) {
            com.bugsee.library.adapters.BugseeThreadAdapter.registerThread();
        }
    }
}
