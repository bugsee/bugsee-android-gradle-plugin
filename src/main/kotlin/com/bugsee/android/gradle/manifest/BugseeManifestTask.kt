package com.bugsee.android.gradle.manifest

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Task that injects a unique BUILD_UUID into the merged AndroidManifest.xml
 * and (when [optimizeExtensionsLoading] is `true`) strips every Bugsee
 * extension `<provider>` from the merged manifest.
 *
 * This task operates on the merged manifest via AGP's artifact transformation API,
 * meaning it receives the manifest as input, modifies it in place, and outputs the same file.
 *
 * The list of stripped extension init-provider FQNs is written to
 * [detectedExtensions] so the downstream bytecode visitor knows which
 * `register<Name>Extension()` calls to inline into the SDK's
 * `BugseeInitProvider.initializeExtensions()`.
 */
abstract class BugseeManifestTask : DefaultTask() {

    @get:Input
    abstract val debug: Property<Boolean>

    /**
     * When `true` (default), every `<provider>` matching
     * `*.Bugsee<Name>InitProvider` is stripped from the merged manifest
     * and its FQN recorded in [detectedExtensions]. When `false`, no
     * stripping occurs and the detection file is written empty.
     */
    @get:Input
    abstract val optimizeExtensionsLoading: Property<Boolean>

    /**
     * AGP variant name (`debug`, `freeRelease`, etc.) for this task's
     * variant. Mixed into the deterministic BUILD_UUID derivation so
     * `assembleDebug` and `assembleRelease` of the same workspace
     * produce DIFFERENT UUIDs — they're different artefacts and must
     * round-trip to different crash/mapping uploads.
     */
    @get:Input
    abstract val variantName: Property<String>

    /**
     * Plugin version (`PLUGIN_VERSION` constant). Mixed into the UUID
     * derivation so a plugin upgrade applied to the same workspace
     * produces a fresh UUID — the upload pipeline's wire shape and
     * symbol-extraction rules can change across plugin releases, and
     * the safest contract is "new plugin = new build identity."
     */
    @get:Input
    abstract val pluginVersion: Property<String>

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val updatedManifest: RegularFileProperty

    /**
     * Output file listing one extension init-provider FQN per line, in
     * document order, for every provider stripped from the merged
     * manifest. Empty when [optimizeExtensionsLoading] is `false` or no
     * extension providers were merged in.
     */
    @get:OutputFile
    abstract val detectedExtensions: RegularFileProperty

    /**
     * Output file containing the deterministic fallback BUILD_UUID
     * this task derived from (mergedManifestBytes + variant + plugin).
     *
     * Consumed by [BugseeBuildIdResolveTask]: when R8 is off (no
     * mapping file available), that task copies this value verbatim
     * into the `assets/bugsee_build_id.properties` channel. The
     * side-file plumbing (rather than re-deriving the hash at T2 from
     * the merged manifest input) is necessary because AGP's artifact
     * transform replaces the `MERGED_MANIFEST` reference after T1
     * runs — the bytes T2 would see are the post-mutation manifest
     * (carrying T1's BUILD_UUID meta-data tag), NOT the pre-mutation
     * input T1 actually hashed. Without this side file the asset and
     * manifest channels would carry DIFFERENT UUIDs for the same
     * non-R8 build, which the SDK's asset-first / manifest-fallback
     * reader would observe as a regression.
     */
    @get:OutputFile
    abstract val fallbackBuildId: RegularFileProperty

    @TaskAction
    fun execute() {
        val isDebug = debug.get()
        val manifestFile = mergedManifest.get().asFile
        val outputFile = updatedManifest.get().asFile
        val detectedFile = detectedExtensions.get().asFile

        if (!manifestFile.isFile) {
            logger.warn("Bugsee: Manifest file not found: ${manifestFile.absolutePath}")
            writeDetectedExtensions(detectedFile, emptyList())
            return
        }

        // This is the *fallback* BUILD_UUID — derived deterministically
        // from (merged manifest bytes + variant + plugin version).
        // The post-R8 `BugseeBuildIdResolveTask` may later compute a
        // BETTER UUID (hash of `mapping.txt` content, which reflects
        // actual bytecode identity) and place it in the
        // `assets/bugsee_build_id.properties` channel the SDK reads
        // first. This fallback UUID lands in the manifest meta-data
        // channel and serves two roles:
        //   1. Non-R8 builds (debug, or release with
        //      isMinifyEnabled=false). There's no mapping file to
        //      hash, so the manifest-derived UUID is the only signal —
        //      and that's fine because there's also no mapping to
        //      mis-match against.
        //   2. Backward compatibility. SDK versions predating the
        //      asset channel still read the manifest meta-data and
        //      use this value verbatim.
        //
        // The exact derivation is centralised in
        // [BugseeBuildIdDeriver] so the post-R8 task computes the
        // same fallback byte-for-byte when it needs to.
        val manifestBytes = manifestFile.readBytes()
        val buildUUID = BugseeBuildIdDeriver.deriveFromFallbackInputs(
            mergedManifestBytes = manifestBytes,
            variantName = variantName.get(),
            pluginVersion = pluginVersion.get(),
        ).toString()

        // Stash the fallback UUID in a side file so the post-R8 resolve
        // task can carry it forward verbatim when no mapping file
        // exists (R8-off path). See [fallbackBuildId]'s KDoc for why
        // T2 cannot recompute the same value from its inputs.
        val fallbackFile = fallbackBuildId.get().asFile
        fallbackFile.parentFile?.mkdirs()
        fallbackFile.writeText(buildUUID)

        // Copy input to output location if different
        if (manifestFile.absolutePath != outputFile.absolutePath) {
            manifestFile.copyTo(outputFile, overwrite = true)
        }

        if (isDebug) logger.warn("Bugsee: Adding buildUUID $buildUUID to ${outputFile.path}")

        if (!ManifestModifier.addBuildUuidToManifest(outputFile, buildUUID)) {
            logger.warn("Bugsee: <application> section not found in manifest: ${outputFile.path}")
            writeDetectedExtensions(detectedFile, emptyList())
            return
        }

        val allDetected = mutableListOf<String>()
        val optimize = optimizeExtensionsLoading.getOrElse(true)
        if (optimize) {
            val detected = ManifestModifier.removeExtensionInitProviders(outputFile)
            if (isDebug && detected.isNotEmpty()) {
                logger.warn(
                    "Bugsee: Stripped ${detected.size} extension provider(s) from " +
                        "${outputFile.path}: ${detected.joinToString(", ")}"
                )
            }
            allDetected.addAll(detected)
        }

        // Handle split APK scenarios: check for nested directories
        // with AndroidManifest.xml.
        //
        // **CC-correctness posture.** These nested manifests are AGP-owned
        // intermediates produced by `processDebugMainManifestForBundle` /
        // similar AGP manifest tasks. We mutate them in-place but do
        // NOT declare them as `@OutputFile` of this task — by Gradle's
        // strict-mode rules that's a CC-correctness gray area. We
        // accept it intentionally:
        //
        //  1. **Declaring static outputs is impossible.** The exact set
        //     of nested manifest files only exists once AGP has run
        //     the manifest-merge phase for splits; it's variant +
        //     splits-DSL dependent and cannot be enumerated at
        //     registration time.
        //  2. **AGP tracks them via its own dependency graph.**
        //     Downstream packaging tasks (`packageDebug*`, `bundleDebug`)
        //     declare these files as their own inputs. Our mutation
        //     therefore propagates correctly: AGP's UP-TO-DATE check
        //     sees the modified bytes and invalidates the packaging
        //     step accordingly.
        //  3. **The Bugsee task itself remains correctly UP-TO-DATE-
        //     gated** on the merged-manifest INPUT (`mergedManifest`)
        //     and the primary OUTPUT (`updatedManifest`). The merged
        //     manifest is a stable artifact-transform input, so any
        //     change to it re-runs this task — which then re-applies
        //     the nested-manifest mutation. No risk of stale
        //     side-effect output.
        //  4. **Coverage:** `BugseeManifestTaskSplitApkTest` (unit-level)
        //     exercises every code path in this loop with a fixture
        //     that materialises sibling directories matching AGP's
        //     real-world split-APK layout.
        //
        // If Gradle ever tightens CC checks to forbid undeclared-
        // file mutation (currently it doesn't catch this pattern),
        // the right replacement is to declare the parent directory
        // as an `@OutputDirectory` — that gives Gradle ownership of
        // the entire intermediate folder, which we'd need to
        // negotiate with AGP. Until then, the explicit comment above
        // is the load-bearing piece of documentation for the
        // intentional posture.
        val parentDir = outputFile.parentFile
        if (parentDir != null && parentDir.isDirectory) {
            parentDir.listFiles()?.filter { dir ->
                dir.isDirectory && File(dir, "AndroidManifest.xml").isFile
            }?.forEach { dir ->
                val nestedManifest = File(dir, "AndroidManifest.xml")
                if (!ManifestModifier.addBuildUuidToManifest(nestedManifest, buildUUID)) {
                    logger.warn("Bugsee: <application> section not found in nested manifest: ${nestedManifest.path}")
                }
                if (optimize) {
                    val nestedDetected = ManifestModifier.removeExtensionInitProviders(nestedManifest)
                    // De-duplicate across split copies: each split typically
                    // mirrors the same provider set, so we want the union of
                    // FQNs (not a list with N copies of each name).
                    for (fqn in nestedDetected) {
                        if (fqn !in allDetected) allDetected.add(fqn)
                    }
                }
            }
        }

        writeDetectedExtensions(detectedFile, allDetected)

        if (isDebug) logger.warn("Bugsee: Manifest task complete")
    }

    /**
     * Writes [fqns] one per line into [target], creating parent directories
     * as needed. Always overwrites — Gradle's `@OutputFile` semantics
     * require a deterministic output on every task run.
     */
    private fun writeDetectedExtensions(target: File, fqns: List<String>) {
        target.parentFile?.mkdirs()
        target.writeText(fqns.joinToString(separator = "\n", postfix = if (fqns.isEmpty()) "" else "\n"))
    }
}
