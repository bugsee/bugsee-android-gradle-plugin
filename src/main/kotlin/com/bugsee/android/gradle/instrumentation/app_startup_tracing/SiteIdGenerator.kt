package com.bugsee.android.gradle.instrumentation.app_startup_tracing

/**
 * Builds the {@code siteId} string that gets emitted as the
 * {@code (Ljava/lang/String;)V} argument to every
 * {@code BugseeAppStartupDispatcher.on*Start/End} call.
 *
 * The string is what the SDK records and what the dashboard groups by,
 * so it has to be both:
 *  - **stable across builds** — same source → same string, so dashboards
 *    can aggregate runs;
 *  - **human-readable** — the dashboard renders it directly with no
 *    decoding step.
 *
 * At MINIMAL tier (Phase 5) the only sites are whole methods; the
 * scheme is {@code "<owner-dot-name>#<method-name>"}, e.g.
 * {@code "com.example.MyApplication#onCreate"}. Line numbers, call
 * sites, and loop indices are added at higher tiers.
 */
internal object SiteIdGenerator {

    /**
     * @param ownerInternalName JVM internal name with slashes
     * (e.g. {@code "com/example/MyApp"})
     * @param methodName the JVM method name
     */
    fun forMethod(ownerInternalName: String, methodName: String): String {
        val ownerDotted = ownerInternalName.replace('/', '.')
        return "$ownerDotted#$methodName"
    }

    /**
     * Site ID for a wrapped {@code INVOKE*} call at STANDARD tier and
     * above. Identifies the **callee**, not the call site — so all calls
     * to {@code java.lang.String#length} aggregate together in the
     * dashboard. The span tree's parent already carries the calling
     * context, so the encoded site ID does not need to.
     *
     * @param ownerInternalName JVM internal name of the call's owner
     * class (e.g. {@code "java/lang/String"})
     * @param methodName the called method's name
     */
    fun forCall(ownerInternalName: String, methodName: String): String {
        val ownerDotted = ownerInternalName.replace('/', '.')
        return "$ownerDotted#$methodName"
    }

    /**
     * Site ID for a wrapped top-level loop at DETAILED tier and above.
     * Includes a per-method ordinal so multiple loops in the same method
     * are distinguishable: the first detected loop gets ordinal 1, the
     * next gets 2, etc. Ordinals are assigned in detection order
     * (typically source order for typical compiled code) but are NOT
     * guaranteed stable across SDK / plugin / compiler version changes —
     * dashboards that need cross-build aggregation should rely on
     * {@code site_id} prefix matching the enclosing method, not on the
     * loop ordinal alone.
     *
     * @param ownerInternalName JVM internal name of the enclosing class
     * @param methodName name of the enclosing method
     * @param ordinal 1-based index of the loop within the method
     */
    fun forLoop(ownerInternalName: String, methodName: String, ordinal: Int): String {
        val ownerDotted = ownerInternalName.replace('/', '.')
        return "$ownerDotted#$methodName#loop_$ordinal"
    }
}
