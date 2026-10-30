package com.bugsee.android.gradle

/**
 * Artifact names of every extension module `BugseePluginImpl.computeAutoAdds` can auto-add. The single
 * source for those call sites and for the tests that guard [ExtensionFirstRelease] coverage: add a
 * new auto-added extension here (and a row in [ExtensionFirstRelease]).
 */
internal object AutoAddedExtensions {
    const val NDK = "bugsee-android-ndk"
    const val LEAK = "bugsee-android-leak"
    const val FEEDBACK = "bugsee-android-feedback"
    const val COMPOSE = "bugsee-android-compose"
    const val OKHTTP = "bugsee-android-okhttp"
    const val KTOR_2 = "bugsee-android-ktor-2"
    const val KTOR_3 = "bugsee-android-ktor-3"
    const val CRONET = "bugsee-android-cronet"

    val ALL: Set<String> = setOf(NDK, LEAK, FEEDBACK, COMPOSE, OKHTTP, KTOR_2, KTOR_3, CRONET)
}
