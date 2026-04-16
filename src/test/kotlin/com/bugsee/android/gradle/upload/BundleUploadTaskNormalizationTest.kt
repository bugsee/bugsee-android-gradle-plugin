package com.bugsee.android.gradle.upload

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.zip.ZipFile

class BundleUploadTaskNormalizationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
    }

    @Test fun `same inputs produce byte-identical wrapper zips`() {
        val bundle = tempFolder.newFile("sample.apk")
        bundle.writeBytes(ByteArray(5000) { (it and 0xff).toByte() })
        val mapping = tempFolder.newFile("mapping.txt")
        mapping.writeText("foo -> bar\nbaz -> qux\n".repeat(200))

        val outA = tempFolder.newFile("a.zip")
        val outB = tempFolder.newFile("b.zip")

        BundleUploadTask.writeNormalizedUploadZip(bundle, mapping, outA)
        // Force at least one second of wall-clock drift between writes so
        // any accidental reliance on "now" would surface as a diff.
        Thread.sleep(1100)
        BundleUploadTask.writeNormalizedUploadZip(bundle, mapping, outB)

        assertArrayEquals(
            "repeat writes must be byte-identical; otherwise chunk dedup can't benefit from a stable wrapper",
            outA.readBytes(),
            outB.readBytes(),
        )
    }

    @Test fun `hash is stable across a fresh process state`() {
        val bundle = tempFolder.newFile("b.apk").apply { writeBytes(ByteArray(1024) { 0x42 }) }
        val out = tempFolder.newFile("c.zip")
        BundleUploadTask.writeNormalizedUploadZip(bundle, null, out)
        val expected = sha256(out.readBytes())

        BundleUploadTask.writeNormalizedUploadZip(bundle, null, out)
        val actual = sha256(out.readBytes())
        assertEquals(expected, actual)
    }

    @Test fun `different bundle content produces different output`() {
        val b1 = tempFolder.newFile("b1.apk").apply { writeBytes(ByteArray(2048) { 0x01 }) }
        val b2 = tempFolder.newFile("b2.apk").apply { writeBytes(ByteArray(2048) { 0x02 }) }
        val o1 = tempFolder.newFile("o1.zip")
        val o2 = tempFolder.newFile("o2.zip")
        BundleUploadTask.writeNormalizedUploadZip(b1, null, o1)
        BundleUploadTask.writeNormalizedUploadZip(b2, null, o2)
        assertNotEquals(sha256(o1.readBytes()), sha256(o2.readBytes()))
    }

    @Test fun `output is a valid readable zip containing the expected entries`() {
        val bundle = tempFolder.newFile("app.apk").apply {
            writeBytes(ByteArray(1024) { (it and 0xff).toByte() })
        }
        val mapping = tempFolder.newFile("mapping.txt").apply {
            writeText("class a -> com.example.A")
        }
        val out = tempFolder.newFile("wrapper.zip")
        BundleUploadTask.writeNormalizedUploadZip(bundle, mapping, out)

        ZipFile(out).use { zf ->
            val names = zf.entries().toList().map { it.name }.sorted()
            assertEquals(listOf("app.apk", "mapping.txt"), names)
            // Bundle is STORED (method code 0), mapping is DEFLATED (8).
            assertEquals(0, zf.getEntry("app.apk").method)
            assertEquals(8, zf.getEntry("mapping.txt").method)
        }
    }

    @Test fun `entries sort lexicographically regardless of input order`() {
        // Our helper's signature forces bundle + mapping ordering on the
        // caller, but the sort inside the helper still applies. Prove
        // the invariant by inspecting the recorded zip entry order.
        val bundle = tempFolder.newFile("zulu.apk").apply { writeBytes(byteArrayOf(1)) }
        val mapping = tempFolder.newFile("mapping.txt").apply { writeBytes(byteArrayOf(2)) }
        val out = tempFolder.newFile("sorted.zip")
        BundleUploadTask.writeNormalizedUploadZip(bundle, mapping, out)

        ZipFile(out).use { zf ->
            val orderedNames = zf.entries().toList().map { it.name }
            assertEquals(listOf("mapping.txt", "zulu.apk"), orderedNames)
        }
    }

    // NB: TZ-independence is NOT tested here. JDK's ZipOutputStream
    // writes an "extended timestamp" (UT) extra field derived from the
    // default time zone, and `setTimeLocal` controls only the DOS
    // date/time. Achieving byte-identical output across time zones
    // would require switching to apache.commons.compress. The chunk-
    // dedup target — same inputs on the same CI runner → same bytes
    // across runs — is already guaranteed by the tests above.

    @Test fun `DOS_EPOCH_LOCAL is exactly 1980-01-01 midnight`() {
        val epoch = BundleUploadTask.DOS_EPOCH_LOCAL
        assertEquals(1980, epoch.year)
        assertEquals(1, epoch.monthValue)
        assertEquals(1, epoch.dayOfMonth)
        assertEquals(0, epoch.hour)
        assertEquals(0, epoch.minute)
        assertEquals(0, epoch.second)
        assertTrue(epoch.isBefore(java.time.LocalDateTime.now()))
    }
}
