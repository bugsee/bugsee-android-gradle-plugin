package com.bugsee.android.gradle.instrumentation.extensions_init

/**
 * Facade + register-method coordinates for one Bugsee extension, keyed by the
 * init-provider FQN the manifest task strips.
 *
 * [KNOWN] is the single source of truth for BOTH halves of the consolidation:
 * the manifest task strips a `<provider>` only if its FQN is listed here, and
 * the injection emits exactly the listed register call for it. An unlisted
 * provider — a wrapper's, a customer's, or an SDK extension newer than this
 * plugin — is left in the manifest and keeps registering itself as a
 * ContentProvider, so an unknown extension costs a missed optimisation, never
 * a dead extension.
 *
 * Deliberately a table rather than a `Bugsee<Name>InitProvider` name pattern:
 * a pattern matched providers from any package and derived a facade that need
 * not exist, so the provider was removed and the injected call hit a missing
 * class inside a catch-all — the provider vanished with a green build.
 */
internal data class ExtensionSpec(
    /** Internal name of the facade class (`/`-separated). */
    val facadeInternalName: String,
    /** Static method on the facade that performs the registration. */
    val registerMethodName: String,
    /** The init-provider FQN this spec replaces. Kept for diagnostics. */
    val initProviderFqn: String,
    /**
     * The `com.bugsee:<artifactId>` module that ships the provider (and the facade). Verified against the
     * published 7.5.0 AARs: each extension artifact carries exactly this one provider, so a module on the
     * variant's runtime classpath says which providers the manifest will contain, without the manifest.
     */
    val artifactId: String,
) {
    companion object {
        /** Every SDK extension whose provider the plugin may consolidate. */
        val KNOWN: List<ExtensionSpec> = listOf(
            known("com.bugsee.library.BugseeFeedbackInitProvider", "Feedback", "bugsee-android-feedback"),
            known("com.bugsee.library.BugseeRemotingInitProvider", "Remoting", "bugsee-android-remoting"),
            known("com.bugsee.library.compose.BugseeComposeInitProvider", "Compose", "bugsee-android-compose"),
            known("com.bugsee.library.BugseeNdkInitProvider", "Ndk", "bugsee-android-ndk"),
            known("com.bugsee.library.BugseeOkHttpInitProvider", "OkHttp", "bugsee-android-okhttp"),
            known("com.bugsee.library.BugseeKtor2InitProvider", "Ktor2", "bugsee-android-ktor-2"),
            known("com.bugsee.library.BugseeKtor3InitProvider", "Ktor3", "bugsee-android-ktor-3"),
            known("com.bugsee.library.BugseeCronetInitProvider", "Cronet", "bugsee-android-cronet"),
            known("com.bugsee.library.BugseeLeakInitProvider", "Leak", "bugsee-android-leak"),
        )

        private val BY_INIT_PROVIDER: Map<String, ExtensionSpec> =
            KNOWN.associateBy { it.initProviderFqn }

        /** The spec for [fqn], or `null` when it is not a known Bugsee extension provider. */
        fun fromInitProviderFqn(fqn: String): ExtensionSpec? = BY_INIT_PROVIDER[fqn]

        /** The facade always lives in `com.bugsee.library`, whatever package holds the provider. */
        private fun known(initProviderFqn: String, name: String, artifactId: String) = ExtensionSpec(
            facadeInternalName = "com/bugsee/library/Bugsee$name",
            registerMethodName = "register${name}Extension",
            initProviderFqn = initProviderFqn,
            artifactId = artifactId,
        )
    }
}
