package com.bugsee.android.gradle.upload

import com.bugsee.android.gradle.BugseePluginExtension
import com.bugsee.android.gradle.config.BUGSEE_PROPERTIES_FILENAME
import com.bugsee.android.gradle.util.StringResourceResolver
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import java.io.File

internal object AppTokenResolver {

    private const val APP_TOKEN_TAG = "com.bugsee.android.APP_TOKEN"
    private const val PROPERTIES_TOKEN_KEY = "app_token"

    /**
     * The all-zero token that templates and CI projects ship instead of a real one. It identifies
     * no application, so every source treats it as absent: the chain falls through, and with no
     * real token left the upload tasks skip.
     */
    internal const val PLACEHOLDER_TOKEN = "00000000-0000-4000-8000-000000000000"

    /** True for [PLACEHOLDER_TOKEN], ignoring case and surrounding whitespace. */
    internal fun isPlaceholder(token: String?): Boolean =
        token != null && token.trim().equals(PLACEHOLDER_TOKEN, ignoreCase = true)

    /**
     * True when [token] is a usable app token: non-empty and not [PLACEHOLDER_TOKEN]. A placeholder
     * is reported at info level ([source] names where it came from; once per source when [reported]
     * is given) and rejected, so the caller moves on to the next source.
     */
    internal fun isUsable(
        token: String?,
        source: String,
        logger: Logger,
        reported: MutableSet<String>? = null,
    ): Boolean {
        if (token.isNullOrEmpty()) return false
        if (!isPlaceholder(token)) return true
        if (reported == null || reported.add(source)) {
            logger.info("Bugsee: Ignoring placeholder app token from $source; treating it as not configured, trying the next source.")
        }
        return false
    }

    /** The info line an upload task logs, instead of the missing-token warning, when a placeholder was ignored. */
    internal fun logPlaceholderSkip(logger: Logger, what: String) {
        logger.info("Bugsee: Placeholder app token configured; skipping $what upload.")
    }

    internal fun maskToken(token: String): String {
        if (token.length <= 8) return "****"
        return "${token.take(4)}...${token.takeLast(4)}"
    }

    /**
     * Eager "plugin side" resolution — runs at task-registration time
     * inside `onVariants { }` and returns the first non-empty token
     * found across the extension's three configuration-time sources.
     * The task receives whatever this produced as a plain string input,
     * so the `@TaskAction` never has to touch `project.extensions`
     * itself (which would be a configuration-cache violation).
     *
     * Priority, identical to the previous execution-time flow:
     *   1. `appTokenByVariant` closure (invoked here, not deferred).
     *   2. `AppTokenProvider` interface.
     *   3. `defaultAppToken` property.
     *
     * The manifest `<meta-data>` fallback (priority 4 of the original
     * resolver) is NOT handled here — the merged manifest only exists
     * at execution time, so that branch lives in `resolveFromManifest`.
     */
    fun resolveFromExtension(
        extension: BugseePluginExtension,
        variantName: String,
        logger: Logger,
        debug: Boolean,
    ): String? =
        resolveFromClosureOrProvider(extension, variantName, logger, debug)
            ?: resolveFromDefaultToken(extension, logger, debug)

    /** Steps 1-2 of [resolveFromExtension]: the `appTokenByVariant` closure, then the `AppTokenProvider`. */
    internal fun resolveFromClosureOrProvider(
        extension: BugseePluginExtension,
        variantName: String,
        logger: Logger,
        debug: Boolean,
        reported: MutableSet<String>? = null,
    ): String? {
        extension.appTokenByVariant?.let { resolver ->
            val token = resolver(variantName)
            if (token != null && isUsable(token, "appTokenByVariant", logger, reported)) {
                if (debug) logger.warn("Bugsee: Using appTokenByVariant: ${maskToken(token)}")
                return token
            }
        }
        extension.appTokenProvider?.let { provider ->
            val token = provider.getAppToken(variantName)
            if (token != null && isUsable(token, "AppTokenProvider", logger, reported)) {
                if (debug) logger.warn("Bugsee: Using AppTokenProvider: ${maskToken(token)}")
                return token
            }
        }
        return null
    }

    /** Step 3 of [resolveFromExtension]: the non-empty `defaultAppToken`. */
    internal fun resolveFromDefaultToken(
        extension: BugseePluginExtension,
        logger: Logger,
        debug: Boolean,
        reported: MutableSet<String>? = null,
    ): String? {
        extension.defaultAppToken?.let { token ->
            if (isUsable(token, "defaultAppToken", logger, reported)) {
                if (debug) logger.warn("Bugsee: Using defaultAppToken: ${maskToken(token)}")
                return token
            }
        }
        return null
    }

    /**
     * Read the app token from `<rootProject>/bugsee.properties`. The
     * dedicated properties file is the wizard's storage of choice — kept
     * out of source control via `.gitignore` so secrets don't get
     * committed alongside build configuration.
     *
     * Slots into the resolver chain *between* the DSL forms (closure /
     * provider / `defaultAppToken`) and the manifest fallback. DSL beats
     * properties because it's prominent and explicit; properties beats
     * manifest because the manifest is a runtime-config surface, not a
     * build-time secrets source.
     *
     * Takes a plain `File` rather than `Project` so callers can wire the
     * `rootProject.projectDir` into a configuration-cache-friendly task
     * input at registration time.
     */
    fun resolveFromPropertiesFile(
        rootProjectDir: File,
        logger: Logger,
        debug: Boolean,
        onPlaceholder: () -> Unit = {},
    ): String? {
        val file = File(rootProjectDir, BUGSEE_PROPERTIES_FILENAME)
        if (!file.isFile) return null
        val props = java.util.Properties()
        try {
            file.inputStream().use { props.load(it) }
        } catch (e: Exception) {
            logger.warn("Bugsee: Failed to read ${file.path}: ${e.message}")
            return null
        }
        val rawValue = props.getProperty(PROPERTIES_TOKEN_KEY)
        val token = rawValue?.trim().orEmpty()
        if (token.isEmpty()) {
            // Differentiate "key absent" from "key present but empty" so
            // a user with `app_token=` (cleared during editing) gets a
            // visible diagnostic instead of silent fallback to manifest.
            if (rawValue != null) {
                logger.warn(
                    "Bugsee: ${file.name} declares ${PROPERTIES_TOKEN_KEY} with an empty value — falling through to next resolver source.",
                )
            }
            return null
        }
        if (isPlaceholder(token)) onPlaceholder()
        if (!isUsable(token, "${file.name}:$PROPERTIES_TOKEN_KEY", logger)) return null
        if (debug) {
            logger.warn(
                "Bugsee: Using ${file.name}:${PROPERTIES_TOKEN_KEY}: ${maskToken(token)}",
            )
        }
        return token
    }

    /**
     * Execution-time fallback — reads the merged manifest for the
     * `com.bugsee.android.APP_TOKEN` meta-data tag. Called by the
     * task only when the plugin-side resolution in
     * [resolveFromExtension] produced nothing.
     *
     * `stringResourceFiles` is passed in pre-resolved because the
     * `@string/foo` lookup path would otherwise need to reach into
     * the Android extension at execution time (a CC leak). The
     * plugin wires `android.sourceSets.main.res.sourceFiles` into
     * the task at registration so the lookup here is a pure file
     * walk.
     */
    fun resolveFromManifest(
        manifestFile: File?,
        stringResourceFiles: Iterable<File>,
        logger: Logger,
        debug: Boolean,
        warnIfMissing: Boolean = true,
        onPlaceholder: () -> Unit = {},
    ): String? {
        if (manifestFile == null || !manifestFile.isFile) {
            logger.warn("Could not resolve app token from any source.")
            return null
        }

        val appToken = com.bugsee.android.gradle.manifest.ManifestModifier
            .getMetaDataValue(manifestFile, APP_TOKEN_TAG)

        if (appToken.isNullOrEmpty()) {
            // Muted when a placeholder was already ignored upstream: the task logs one info skip line.
            if (warnIfMissing) logger.warn("Could not find '$APP_TOKEN_TAG' <meta-data> tag in your AndroidManifest.xml")
            return null
        }

        val resolved = if (StringResourceResolver.isStringResource(appToken)) {
            StringResourceResolver.resolve(stringResourceFiles, appToken, logger, debug)
        } else {
            appToken
        }
        if (isPlaceholder(resolved)) onPlaceholder()
        return resolved?.takeIf { isUsable(it, "manifest $APP_TOKEN_TAG", logger) }
    }

    /**
     * Back-compat wrapper used by `MappingUploadTask` / `NativeUploadTask`
     * which still follow the earlier "resolve everything at task
     * execution" pattern. Reaches into `project` for both the plugin
     * extension and the `res/` source files — this is a known
     * configuration-cache violation that predates the size-analysis
     * work and lives here until those two tasks get the same refactor
     * `BundleUploadTask` received (wire pre-resolved inputs at task
     * registration instead of reaching into project state at execution).
     *
     * New call sites should NOT use this method — call
     * [resolveFromExtension] at task registration and
     * [resolveFromManifest] with pre-wired file inputs at execution
     * instead.
     */
    @Suppress("UNCHECKED_CAST")
    fun resolve(
        project: Project,
        extension: BugseePluginExtension,
        variantName: String,
        manifestFile: File?,
        logger: Logger,
        debug: Boolean,
    ): String? {
        resolveFromExtension(extension, variantName, logger, debug)?.let { return it }
        resolveFromPropertiesFile(project.rootProject.projectDir, logger, debug)
            ?.let { return it }

        val sourceFiles: Iterable<File> = try {
            val android = project.extensions.findByName("android")
            if (android != null) {
                val sourceSets = android.javaClass.getMethod("getSourceSets").invoke(android)
                val mainSourceSet = sourceSets.javaClass
                    .getMethod("getByName", String::class.java)
                    .invoke(sourceSets, "main")
                val res = mainSourceSet.javaClass.getMethod("getRes").invoke(mainSourceSet)
                res.javaClass.getMethod("getSourceFiles").invoke(res) as Iterable<File>
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }

        return resolveFromManifest(manifestFile, sourceFiles, logger, debug)
    }
}
