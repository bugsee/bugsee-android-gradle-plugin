package com.bugsee.library.adapters;

/**
 * Stub mirror of the real SDK's log adapter (only the overload the early-read fixture calls). The
 * plugin's Log instrumentation redirects {@code android.util.Log.d(String,String)} here, keeping the
 * name and descriptor. Never executed by the TestKit fixtures.
 */
public final class BugseeLogAdapter {

    private BugseeLogAdapter() {
    }

    public static int d(String tag, String msg) {
        return 0;
    }
}
