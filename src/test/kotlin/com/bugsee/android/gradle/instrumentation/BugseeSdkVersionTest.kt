package com.bugsee.android.gradle.instrumentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [BugseeSdkVersion]'s parser and semver-flavored ordering.
 *
 * The parser feeds the SDK-version gate in
 * `AppStartupTracingInstrumentation.shouldApply`. A bug here would
 * either (a) silently permit a too-old SDK to pair with the plugin —
 * `NoClassDefFoundError` at app launch — or (b) wrongly refuse a
 * valid version and disable instrumentation. Both are user-visible.
 *
 * Mutation rationale per test is called out inline.
 */
class BugseeSdkVersionTest {

    @Test
    fun parse_pinnedStableVersion_returnsAllZeroPreRelease() {
        val v = BugseeSdkVersion.parse("7.0.0")
        assertNotNull(v)
        assertEquals(7, v!!.major)
        assertEquals(0, v.minor)
        assertEquals(0, v.patch)
        assertEquals("", v.preLabel)
        // No pre-release suffix → preNumber stays at -1 sentinel.
        assertEquals(-1, v.preNumber)
    }

    @Test
    fun parse_betaWithNumber_capturesLabelAndNumber() {
        // Catches mutations: numeric suffix dropped, label captured as
        // "beta11", or split incorrectly.
        val v = BugseeSdkVersion.parse("7.0.0-beta11")
        assertNotNull(v)
        assertEquals("beta", v!!.preLabel)
        assertEquals(11, v.preNumber)
    }

    @Test
    fun parse_betaWithoutNumber_returnsMinusOne() {
        // Catches a mutation that defaults preNumber to 0 (which would
        // incorrectly tie "alpha" with "alpha0" → may flip ordering).
        val v = BugseeSdkVersion.parse("7.0.0-alpha")
        assertNotNull(v)
        assertEquals("alpha", v!!.preLabel)
        assertEquals(-1, v.preNumber)
    }

    @Test
    fun parse_snapshotSuffix_stripsAndKeepsBaseVersion() {
        // Catches a mutation that fails to strip -SNAPSHOT, then the
        // regex returns null and the gate skips the check entirely.
        val v = BugseeSdkVersion.parse("7.0.0-beta11-SNAPSHOT")
        assertNotNull(v)
        assertEquals(7, v!!.major)
        assertEquals("beta", v.preLabel)
        assertEquals(11, v.preNumber)
    }

    @Test
    fun parse_localSuffix_stripsAndKeepsBaseVersion() {
        val v = BugseeSdkVersion.parse("7.0.0-beta11-LOCAL")
        assertNotNull(v)
        assertEquals("beta", v!!.preLabel)
        assertEquals(11, v.preNumber)
    }

    @Test
    fun parse_unparseableForms_returnNull() {
        // Gradle ranges, version-catalog placeholders, project-dep
        // sentinels all parse to null → caller treats permissively.
        // Catches a mutation that returns a default v=(0,0,0,…) for
        // bad input, which would always be < min and incorrectly
        // refuse instrumentation for these legitimate cases.
        assertNull(BugseeSdkVersion.parse(""))
        assertNull(BugseeSdkVersion.parse("   "))
        assertNull(BugseeSdkVersion.parse(null))
        assertNull(BugseeSdkVersion.parse("7.+"))
        assertNull(BugseeSdkVersion.parse("[7.0.0,8.0.0)"))
        assertNull(BugseeSdkVersion.parse("latest.release"))
    }

    @Test
    fun compare_stableOutranksAnyPreRelease() {
        // Semver §11.4.4. Catches a mutation that returns 0 when one
        // side has empty preLabel — would let "7.0.0-beta11" compare
        // equal to "7.0.0".
        val stable = BugseeSdkVersion.parse("7.0.0")!!
        val beta = BugseeSdkVersion.parse("7.0.0-beta11")!!
        assertTrue("stable > beta", stable > beta)
        assertTrue("beta < stable", beta < stable)
    }

    @Test
    fun compare_higherMajorWinsOverPreReleaseAdvantage() {
        // 8.0.0-alpha > 7.0.0 because MAJOR diff trumps everything.
        // Catches a mutation that reorders compareTo to check the
        // pre-release before MAJOR.
        val v8a = BugseeSdkVersion.parse("8.0.0-alpha")!!
        val v7 = BugseeSdkVersion.parse("7.0.0")!!
        assertTrue("8.0.0-alpha > 7.0.0", v8a > v7)
    }

    @Test
    fun compare_betaNumberIsNumericNotLexicographic() {
        // beta2 < beta11 numerically; "2" > "11" lexicographically.
        // Catches a mutation that compares preNumber as String.
        val beta2 = BugseeSdkVersion.parse("7.0.0-beta2")!!
        val beta11 = BugseeSdkVersion.parse("7.0.0-beta11")!!
        assertTrue("beta2 < beta11 numerically", beta2 < beta11)
    }

    @Test
    fun compare_alphaLessThanBetaLessThanRc() {
        // Alphabetical ordering on preLabel matches the conventional
        // pre-release progression alpha < beta < rc. Catches a
        // mutation that reverses the label comparator.
        val alpha = BugseeSdkVersion.parse("7.0.0-alpha1")!!
        val beta = BugseeSdkVersion.parse("7.0.0-beta1")!!
        val rc = BugseeSdkVersion.parse("7.0.0-rc1")!!
        assertTrue("alpha < beta", alpha < beta)
        assertTrue("beta < rc", beta < rc)
    }

    @Test
    fun compare_patchLevelOrders() {
        // Sanity for the MINOR/PATCH fields. Catches a mutation that
        // swaps minor.compareTo / patch.compareTo.
        val v700 = BugseeSdkVersion.parse("7.0.0")!!
        val v701 = BugseeSdkVersion.parse("7.0.1")!!
        val v710 = BugseeSdkVersion.parse("7.1.0")!!
        assertTrue(v700 < v701)
        assertTrue(v701 < v710)
    }

    @Test
    fun gate_minVersionAcceptsExactMatch() {
        // The plugin's MIN_SDK_VERSION_WITH_DISPATCHER is 7.0.0-beta11.
        // Pinning the exact min must NOT trigger the warning.
        val min = BugseeSdkVersion.parse("7.0.0-beta11")!!
        val candidate = BugseeSdkVersion.parse("7.0.0-beta11")!!
        assertTrue("exact min must not be < min", candidate >= min)
    }

    @Test
    fun gate_olderBetaRejected() {
        // 7.0.0-beta10 predates the dispatcher introduction.
        val min = BugseeSdkVersion.parse("7.0.0-beta11")!!
        val older = BugseeSdkVersion.parse("7.0.0-beta10")!!
        assertTrue("beta10 must be < beta11", older < min)
    }

    @Test
    fun gate_postReleaseStableAccepted() {
        // 7.0.0 (final release) > any 7.0.0-betaN. The gate must let
        // it through.
        val min = BugseeSdkVersion.parse("7.0.0-beta11")!!
        val stable = BugseeSdkVersion.parse("7.0.0")!!
        assertTrue("stable >= min", stable >= min)
    }

    @Test
    fun parse_semverBuildMetadata_returnsNull() {
        // The Bugsee SDK ships MAJOR.MINOR.PATCH[-labelN] exclusively;
        // semver build metadata (+sha.abc) is intentionally NOT
        // supported. Pin the behavior so a future regex widening
        // doesn't silently start accepting these — at which point
        // the comparator's ordering semantics on the build-metadata
        // field would need explicit thought (semver §10: build
        // metadata MUST be ignored when ordering). Catches a regex
        // mutation that adds a `[+...]?` suffix grouping without the
        // accompanying compareTo work.
        assertNull(BugseeSdkVersion.parse("7.0.0+build.1"))
        assertNull(BugseeSdkVersion.parse("7.0.0+sha.abc"))
        assertNull(BugseeSdkVersion.parse("7.0.0-beta11+build.42"))
    }

    @Test
    fun compare_doubleDigitComponentsOrderNumerically() {
        // 10.0.0 > 9.0.0 numerically; "10" < "9" lexicographically.
        // Catches a comparator mutation that treats the major /
        // minor / patch components as Strings instead of Ints —
        // single-digit fixtures would pass silently.
        val v9 = BugseeSdkVersion.parse("9.0.0")!!
        val v10 = BugseeSdkVersion.parse("10.0.0")!!
        assertTrue("10.0.0 > 9.0.0 (numeric, not lexical)", v10 > v9)

        val v009 = BugseeSdkVersion.parse("0.0.9")!!
        val v0010 = BugseeSdkVersion.parse("0.0.10")!!
        assertTrue("0.0.10 > 0.0.9 on patch", v0010 > v009)

        val v090 = BugseeSdkVersion.parse("0.9.0")!!
        val v0100 = BugseeSdkVersion.parse("0.10.0")!!
        assertTrue("0.10.0 > 0.9.0 on minor", v0100 > v090)
    }

    @Test
    fun toString_rendersCanonicalShape() {
        // The data-class default toString() dumps the field structure
        // (`BugseeSdkVersion(major=7, minor=0, ...)`); we override it
        // to render the parseable shape so user-facing logs (e.g. the
        // shouldApply warn message) read cleanly. Catches a mutation
        // that removes the toString override and leaks data-class
        // internals into the user log.
        assertEquals("7.0.0-beta11", BugseeSdkVersion.parse("7.0.0-beta11")!!.toString())
        assertEquals("7.0.0", BugseeSdkVersion.parse("7.0.0")!!.toString())
        assertEquals("7.0.0-alpha", BugseeSdkVersion.parse("7.0.0-alpha")!!.toString())
        assertEquals("10.20.30-rc5",
                BugseeSdkVersion.parse("10.20.30-rc5")!!.toString())
        // Snapshot suffixes are stripped on parse — toString renders
        // the canonical parsed form, NOT the original input.
        assertEquals("7.0.0-beta11",
                BugseeSdkVersion.parse("7.0.0-beta11-SNAPSHOT")!!.toString())
    }
}
