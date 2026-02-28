package com.bugsee.android.gradle.upload

import com.bugsee.android.gradle.BugseePluginExtension
import com.bugsee.android.gradle.util.StringResourceResolver
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import java.io.File

internal object AppTokenResolver {

    private const val APP_TOKEN_TAG = "com.bugsee.android.APP_TOKEN"

    private fun maskToken(token: String): String {
        if (token.length <= 8) return "****"
        return "${token.take(4)}...${token.takeLast(4)}"
    }

    /**
     * Resolves the app token with the following priority:
     * 1. appTokenByVariant closure
     * 2. AppTokenProvider interface
     * 3. defaultAppToken property
     * 4. APP_TOKEN meta-data tag in the merged manifest
     */
    fun resolve(
        project: Project,
        extension: BugseePluginExtension,
        variantName: String,
        manifestFile: File?,
        logger: Logger,
        debug: Boolean
    ): String? {
        // 1. Closure-based variant token
        extension.appTokenByVariant?.let { resolver ->
            val token = resolver(variantName)
            if (!token.isNullOrEmpty()) {
                if (debug) logger.warn("Bugsee: Using appTokenByVariant: ${maskToken(token)}")
                return token
            }
        }

        // 2. AppTokenProvider interface
        extension.appTokenProvider?.let { provider ->
            val token = provider.getAppToken(variantName)
            if (!token.isNullOrEmpty()) {
                if (debug) logger.warn("Bugsee: Using AppTokenProvider: ${maskToken(token)}")
                return token
            }
        }

        // 3. Default app token
        extension.defaultAppToken?.let { token ->
            if (token.isNotEmpty()) {
                if (debug) logger.warn("Bugsee: Using defaultAppToken: ${maskToken(token)}")
                return token
            }
        }

        // 4. Manifest meta-data
        if (manifestFile != null && manifestFile.isFile) {
            val appToken = com.bugsee.android.gradle.manifest.ManifestModifier
                .getMetaDataValue(manifestFile, APP_TOKEN_TAG)

            if (appToken.isNullOrEmpty()) {
                logger.warn("Could not find '$APP_TOKEN_TAG' <meta-data> tag in your AndroidManifest.xml")
                return null
            }

            // Resolve @string/ references
            if (StringResourceResolver.isStringResource(appToken)) {
                return StringResourceResolver.resolve(project, appToken, logger, debug)
            }

            return appToken
        }

        logger.warn("Could not resolve app token from any source.")
        return null
    }
}
