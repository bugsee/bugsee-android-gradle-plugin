package com.bugsee.library.adapters;

/**
 * Stub mirror of the real SDK class used by the plugin's TestKit integration
 * tests. Signatures MUST match the real SDK's
 * {@code BugseeAppStartupDispatcher} exactly — the plugin injects
 * {@code INVOKESTATIC} calls keyed on these names and {@code (Ljava/lang/String;)V}
 * descriptors. Any drift surfaces here as a compile error.
 *
 * <p>The methods are no-ops because the TestKit fixture is never executed
 * (it is just assembled and the post-transform bytecode is inspected). What
 * matters is that the class exists on the fixture's compile/runtime classpath
 * so the plugin's {@code ClassContext.loadClassData} probe succeeds and the
 * factory does not skip instrumentation.
 *
 * <p><b>DO NOT add or remove dispatch methods here without paired changes in
 * the SDK + plugin.</b>
 */
public final class BugseeAppStartupDispatcher {

    private BugseeAppStartupDispatcher() {
    }

    public static void onMethodStart(String siteId) {
    }

    public static void onMethodEnd(String siteId) {
    }

    public static void onCallStart(String siteId) {
    }

    public static void onCallEnd(String siteId) {
    }

    public static void onLoopStart(String siteId) {
    }

    public static void onLoopEnd(String siteId) {
    }
}
