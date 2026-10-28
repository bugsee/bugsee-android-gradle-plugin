package com.bugsee.android.gradle

import com.bugsee.android.gradle.instrumentation.BugseeSdkVersion

/**
 * First published release of each auto-added `com.bugsee:bugsee-android-*` extension artifact.
 *
 * Extensions are auto-added at the version of the core SDK (the declared pin, see `autoAddModule`),
 * but not every extension exists from the start of the 7.0.0 line: asking for `bugsee-android-ndk`
 * at `7.0.0-beta1` is a coordinate that does not exist, and the build fails at dependency
 * resolution. A declared version below the artifact's first release skips the auto-add instead.
 *
 * Values are the earliest version listed in each artifact's `maven-metadata.xml` on Maven Central
 * (checked against 7.4.2): ndk `7.0.0-beta6`, leak `7.0.0-beta13`, every other extension
 * `7.0.0-beta1`. Add a row here when a new extension artifact is added to `AutoAddedExtensions` (a test fails otherwise); an
 * artifact with no row is never skipped.
 */
internal object ExtensionFirstRelease {

    private val FIRST_RELEASE: Map<String, String> = mapOf(
        "bugsee-android-ndk" to "7.0.0-beta6",
        "bugsee-android-leak" to "7.0.0-beta13",
        "bugsee-android-feedback" to "7.0.0-beta1",
        "bugsee-android-compose" to "7.0.0-beta1",
        "bugsee-android-okhttp" to "7.0.0-beta1",
        "bugsee-android-ktor-2" to "7.0.0-beta1",
        "bugsee-android-ktor-3" to "7.0.0-beta1",
        "bugsee-android-cronet" to "7.0.0-beta1",
    )

    /** The artifact's first release, or null when [artifactName] is not in the table. */
    fun of(artifactName: String): String? = FIRST_RELEASE[artifactName]

    /** Every artifact with a row (used by the table/auto-add guard test). */
    fun artifacts(): Set<String> = FIRST_RELEASE.keys

    /**
     * The first release of [artifactName] when [declaredVersion] is a parsable version strictly
     * below it, else null. A range (`[7.3.0,)`), dynamic version or any unparsable string is left
     * to the existing behaviour (null), as is an artifact not in the table.
     */
    fun firstReleaseIfOlder(artifactName: String, declaredVersion: String): String? {
        val first = FIRST_RELEASE[artifactName] ?: return null
        val declared = BugseeSdkVersion.parse(declaredVersion) ?: return null
        val firstParsed = BugseeSdkVersion.parse(first) ?: return null
        return if (declared < firstParsed) first else null
    }

    fun skipWarning(artifactName: String, declaredVersion: String, firstRelease: String): String =
        "Bugsee: com.bugsee:$artifactName is not auto-added: the SDK version $declaredVersion is older than " +
            "the first release of that module ($firstRelease), so the coordinate would not resolve. " +
            "Upgrade the Bugsee SDK to use it, or turn off the feature that needs it " +
            "(ndk.enabled, leak.enabled, feedback, or the okhttp/compose/ktor/cronet instrumentation) to silence this."
}
