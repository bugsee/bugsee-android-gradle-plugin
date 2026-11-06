package com.bugsee.android.gradle.upload

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #53: the "build id is not mapping-derived" safeguard of [MappingUploadTask].
 *
 * Gradle's task logger cannot be captured under ProjectBuilder, so the message decision is
 * a pure function; the ordering (silent for variants with nothing to upload, warned only
 * once an upload will happen) is covered end to end by `NonMinifiedVariantWarningsTest`.
 */
class MappingUploadTaskWarningsTest {

    @Test
    fun listenerRegistered_noWarning() {
        assertNull(MappingUploadTask.buildIdNotMappingDerivedWarning("release", true))
    }

    @Test
    fun listenerMissing_warnsWithVariantName() {
        val msg = MappingUploadTask.buildIdNotMappingDerivedWarning("fooRelease", false)
        assertTrue(msg != null && "variant fooRelease" in msg && "not derived from the mapping" in msg, msg)
    }

    @Test
    fun messageIsStable() {
        assertEquals(
            "Bugsee: a mapping file exists for variant v, but minification is not enabled for it, " +
                "so its build id is not derived from the mapping. " +
                "Crashes may not deobfuscate after code-only changes.",
            MappingUploadTask.buildIdNotMappingDerivedWarning("v", false),
        )
    }
}
