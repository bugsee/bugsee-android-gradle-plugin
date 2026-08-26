package com.bugsee.android.gradle.instrumentation.util

import org.objectweb.asm.ClassReader

/**
 * Detects classes that R8 has already optimised, so no Bugsee instrumentation runs
 * over them.
 *
 * ### Why
 *
 * Rewriting R8 output is the single largest category of failure in comparable plugins:
 * `VerifyError` ("register vN has type Precise Reference: X but expected Reference: Y"),
 * `ArrayIndexOutOfBoundsException` inside the ASM transform, "Error while dexing", and
 * message-less NPEs — reported against Braze, Google Play Services, ML Kit,
 * androidx.startup, facebook-core and others. R8's output is legal bytecode, but it is
 * shaped in ways ad-hoc rewriting does not always survive.
 *
 * Our exposure is larger than most: `InstrumentationScope.ALL` means third-party AARs
 * are in scope, and the operation-dispatch and app-startup lanes rewrite *arbitrary*
 * methods rather than a fixed set of known third-party call sites.
 *
 * ### What is checked, and what deliberately is not
 *
 * Only R8's own `~~R8` marker — a self-declared signal that R8 wrote the class. A
 * class-NAME heuristic (lowercase first character, short-name regexes) is deliberately
 * NOT used: it misfires on legitimate short names, and the failure it produces is
 * silent skipping, which is undiagnosable from a consumer's side. A false negative here
 * costs one uninstrumented class; a false positive costs silent capture loss.
 *
 * Fails open throughout: if the pool cannot be read, the class is treated as ordinary
 * and instrumentation proceeds exactly as before.
 */
internal object MinifiedClassDetector {

    /** R8 writes this into the constant pool of classes it produces. */
    private const val R8_MARKER = "~~R8"

    /**
     * Constant pools can be very large and this runs for every class in the build, so
     * the scan is capped. R8 writes its marker early, so the cap is safe in practice —
     * but it is a real (documented, tested) false-negative boundary, not a free choice.
     */
    const val MAX_SCANNED_ENTRIES = 10

    fun isMinified(reader: ClassReader): Boolean {
        val limit = minOf(MAX_SCANNED_ENTRIES, reader.itemCount)
        for (i in 1 until limit) {
            val value = try {
                // Throws for pool slots that are not constants, and for the unused slot
                // following a long/double. Per-entry catch: one bad slot must not stop
                // the scan, and must never fail the build.
                reader.readConst(i, CharArray(reader.maxStringLength))
            } catch (t: Throwable) {
                continue
            }
            if (value is String && value.contains(R8_MARKER)) {
                return true
            }
        }
        return false
    }
}
