package com.bugsee.android.gradle.instrumentation.extensions_init

/**
 * Resolved facade + register-method coordinates for one Bugsee extension,
 * derived from the init-provider FQN that was stripped from the merged
 * manifest.
 *
 * Naming convention (enforced for every extension):
 *
 * | init-provider FQN                                          | facade FQN                              | register method            |
 * |-----------------------------------------------------------|-----------------------------------------|----------------------------|
 * | `com.bugsee.library.BugseeFeedbackInitProvider`           | `com.bugsee.library.BugseeFeedback`     | `registerFeedbackExtension`|
 * | `com.bugsee.library.BugseeRemotingInitProvider`           | `com.bugsee.library.BugseeRemoting`     | `registerRemotingExtension`|
 * | `com.bugsee.library.compose.BugseeComposeInitProvider`    | `com.bugsee.library.BugseeCompose`      | `registerComposeExtension` |
 * | `com.bugsee.library.BugseeNdkInitProvider`                | `com.bugsee.library.BugseeNdk`          | `registerNdkExtension`     |
 *
 * The facade always lives in `com.bugsee.library` regardless of which
 * subpackage holds the init provider — this is the contract documented in
 * `.claude/rules/extensions.md` of the SDK repo.
 */
internal data class ExtensionSpec(
    /** Internal name of the facade class (`/`-separated). */
    val facadeInternalName: String,
    /** Static method on the facade that performs the registration. */
    val registerMethodName: String,
    /** The init-provider FQN this spec was derived from. Kept for diagnostics. */
    val initProviderFqn: String,
) {
    companion object {
        private const val PREFIX = "Bugsee"
        private const val SUFFIX = "InitProvider"
        private const val FACADE_PACKAGE_INTERNAL = "com/bugsee/library"

        /**
         * Derives an [ExtensionSpec] from a fully-qualified init-provider
         * class name (dot-separated). Returns `null` if the name does not
         * match the `*.Bugsee<Name>InitProvider` shape — defensive against
         * stale or malformed entries in the detection file.
         *
         * Example: `com.bugsee.library.compose.BugseeComposeInitProvider`
         * yields `ExtensionSpec("com/bugsee/library/BugseeCompose", "registerComposeExtension", …)`.
         */
        fun fromInitProviderFqn(fqn: String): ExtensionSpec? {
            val simpleName = fqn.substringAfterLast('.')
            if (!simpleName.startsWith(PREFIX) || !simpleName.endsWith(SUFFIX)) return null
            val name = simpleName.substring(PREFIX.length, simpleName.length - SUFFIX.length)
            if (name.isEmpty()) return null
            return ExtensionSpec(
                facadeInternalName = "$FACADE_PACKAGE_INTERNAL/$PREFIX$name",
                registerMethodName = "register${name}Extension",
                initProviderFqn = fqn,
            )
        }
    }
}
