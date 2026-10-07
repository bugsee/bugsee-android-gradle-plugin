package com.bugsee.android.gradle.upload

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeForceDecisionTest {

    private fun zipOf(vararg names: String): File {
        val f = File.createTempFile("symbols", ".zip").apply { deleteOnExit() }
        ZipOutputStream(f.outputStream()).use { z ->
            names.forEach { z.putNextEntry(ZipEntry(it)); z.write(1); z.closeEntry() }
        }
        return f
    }

    @Test fun `FULL zip with so_dbg entries is forced`() {
        assertTrue(containsFullDebugSymbols(zipOf("arm64-v8a/libfoo.so.dbg", "arm64-v8a/libbar.so.sym")))
    }

    @Test fun `SYMBOL_TABLE and plain so zips are not forced`() {
        assertFalse(containsFullDebugSymbols(zipOf("arm64-v8a/libfoo.so.sym")))
        assertFalse(containsFullDebugSymbols(zipOf("arm64-v8a/libfoo.so")))
    }

    @Test fun `unreadable zip is not forced`() {
        val bad = File.createTempFile("bad", ".zip").apply { writeText("nope"); deleteOnExit() }
        assertFalse(containsFullDebugSymbols(bad))
    }
}
