package com.bugsee.android.gradle.instrumentation

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The probe that decides whether a plugin-injected call may be emitted.
 *
 * It replaces a version-number comparison, which could not answer the question for ranges,
 * platform-managed versions or project dependencies — and could not detect a class that was
 * present in source but stripped from the published artifact, which is precisely how
 * BugseeOkHttpWebSockets was missing from every release before 7.1.0.
 */
class SdkClassProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val target = "com/bugsee/library/okhttp/BugseeOkHttpWebSockets"

    private fun jarBytes(vararg entries: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (e in entries) {
                zip.putNextEntry(ZipEntry(e))
                zip.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun jar(name: String, vararg entries: String): File =
        temp.newFile(name).apply { writeBytes(jarBytes(*entries)) }

    /** An AAR keeps bytecode in a nested classes.jar, so the entry is not directly visible. */
    private fun aar(name: String, vararg entries: String): File {
        val file = temp.newFile(name)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("<manifest/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.jar"))
            zip.write(jarBytes(*entries))
            zip.closeEntry()
        }
        file.writeBytes(out.toByteArray())
        return file
    }

    @Test
    fun `finds a class inside a plain jar`() {
        assertTrue(SdkClassProbe.containsClass(listOf(jar("a.jar", "$target.class")), target))
    }

    /** The shape that actually matters: the SDK ships as an AAR. */
    @Test
    fun `finds a class nested inside an aar's classes jar`() {
        assertTrue(SdkClassProbe.containsClass(listOf(aar("b.aar", "$target.class")), target))
    }

    @Test
    fun `reports absent when the aar does not carry the class`() {
        val other = aar("c.aar", "com/bugsee/library/okhttp/BugseeOkHttpInterceptor.class")
        assertFalse(
            SdkClassProbe.containsClass(listOf(other), target),
            "an SDK that ships the interceptor but not the WebSocket producer must read as absent",
        )
    }

    @Test
    fun `scans past non-matching artifacts to find a later one`() {
        val artifacts = listOf(
            jar("unrelated.jar", "okhttp3/OkHttpClient.class"),
            aar("bugsee.aar", "$target.class"),
        )
        assertTrue(SdkClassProbe.containsClass(artifacts, target))
    }

    /**
     * A prefix collision must not count: `…WebSocketsExtra` is a different class, and a naive
     * `contains` check on entry names would accept it.
     */
    @Test
    fun `does not match a class whose name merely starts with the target`() {
        val decoy = aar("decoy.aar", "${target}Extra.class")
        assertFalse(SdkClassProbe.containsClass(listOf(decoy), target))
    }

    /**
     * Robustness over strictness: an unreadable artifact must not fail the consumer's build over
     * an optional capability — it simply does not contribute a match.
     */
    @Test
    fun `a corrupt archive is skipped rather than thrown`() {
        val corrupt = temp.newFile("broken.jar").apply { writeText("not a zip") }
        assertFalse(SdkClassProbe.containsClass(listOf(corrupt), target))
        assertTrue(SdkClassProbe.containsClass(listOf(corrupt, aar("ok.aar", "$target.class")), target))
    }

    @Test
    fun `non-archive files and empty input are handled`() {
        assertFalse(SdkClassProbe.containsClass(emptyList(), target))
        assertFalse(SdkClassProbe.containsClass(listOf(temp.newFile("notes.txt")), target))
    }
}
