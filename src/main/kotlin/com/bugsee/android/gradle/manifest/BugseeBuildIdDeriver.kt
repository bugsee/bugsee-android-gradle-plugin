package com.bugsee.android.gradle.manifest

import java.util.UUID

/**
 * Centralised BUILD_UUID derivation shared between the two writers that
 * embed it into a release artefact:
 *
 *   - [BugseeManifestTask] (T1, pre-R8) — stamps a *fallback* UUID into
 *     the merged AndroidManifest.xml. This is what older SDK builds
 *     read at runtime when the post-R8 asset channel isn't present, and
 *     what every build reads when R8 is off entirely.
 *   - `BugseeBuildIdResolveTask` (T2, post-R8) — picks between the
 *     R8-mapping-derived UUID (when `mapping.txt` exists) and this
 *     fallback UUID (when it doesn't). The resolved value lands in the
 *     `assets/bugsee_build_id.properties` channel the SDK reads first
 *     at runtime.
 *
 * Both writers must agree byte-for-byte on the fallback computation so
 * that a build with R8 off produces the SAME UUID in the manifest as
 * in the asset file — otherwise the SDK's "asset first, manifest
 * fallback" reader would observe a mismatch that looks like a
 * regression.
 *
 * The R8-mapping path hashes `mapping.txt` content directly: same
 * bytecode → same mapping bytes → same UUID, which is the property
 * that makes the SDK-to-mapping-file round-trip correct even when the
 * developer changes code without bumping `versionCode`.
 */
internal object BugseeBuildIdDeriver {

    /**
     * Derives the fallback BUILD_UUID from (merged manifest bytes,
     * variant name, plugin version). Used when no R8 mapping file is
     * available (debug builds, release with `isMinifyEnabled = false`,
     * library projects with no application module).
     *
     * The hash input concatenates the three sources separated by `|`
     * bytes — a separator any well-formed AGP variant or plugin
     * version value will not contain naturally. Sizing comes from the
     * **UTF-8 byte lengths** (not [String.length]'s char count); a
     * future non-ASCII flavor name would otherwise overflow the
     * arraycopy.
     *
     * `UUID.nameUUIDFromBytes` produces a v3 (MD5-based, name-derived)
     * UUID per RFC 4122. The version bits don't matter for our use —
     * what matters is that the result is UUID-shaped, stable across
     * runs of the same inputs, and distinct across distinct inputs.
     */
    fun deriveFromFallbackInputs(
        mergedManifestBytes: ByteArray,
        variantName: String,
        pluginVersion: String,
    ): UUID {
        val variantBytes = variantName.toByteArray(Charsets.UTF_8)
        val pluginVersionBytes = pluginVersion.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(
            mergedManifestBytes.size + variantBytes.size + pluginVersionBytes.size + 2
        )
        System.arraycopy(mergedManifestBytes, 0, buf, 0, mergedManifestBytes.size)
        var offset = mergedManifestBytes.size
        buf[offset++] = SEPARATOR
        System.arraycopy(variantBytes, 0, buf, offset, variantBytes.size)
        offset += variantBytes.size
        buf[offset++] = SEPARATOR
        System.arraycopy(pluginVersionBytes, 0, buf, offset, pluginVersionBytes.size)
        return UUID.nameUUIDFromBytes(buf)
    }

    /**
     * Derives the BUILD_UUID from R8's `mapping.txt` content. Hashing
     * the whole file (rather than parsing the `pg_map_id` header line)
     * gives a UUID that:
     *   - changes whenever R8's output changes (the property we want);
     *   - is independent of mapping-file header format drift across
     *     R8 versions (we don't have to keep up with R8's textual
     *     conventions);
     *   - matches the approach Sentry's android-gradle-plugin uses,
     *     so we don't pioneer a wire format that's never been
     *     stress-tested in production.
     *
     * Sentry's `SentryGenerateProguardUuidTask` uses the same input
     * shape; see plugin-build / src/main/kotlin/io/sentry/android/gradle/
     * tasks/SentryGenerateProguardUuidTask.kt.
     */
    fun deriveFromMappingFile(mappingFileBytes: ByteArray): UUID {
        return UUID.nameUUIDFromBytes(mappingFileBytes)
    }

    private const val SEPARATOR: Byte = '|'.code.toByte()
}
