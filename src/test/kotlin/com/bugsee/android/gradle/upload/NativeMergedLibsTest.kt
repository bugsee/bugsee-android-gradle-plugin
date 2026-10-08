package com.bugsee.android.gradle.upload

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeMergedLibsTest {

    private fun tempDir(): File = Files.createTempDirectory("merged_native_libs").toFile().apply { deleteOnExit() }

    @Test fun `null or missing directory yields no source`() {
        assertNull(findMergedNativeLibs(null))
        assertNull(findMergedNativeLibs(File(tempDir(), "nope")))
    }

    @Test fun `directory without libraries yields no source`() {
        val dir = tempDir()
        File(dir, "out/lib/arm64-v8a").mkdirs()
        File(dir, "out/lib/arm64-v8a/readme.txt").writeText("x")
        assertNull(findMergedNativeLibs(dir))
    }

    @Test fun `directory with a nested library is the source`() {
        val dir = tempDir()
        File(dir, "mergeReleaseNativeLibs/out/lib/arm64-v8a").mkdirs()
        File(dir, "mergeReleaseNativeLibs/out/lib/arm64-v8a/libfoo.so").writeText("x")
        assertEquals(dir, findMergedNativeLibs(dir))
    }
}
