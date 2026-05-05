package com.bugsee.android.gradle.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SizeCheckEvaluatorTest {

    private val empty = SizeCheckEvaluator.Thresholds(null, null, null, null)

    // ── anyActive ─────────────────────────────────────────────────

    @Test fun `anyActive is false when every threshold is null`() {
        assertFalse(empty.anyActive)
    }

    @Test fun `anyActive is true when at least one threshold is set`() {
        assertTrue(empty.copy(warningPercent = 5.0).anyActive)
        assertTrue(empty.copy(failPercent    = 10.0).anyActive)
        assertTrue(empty.copy(warningBytes   = 1).anyActive)
        assertTrue(empty.copy(failBytes      = 1).anyActive)
    }

    // ── PASS path ──────────────────────────────────────────────────

    @Test fun `evaluate returns PASS for shrunk artifact regardless of thresholds`() {
        // Even with absurdly low thresholds, a shrink must never
        // trigger — the whole feature is about catching growth.
        val r = SizeCheckEvaluator.evaluate(
            localSize = 9_000_000,
            baselineSize = 10_000_000,
            thresholds = empty.copy(warningPercent = 0.001, failPercent = 0.001,
                                     warningBytes = 1L, failBytes = 1L),
        )
        assertEquals(SizeCheckEvaluator.Outcome.PASS, r.outcome)
        assertEquals(-1_000_000L, r.deltaBytes)
        assertNull(r.triggeredBy)
    }

    @Test fun `evaluate returns PASS when no threshold is configured`() {
        val r = SizeCheckEvaluator.evaluate(
            localSize = 12_000_000,
            baselineSize = 10_000_000,
            thresholds = empty,
        )
        assertEquals(SizeCheckEvaluator.Outcome.PASS, r.outcome)
    }

    @Test fun `evaluate handles zero baseline by returning a sane PASS`() {
        // Defensive: a server bug could in theory return a zero
        // baseline. Don't fail the build on it.
        val r = SizeCheckEvaluator.evaluate(
            localSize = 12_000_000,
            baselineSize = 0,
            thresholds = empty.copy(failPercent = 1.0),
        )
        assertEquals(SizeCheckEvaluator.Outcome.PASS, r.outcome)
    }

    // ── WARN path ──────────────────────────────────────────────────

    @Test fun `evaluate triggers WARN when delta crosses warningPercent`() {
        // 12 MB / 10 MB → +20%. With warningPercent = 5, FAIL not set,
        // we expect WARN.
        val r = SizeCheckEvaluator.evaluate(
            localSize = 12_000_000,
            baselineSize = 10_000_000,
            thresholds = empty.copy(warningPercent = 5.0),
        )
        assertEquals(SizeCheckEvaluator.Outcome.WARN, r.outcome)
        assertTrue(r.triggeredBy?.startsWith("warningPercent") ?: false)
    }

    @Test fun `evaluate triggers WARN when delta crosses warningBytes only`() {
        val r = SizeCheckEvaluator.evaluate(
            localSize = 10_001_000,
            baselineSize = 10_000_000,
            thresholds = empty.copy(warningBytes = 500L),
        )
        assertEquals(SizeCheckEvaluator.Outcome.WARN, r.outcome)
        assertTrue(r.triggeredBy?.startsWith("warningBytes") ?: false)
    }

    // ── FAIL path ──────────────────────────────────────────────────

    @Test fun `evaluate triggers FAIL when delta crosses failPercent`() {
        val r = SizeCheckEvaluator.evaluate(
            localSize = 12_000_000,
            baselineSize = 10_000_000,
            thresholds = empty.copy(failPercent = 10.0),
        )
        assertEquals(SizeCheckEvaluator.Outcome.FAIL, r.outcome)
        assertTrue(r.triggeredBy?.startsWith("failPercent") ?: false)
    }

    @Test fun `evaluate prefers FAIL over WARN when both gates would trigger`() {
        // +20% ≥ 5% (warn) AND ≥ 10% (fail). Result must be FAIL —
        // the user wants the strongest signal, not the first one
        // crossed.
        val r = SizeCheckEvaluator.evaluate(
            localSize = 12_000_000,
            baselineSize = 10_000_000,
            thresholds = empty.copy(warningPercent = 5.0, failPercent = 10.0),
        )
        assertEquals(SizeCheckEvaluator.Outcome.FAIL, r.outcome)
    }

    @Test fun `evaluate prefers FAIL on bytes when percent would only WARN`() {
        // +0.5% (within warningPercent 5%) but +500 KB (above failBytes
        // 100 KB). Bytes gate wins → FAIL.
        val r = SizeCheckEvaluator.evaluate(
            localSize = 10_500_000,
            baselineSize = 10_000_000,
            thresholds = empty.copy(warningPercent = 5.0, failBytes = 100_000L),
        )
        assertEquals(SizeCheckEvaluator.Outcome.FAIL, r.outcome)
        assertTrue(r.triggeredBy?.startsWith("failBytes") ?: false)
    }

    // ── delta math ─────────────────────────────────────────────────

    @Test fun `evaluate computes percent delta against baseline`() {
        val r = SizeCheckEvaluator.evaluate(
            localSize = 11_000_000,
            baselineSize = 10_000_000,
            thresholds = empty,
        )
        assertEquals(1_000_000L, r.deltaBytes)
        assertEquals(10.0, r.deltaPercent, 1e-9)
    }

    // ── log message format ─────────────────────────────────────────

    @Test fun `formatMessage names the offending threshold for WARN`() {
        val r = SizeCheckEvaluator.Result(
            deltaBytes = 1_200_000,
            deltaPercent = 8.3,
            outcome = SizeCheckEvaluator.Outcome.WARN,
            triggeredBy = "warningPercent 5%",
        )
        val msg = SizeCheckEvaluator.formatMessage(
            localSize = 15_400_000,
            baselineSize = 14_200_000,
            baselineVersion = "1.4.2",
            baselineBuild = "318",
            result = r,
        )
        assertTrue(msg.contains("Bugsee size check"))
        assertTrue(msg.contains("version 1.4.2"))
        assertTrue(msg.contains("(318)"))
        assertTrue(msg.contains("+8.3%"))
        assertTrue(msg.contains("warningPercent 5%"))
    }

    @Test fun `formatMessage falls back to 'previous build' when version metadata missing`() {
        val r = SizeCheckEvaluator.Result(0, 0.0, SizeCheckEvaluator.Outcome.PASS, null)
        val msg = SizeCheckEvaluator.formatMessage(
            localSize = 100,
            baselineSize = 100,
            baselineVersion = null,
            baselineBuild = null,
            result = r,
        )
        assertTrue(msg.contains("vs previous build"))
    }

    @Test fun `formatBytes renders compact KB MB GB`() {
        assertEquals("512 B",  SizeCheckEvaluator.formatBytes(512))
        assertEquals("1.5 KB", SizeCheckEvaluator.formatBytes(1_536))
        assertEquals("1.0 MB", SizeCheckEvaluator.formatBytes(1_048_576))
        assertEquals("1.50 GB", SizeCheckEvaluator.formatBytes(1_610_612_736))
    }

    @Test fun `formatBytes uses period as decimal separator regardless of host locale`() {
        // Set the JVM default locale to one that uses comma as the
        // decimal separator. The formatter must still produce
        // periods so log-grep patterns relying on `+\d+\.\d+` keep
        // matching on non-en CI hosts (e.g. de_DE, fr_FR).
        val originalLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("1.5 KB", SizeCheckEvaluator.formatBytes(1_536))
            assertEquals("1.0 MB", SizeCheckEvaluator.formatBytes(1_048_576))
        } finally {
            java.util.Locale.setDefault(originalLocale)
        }
    }

    // ── threshold edge cases (zero delta, NaN, infinity) ───────────

    @Test fun `evaluate returns PASS when localSize equals baseline`() {
        // Direct assertion of the zero-delta branch — currently
        // covered transitively via the `<= 0` guard but worth an
        // explicit test so a future "delta == 0 should warn" change
        // would surface here rather than slipping through.
        val r = SizeCheckEvaluator.evaluate(
            localSize = 10_000_000,
            baselineSize = 10_000_000,
            thresholds = empty.copy(failPercent = 1.0, warningPercent = 0.5),
        )
        assertEquals(SizeCheckEvaluator.Outcome.PASS, r.outcome)
        assertEquals(0L, r.deltaBytes)
        assertNull(r.triggeredBy)
    }
}
