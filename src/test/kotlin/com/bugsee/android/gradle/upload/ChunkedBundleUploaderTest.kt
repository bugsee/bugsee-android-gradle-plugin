package com.bugsee.android.gradle.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ChunkedBundleUploaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
    }

    @Test fun `empty file produces no chunks`() {
        val f = tempFolder.newFile("empty.bin")
        val hashes = ChunkedBundleUploader.computeChunkHashes(f, 1024)
        assertTrue(hashes.isEmpty())
    }

    @Test fun `single-chunk file produces one hash matching SHA-1 of the whole file`() {
        val content = ByteArray(500) { (it and 0xff).toByte() }
        val f = tempFolder.newFile("small.bin")
        f.writeBytes(content)
        val hashes = ChunkedBundleUploader.computeChunkHashes(f, 1024)
        assertEquals(listOf(sha1Hex(content)), hashes)
    }

    @Test fun `file exactly one chunk size emits one hash over all bytes`() {
        val content = ByteArray(1024) { (it and 0xff).toByte() }
        val f = tempFolder.newFile("one.bin")
        f.writeBytes(content)
        val hashes = ChunkedBundleUploader.computeChunkHashes(f, 1024)
        assertEquals(listOf(sha1Hex(content)), hashes)
    }

    @Test fun `file greater than one chunk emits correct hash per chunk in order`() {
        val chunkSize = 1024
        val total = chunkSize * 3 + 500
        val content = ByteArray(total) { (it and 0xff).toByte() }
        val f = tempFolder.newFile("multi.bin")
        f.writeBytes(content)

        val expected = listOf(
            sha1Hex(content.copyOfRange(0, chunkSize)),
            sha1Hex(content.copyOfRange(chunkSize, chunkSize * 2)),
            sha1Hex(content.copyOfRange(chunkSize * 2, chunkSize * 3)),
            sha1Hex(content.copyOfRange(chunkSize * 3, total)),
        )
        val actual = ChunkedBundleUploader.computeChunkHashes(f, chunkSize)
        assertEquals(expected, actual)
    }

    @Test fun `hash is deterministic for identical files`() {
        val content = ByteArray(10_000) { (it and 0xff).toByte() }
        val a = tempFolder.newFile("a.bin").apply { writeBytes(content) }
        val b = tempFolder.newFile("b.bin").apply { writeBytes(content) }
        assertEquals(
            ChunkedBundleUploader.computeChunkHashes(a, 4096),
            ChunkedBundleUploader.computeChunkHashes(b, 4096),
        )
    }

    @Test fun `different content yields different chunk hashes`() {
        val chunkSize = 1024
        val a = tempFolder.newFile("diff-a.bin")
        val b = tempFolder.newFile("diff-b.bin")
        a.writeBytes(ByteArray(chunkSize * 2) { 0x41 })
        b.writeBytes(ByteArray(chunkSize * 2) { 0x42 })
        val ha = ChunkedBundleUploader.computeChunkHashes(a, chunkSize)
        val hb = ChunkedBundleUploader.computeChunkHashes(b, chunkSize)
        assertEquals(2, ha.size)
        assertEquals(2, hb.size)
        assertTrue(ha[0] != hb[0])
        assertTrue(ha[1] != hb[1])
    }

    @Test fun `hash length is always 40 hex chars`() {
        val f = tempFolder.newFile("len.bin")
        f.writeBytes(ByteArray(5000) { (it and 0xff).toByte() })
        val hashes = ChunkedBundleUploader.computeChunkHashes(f, 1024)
        for (h in hashes) {
            assertEquals(40, h.length)
            assertTrue(h.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }
}
