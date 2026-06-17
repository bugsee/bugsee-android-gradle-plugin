package com.bugsee.android.gradle.util

import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins zip-output REPRODUCIBILITY for [ZipUtils].
 *
 * The LAST-BETA bug: entries were created with no explicit time and (for
 * [ZipUtils.zipDirectory]) walked in unspecified `Files.walk` order, so two
 * zips of byte-identical input produced byte-DIFFERENT output. The native
 * debug-symbol upload keys its `SymbolHashCache` on the SHA-1 of the zip, so
 * a per-build-varying hash defeated the cache entirely (re-upload every
 * build) and undermined server-side dedup. The fix pins a constant entry
 * time and a stable sorted entry order.
 */
class ZipUtilsTest {

    private fun newTempDir(name: String): File =
        File.createTempFile(name, "").let {
            it.delete(); it.mkdirs(); it.deleteOnExit(); it
        }

    private fun bytesOf(f: File): ByteArray = f.readBytes()

    @Test
    fun `zipDirectory output is byte-identical across runs`() {
        val src = newTempDir("ziputils-src")
        // Materialize files in a deliberately non-alphabetical creation
        // order across nested dirs to exercise the sort.
        File(src, "zeta.txt").writeText("z")
        File(src, "alpha.txt").writeText("a")
        File(src, "sub").mkdirs()
        File(src, "sub/inner.txt").writeText("i")
        File(src, "sub/aaa.txt").writeText("x")

        val out1 = File.createTempFile("ziputils-out1", ".zip").apply { deleteOnExit() }
        val out2 = File.createTempFile("ziputils-out2", ".zip").apply { deleteOnExit() }

        ZipUtils.zipDirectory(src.absolutePath, out1.absolutePath)
        // A small wait so a current-time stamp (the bug) would differ.
        Thread.sleep(1100)
        ZipUtils.zipDirectory(src.absolutePath, out2.absolutePath)

        assertContentEquals(
            bytesOf(out1),
            bytesOf(out2),
            "two zips of identical content must be byte-identical (reproducible)",
        )
    }

    @Test
    fun `zipDirectory entries carry a constant timestamp across runs`() {
        val src = newTempDir("ziputils-ts-src")
        File(src, "a.txt").writeText("a")
        val out1 = File.createTempFile("ziputils-ts-out1", ".zip").apply { deleteOnExit() }
        val out2 = File.createTempFile("ziputils-ts-out2", ".zip").apply { deleteOnExit() }

        ZipUtils.zipDirectory(src.absolutePath, out1.absolutePath)
        Thread.sleep(1100)
        ZipUtils.zipDirectory(src.absolutePath, out2.absolutePath)

        // The load-bearing property: the entry time is CONSTANT (pinned),
        // never System.currentTimeMillis() — so the same entry has the same
        // stored time across two builds >1s apart. (Asserting a specific
        // value would couple to the ZIP DOS-time conversion, which clamps
        // the pre-1980 epoch; constancy is what reproducibility needs.)
        ZipFile(out1).use { z1 ->
            ZipFile(out2).use { z2 ->
                val e1 = z1.getEntry("a.txt")
                val e2 = z2.getEntry("a.txt")
                assertTrue(e1 != null && e2 != null, "a.txt must exist in both zips")
                assertEquals(
                    e1!!.time, e2!!.time,
                    "entry time must be constant across builds, not the wall clock",
                )
            }
        }
    }

    @Test
    fun `zipDirectory emits entries in stable sorted order`() {
        val src = newTempDir("ziputils-order-src")
        File(src, "zeta.txt").writeText("z")
        File(src, "alpha.txt").writeText("a")
        File(src, "mid.txt").writeText("m")
        val out = File.createTempFile("ziputils-order-out", ".zip").apply { deleteOnExit() }
        ZipUtils.zipDirectory(src.absolutePath, out.absolutePath)

        val names = mutableListOf<String>()
        ZipInputStream(out.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) names.add(e.name)
                e = zis.nextEntry
            }
        }
        assertEquals(
            names.sorted(),
            names,
            "file entries must be written in sorted order for deterministic bytes",
        )
    }

    @Test
    fun `createMappingZip output is byte-identical across runs`() {
        val mapping = File.createTempFile("mapping", ".txt").apply {
            writeText("a -> b\n"); deleteOnExit()
        }
        val zip1 = ZipUtils.createMappingZip(mapping, null, "uuid-fixed")
        Thread.sleep(1100)
        val zip2 = ZipUtils.createMappingZip(mapping, null, "uuid-fixed")
        zip1.deleteOnExit(); zip2.deleteOnExit()

        assertContentEquals(
            bytesOf(zip1),
            bytesOf(zip2),
            "two mapping zips of identical content must be byte-identical",
        )
    }

    @Test
    fun `createMappingZip entries carry a constant timestamp across runs`() {
        val mapping = File.createTempFile("mapping", ".txt").apply {
            writeText("a -> b\n"); deleteOnExit()
        }
        val zip1 = ZipUtils.createMappingZip(mapping, null, "uuid-ts").apply { deleteOnExit() }
        Thread.sleep(1100)
        val zip2 = ZipUtils.createMappingZip(mapping, null, "uuid-ts").apply { deleteOnExit() }
        ZipFile(zip1).use { z1 ->
            ZipFile(zip2).use { z2 ->
                val e1 = z1.getEntry("mapping.txt")
                val e2 = z2.getEntry("mapping.txt")
                assertTrue(e1 != null && e2 != null, "mapping.txt must exist in both zips")
                assertEquals(e1!!.time, e2!!.time, "mapping.txt time must be constant across builds")
            }
        }
    }
}
