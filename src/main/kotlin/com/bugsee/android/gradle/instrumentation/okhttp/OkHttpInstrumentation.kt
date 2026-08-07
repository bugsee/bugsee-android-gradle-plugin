package com.bugsee.android.gradle.instrumentation.okhttp

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.instrumentation.BugseeSdkVersion
import com.bugsee.android.gradle.instrumentation.DependencyDetector
import com.bugsee.android.gradle.instrumentation.Instrumentation
import org.gradle.api.Project

/**
 * OkHttp instrumentation that injects BugseeOkHttpInterceptor into every
 * OkHttpClient.Builder.build() call site.
 *
 * Gated on the presence of `com.bugsee:bugsee-android-okhttp` dependency.
 *
 * The WebSocket rewrite carries a SECOND, narrower gate. Plugin and SDK versions
 * are not released in lockstep and cannot be assumed to match, so each side has
 * to tolerate the other lacking a feature:
 *
 *  - **SDK without the plugin lane** — already fine. `BugseeOkHttpWebSockets` is
 *    simply never invoked; it costs nothing and captures nothing.
 *  - **Plugin without the SDK class** — NOT fine without this gate. The injected
 *    `INVOKESTATIC` lives in the host app's own method, so a missing class is a
 *    `NoClassDefFoundError` at the app's call site, where no SDK code path can
 *    absorb it.
 *
 * So the plugin refuses to emit the WebSocket rewrite unless the resolved okhttp
 * extension is new enough to contain the target. Crucially this gates only that
 * branch: the HTTP interceptor rewrite still applies against every SDK that ships
 * the extension. Gating the whole lane would trade a lost WebSocket feature for
 * lost request capture.
 */
internal class OkHttpInstrumentation : Instrumentation {

    override val name: String = "OkHttp"
    override val key: String = "okhttp"

    /**
     * Resolved in [shouldApply] and read by [apply]. The registrar calls the two
     * back to back for each variant (shouldApply first), so the value is always
     * current for the variant being configured.
     */
    private var webSocketCapture: Boolean = false

    override fun shouldApply(project: Project, coreSdkAutoLoad: Boolean): Boolean {
        // Extension-gated: requires the okhttp extension AAR specifically, so
        // core-SDK auto-load alone does not enable it.
        if (!DependencyDetector.hasBugseeDependency(project, "bugsee-android-okhttp", "okhttp")) {
            return false
        }
        webSocketCapture = resolveWebSocketCapture(project)
        return true
    }

    /**
     * Whether the resolved okhttp extension ships `BugseeOkHttpWebSockets`.
     *
     * Deliberately permissive when the version cannot be read — project
     * dependencies, Gradle ranges (`7.+`), and unresolved version-catalog
     * references all yield null or an unparseable string. Refusing on "unknown"
     * would silently disable WebSocket capture for everyone building against a
     * composite or a range, which is the common case in this repo's own sample
     * app. We stand down only when the version is parseable AND provably too old.
     */
    /** Internal rather than private so the version gate can be tested directly. */
    internal fun resolveWebSocketCapture(project: Project): Boolean {
        val declared =
            DependencyDetector.getBugseeDependencyVersion(project, "bugsee-android-okhttp")
        val parsed = BugseeSdkVersion.parse(declared) ?: return true
        if (parsed < MIN_SDK_VERSION_WITH_WEBSOCKETS) {
            project.logger.warn(
                "Bugsee gradle plugin: OkHttp WebSocket capture requires " +
                    "`com.bugsee:bugsee-android-okhttp` $MIN_SDK_VERSION_WITH_WEBSOCKETS or " +
                    "newer (found $declared). Skipping the newWebSocket rewrite to avoid " +
                    "NoClassDefFoundError on BugseeOkHttpWebSockets; HTTP request capture is " +
                    "unaffected. Upgrade the SDK to capture WebSocket frames."
            )
            return false
        }
        return true
    }

    override fun apply(variant: Variant, excludes: Set<String>) {
        variant.instrumentation.transformClassesWith(
            OkHttpClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) { params ->
            params.targetClass.set("com.bugsee.library.okhttp.BugseeOkHttpInterceptor")
            params.excludes.set(excludes)
            params.webSocketCapture.set(webSocketCapture)
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COPY_FRAMES
        )
    }

    private companion object {
        /**
         * First `bugsee-android-okhttp` release whose published AAR actually
         * contains `BugseeOkHttpWebSockets`.
         *
         * The class existed in source earlier, but self-R8 stripped it from every
         * published AAR until the keep rule landed for 7.1.0 — so "the source has
         * it" was never the right question to ask; "the artifact has it" is.
         */
        private val MIN_SDK_VERSION_WITH_WEBSOCKETS = BugseeSdkVersion(
            major = 7,
            minor = 1,
            patch = 0,
            // Stable: "" ranks above any 7.1.0-betaN, so a pre-release of the
            // same line is (correctly) treated as not yet carrying the class.
            preLabel = "",
            preNumber = -1,
        )
    }
}
