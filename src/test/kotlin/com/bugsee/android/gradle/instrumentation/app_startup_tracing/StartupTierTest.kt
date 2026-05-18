package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTierTest {

    // ── parse ────────────────────────────────────────────────────────

    @Test fun `parse exact uppercase names`() {
        assertEquals(StartupTier.OFF, StartupTier.parse("OFF"))
        assertEquals(StartupTier.MINIMAL, StartupTier.parse("MINIMAL"))
        assertEquals(StartupTier.STANDARD, StartupTier.parse("STANDARD"))
        assertEquals(StartupTier.DETAILED, StartupTier.parse("DETAILED"))
        assertEquals(StartupTier.FULL, StartupTier.parse("FULL"))
    }

    @Test fun `parse is case-insensitive`() {
        assertEquals(StartupTier.STANDARD, StartupTier.parse("standard"))
        assertEquals(StartupTier.DETAILED, StartupTier.parse("Detailed"))
        assertEquals(StartupTier.FULL, StartupTier.parse("fUlL"))
    }

    @Test fun `parse trims whitespace`() {
        assertEquals(StartupTier.MINIMAL, StartupTier.parse("  MINIMAL  "))
        assertEquals(StartupTier.OFF, StartupTier.parse("OFF\t"))
    }

    @Test fun `parse returns null for unknown values`() {
        assertNull(StartupTier.parse("BOGUS"))
        assertNull(StartupTier.parse("standardd"))
        assertNull(StartupTier.parse("123"))
    }

    @Test fun `parse returns null for null or blank input`() {
        assertNull(StartupTier.parse(null))
        assertNull(StartupTier.parse(""))
        assertNull(StartupTier.parse("   "))
        assertNull(StartupTier.parse("\t\n"))
    }

    // ── DEFAULT ──────────────────────────────────────────────────────

    @Test fun `DEFAULT is STANDARD`() {
        assertEquals(StartupTier.STANDARD, StartupTier.DEFAULT)
    }

    // ── capability flags ─────────────────────────────────────────────

    @Test fun `wrapsMethods is false only for OFF`() {
        assertFalse(StartupTier.OFF.wrapsMethods())
        assertTrue(StartupTier.MINIMAL.wrapsMethods())
        assertTrue(StartupTier.STANDARD.wrapsMethods())
        assertTrue(StartupTier.DETAILED.wrapsMethods())
        assertTrue(StartupTier.FULL.wrapsMethods())
    }

    @Test fun `wrapsCalls starts at STANDARD`() {
        assertFalse(StartupTier.OFF.wrapsCalls())
        assertFalse(StartupTier.MINIMAL.wrapsCalls())
        assertTrue(StartupTier.STANDARD.wrapsCalls())
        assertTrue(StartupTier.DETAILED.wrapsCalls())
        assertTrue(StartupTier.FULL.wrapsCalls())
    }

    @Test fun `wrapsLoops starts at DETAILED`() {
        assertFalse(StartupTier.OFF.wrapsLoops())
        assertFalse(StartupTier.MINIMAL.wrapsLoops())
        assertFalse(StartupTier.STANDARD.wrapsLoops())
        assertTrue(StartupTier.DETAILED.wrapsLoops())
        assertTrue(StartupTier.FULL.wrapsLoops())
    }

    @Test fun `picksUpAnnotated only true at FULL`() {
        assertFalse(StartupTier.OFF.picksUpAnnotated())
        assertFalse(StartupTier.MINIMAL.picksUpAnnotated())
        assertFalse(StartupTier.STANDARD.picksUpAnnotated())
        assertFalse(StartupTier.DETAILED.picksUpAnnotated())
        assertTrue(StartupTier.FULL.picksUpAnnotated())
    }

    @Test fun `enum ordinal order reflects depth`() {
        // The capability flags use >= comparisons; assert the declaration
        // order is the depth order so future entries don't break the flags.
        val ordered = listOf(
            StartupTier.OFF,
            StartupTier.MINIMAL,
            StartupTier.STANDARD,
            StartupTier.DETAILED,
            StartupTier.FULL,
        )
        assertEquals(ordered, StartupTier.entries.toList())
    }
}
