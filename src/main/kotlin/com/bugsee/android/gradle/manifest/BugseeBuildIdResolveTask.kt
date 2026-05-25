package com.bugsee.android.gradle.manifest

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Resolves the final BUILD_UUID for a variant after R8 has had a
 * chance to produce its mapping file.
 *
 * Wired into AGP via:
 *
 * ```kotlin
 * variant.artifacts.use(taskProvider)
 *     .wiredWith(BugseeBuildIdResolveTask::mappingFile)
 *     .toListenTo(SingleArtifact.OBFUSCATION_MAPPING_FILE)
 * ```
 *
 * `toListenTo` (AGP 8.3+) places this task downstream of R8 *if R8 is
 * running for the variant*, without forcing R8 to run. When R8 is off
 * (`isMinifyEnabled = false`, debug variants by default), the
 * `mappingFile` property is absent and we fall back to the same
 * deterministic-from-manifest derivation that [BugseeManifestTask]
 * already wrote into the manifest meta-data.
 *
 * The resolved UUID is written one-line into [resolvedBuildIdFile]
 * and consumed downstream by [BugseeAssetInjectionTask], which copies
 * it into `assets/bugsee_build_id.properties` for the SDK to read at
 * runtime.
 *
 * The two-source design exists because AGP has no public post-R8
 * manifest hook: we cannot rewrite the manifest meta-data once R8 has
 * declared the mapping. Sentry's android-gradle-plugin uses the
 * same architecture for the same reason — see their
 * `SentryGenerateProguardUuidTask` plus
 * `InjectSentryMetaPropertiesIntoAssetsTask`.
 */
abstract class BugseeBuildIdResolveTask : DefaultTask() {

    /**
     * R8 mapping file, when minification produced one. Marked optional
     * (`@get:Optional`) so this task runs cleanly for non-R8 variants
     * — AGP supplies an absent value rather than failing.
     */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty

    /**
     * Side-file produced by [BugseeManifestTask] containing the
     * deterministic fallback UUID it derived from (manifest + variant
     * + plugin). Used verbatim when no R8 mapping is available.
     *
     * Reading T1's side file (rather than re-deriving the hash here
     * from the merged manifest) is load-bearing: AGP's artifact
     * transform replaces the `MERGED_MANIFEST` reference after T1
     * runs, so any `mergedManifest` input we'd wire to AGP would be
     * T1's POST-mutation output (carrying T1's BUILD_UUID meta-data
     * tag), not the pre-mutation bytes T1 actually hashed.
     * Recomputing the same UUID is therefore impossible without a
     * side-channel that carries the original input bytes — and once
     * you have such a side-channel, carrying the result UUID directly
     * is strictly simpler.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fallbackBuildId: RegularFileProperty

    /**
     * Output: a single-line text file containing the resolved UUID
     * (36-character canonical form, no trailing newline). Consumed by
     * [BugseeAssetInjectionTask].
     */
    @get:OutputFile
    abstract val resolvedBuildIdFile: RegularFileProperty

    @TaskAction
    fun execute() {
        val resolved = resolve()
        val outFile = resolvedBuildIdFile.get().asFile
        outFile.parentFile?.mkdirs()
        outFile.writeText(resolved)
    }

    private fun resolve(): String {
        // Primary path: R8 ran and produced a mapping. Hash its bytes
        // so the UUID reflects bytecode identity — same source ->
        // same UUID, different source -> different UUID, even when
        // the manifest is byte-identical (the case the fallback-only
        // derivation cannot distinguish).
        val mapping = mappingFile.orNull?.asFile
        if (mapping != null && mapping.isFile && mapping.length() > 0L) {
            return BugseeBuildIdDeriver.deriveFromMappingFile(mapping.readBytes()).toString()
        }

        // Fallback path: no mapping → no symbolication round-trip risk
        // for code-only changes. Carry forward verbatim whatever T1
        // already wrote into manifest meta-data so the asset and the
        // manifest agree. T1's UUID arrives via the side file rather
        // than being recomputed locally — see [fallbackBuildId] KDoc.
        return fallbackBuildId.get().asFile.readText().trim()
    }
}
