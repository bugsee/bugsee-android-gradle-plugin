package com.bugsee.android.gradle

/**
 * Interface for providing app tokens based on variant name.
 *
 * Implement this interface for strong-typed app token resolution in Kotlin DSL build scripts.
 */
interface AppTokenProvider {
    fun getAppToken(variantName: String): String?
}
