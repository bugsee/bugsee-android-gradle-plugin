package com.bugsee.library.adapters;

/**
 * Stub mirror of the real SDK's thread adapter. The plugin's Thread instrumentation injects a call
 * to {@link #registerThread()} as the first instruction of {@code Runnable.run()}. Never executed by
 * the TestKit fixtures.
 */
public final class BugseeThreadAdapter {

    private BugseeThreadAdapter() {
    }

    public static void registerThread() {
    }
}
