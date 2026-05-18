package com.bugsee.android.gradle.instrumentation

import com.bugsee.android.gradle.BugseeInstrumentationExtension
import com.bugsee.android.gradle.instrumentation.app_startup_tracing.StartupTier
import com.bugsee.android.gradle.manifest.ManifestModifier
import org.gradle.api.Project
import java.io.File

/**
 * Resolves whether a given instrumentation feature is enabled by checking
 * three sources in priority order:
 *
 * 1. **DSL extension** property (e.g. `bugsee { instrumentation { okhttp.set(false) } }`)
 * 2. **Gradle project property** (e.g. `bugsee.instrumentation.okhttp=false`)
 * 3. **Manifest meta-data** (e.g. `<meta-data android:name="com.bugsee.android.instrumentation.okhttp" android:value="false"/>`)
 *    Note: only the main source manifest (`src/main/AndroidManifest.xml`) is consulted,
 *    not build-type overlays or library manifests.
 * 4. **Default** — `true`
 */
internal class InstrumentationConfigResolver(
    private val extension: BugseeInstrumentationExtension,
    private val project: Project,
    private val sourceManifest: File?
) {

    companion object {
        private const val GRADLE_PROP_PREFIX = "bugsee.instrumentation"
        private const val MANIFEST_META_PREFIX = "com.bugsee.android.instrumentation"
    }

    /** Returns true if instrumentation is globally enabled. */
    fun isGloballyEnabled(): Boolean {
        return resolve(extension.enabled, "$GRADLE_PROP_PREFIX.enabled", "$MANIFEST_META_PREFIX.enabled")
    }

    /** Returns true if the instrumentation with the given [key] is enabled. */
    fun isFeatureEnabled(key: String): Boolean {
        val dslProp = extension.propertyForKey(key)
        return resolve(dslProp, "$GRADLE_PROP_PREFIX.$key", "$MANIFEST_META_PREFIX.$key")
    }

    /**
     * Resolves the app-startup tracing tier through the same priority chain
     * as [isFeatureEnabled]:
     *
     * 1. DSL `bugsee { instrumentation { startupTier = "DETAILED" } }`
     * 2. Gradle property `bugsee.instrumentation.startupTier=DETAILED`
     * 3. Manifest meta-data `com.bugsee.android.instrumentation.startupTier`
     * 4. Default — [StartupTier.DEFAULT] (STANDARD).
     *
     * An invalid value at any source emits a warning and falls through to
     * the next source — never the default directly — so a typo in the DSL
     * doesn't silently mask a valid Gradle property.
     */
    fun resolveStartupTier(): StartupTier {
        val key = "startupTier"
        val gradlePropName = "$GRADLE_PROP_PREFIX.$key"
        val manifestMetaName = "$MANIFEST_META_PREFIX.$key"

        // 1. DSL
        if (extension.startupTier.isPresent) {
            val raw = extension.startupTier.get()
            val parsed = StartupTier.parse(raw)
            if (parsed != null) {
                return parsed
            }
            project.logger.warn(
                "Bugsee: Invalid startupTier '$raw' in DSL; expected one of " +
                        "${StartupTier.entries.joinToString { it.name }}. Falling through to next source."
            )
        }

        // 2. Gradle property
        val gradleValue = project.findProperty(gradlePropName)?.toString()
        if (gradleValue != null) {
            val parsed = StartupTier.parse(gradleValue)
            if (parsed != null) {
                return parsed
            }
            project.logger.warn(
                "Bugsee: Invalid startupTier '$gradleValue' for Gradle property '$gradlePropName'; " +
                        "expected one of ${StartupTier.entries.joinToString { it.name }}. " +
                        "Falling through to next source."
            )
        }

        // 3. Manifest meta-data
        if (sourceManifest != null && sourceManifest.exists()) {
            val manifestValue = ManifestModifier.getMetaDataValue(sourceManifest, manifestMetaName)
            if (manifestValue != null) {
                val parsed = StartupTier.parse(manifestValue)
                if (parsed != null) {
                    return parsed
                }
                project.logger.warn(
                    "Bugsee: Invalid startupTier '$manifestValue' in manifest meta-data " +
                            "'$manifestMetaName'; expected one of " +
                            "${StartupTier.entries.joinToString { it.name }}. " +
                            "Falling through to next source (manifest is the last source — " +
                            "this means falling back to default ${StartupTier.DEFAULT})."
                )
            }
        }

        // 4. Default
        return StartupTier.DEFAULT
    }

    /**
     * Resolves a boolean value through the priority chain:
     * DSL property → Gradle property → manifest meta-data → default (true).
     */
    private fun resolve(
        dslProperty: org.gradle.api.provider.Property<Boolean>?,
        gradlePropName: String,
        manifestMetaName: String
    ): Boolean {
        // 1. DSL extension property
        if (dslProperty != null && dslProperty.isPresent) {
            return dslProperty.get()
        }

        // 2. Gradle project property
        val gradleValue = project.findProperty(gradlePropName)?.toString()
        if (gradleValue != null) {
            val parsed = gradleValue.toBooleanStrictOrNull()
            if (parsed == null) {
                project.logger.warn("Bugsee: Invalid boolean value '$gradleValue' for property '$gradlePropName', defaulting to true")
            }
            return parsed ?: true
        }

        // 3. Manifest meta-data (main source manifest only)
        if (sourceManifest != null && sourceManifest.exists()) {
            val manifestValue = ManifestModifier.getMetaDataValue(sourceManifest, manifestMetaName)
            if (manifestValue != null) {
                val parsed = manifestValue.toBooleanStrictOrNull()
                if (parsed == null) {
                    project.logger.warn("Bugsee: Invalid boolean value '$manifestValue' for manifest meta-data '$manifestMetaName', defaulting to true")
                }
                return parsed ?: true
            }
        }

        // 4. Default
        return true
    }
}
