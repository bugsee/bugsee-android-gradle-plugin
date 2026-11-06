package com.bugsee.library.adapters;

/**
 * Stub mirror of the real SDK's operation dispatcher. The plugin's OperationDispatch instrumentation
 * wraps guarded calls (here: {@code URL.openStream},
 * {@code SharedPreferences.Editor.commit}) in {@code on<Category>OperationStart/End}. Its presence is also
 * what the symbol-availability gate probes for, so the OperationDispatch lane runs in EVERY stub-SDK
 * fixture (added with #97). Never executed by the fixtures.
 */
public final class BugseeOperationDispatcher {

    private BugseeOperationDispatcher() {
    }

    public static void onNetworkOperationStart(String a, String b) {
    }

    public static void onNetworkOperationEnd(String a, String b) {
    }

    public static void onPrefsOperationStart(String a, String b) {
    }

    public static void onPrefsOperationEnd(String a, String b) {
    }
}
