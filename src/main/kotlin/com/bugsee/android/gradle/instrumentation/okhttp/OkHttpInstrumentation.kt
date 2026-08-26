package com.bugsee.android.gradle.instrumentation.okhttp

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.instrumentation.SdkClassProbe
import com.bugsee.android.gradle.instrumentation.SdkSymbolAvailability
import org.gradle.api.provider.Provider
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
     * Captured in [shouldApply] for use by [apply], which AGP's Variant API does not hand a
     * Project. The registrar calls the two back to back for each variant, shouldApply first.
     */
    private var hostProject: Project? = null

    override fun shouldApply(project: Project, coreSdkAutoLoad: Boolean, scope: Set<String>?): Boolean {
        hostProject = project
        // Extension-gated: requires the okhttp extension AAR specifically, so
        // core-SDK auto-load alone does not enable it.
        return DependencyDetector.hasBugseeDependency(project, "bugsee-android-okhttp", "okhttp", scope)
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
    /**
     * Whether the resolved SDK defines `BugseeOkHttpWebSockets`.
     *
     * Routed through the shared [SdkSymbolAvailability] rather than resolving the variant's
     * runtime classpath directly — doing the latter makes the ASM transform depend on the very
     * classpath it instruments, which breaks resolution outright for consumers with Android
     * project dependencies.
     */
    private fun webSocketCaptureProvider(project: Project): Provider<Boolean> =
        SdkSymbolAvailability.of(project, WEBSOCKETS_FQN, "OkHttp WebSocket capture", LOGGER_)

    override fun apply(variant: Variant, excludes: Set<String>) {
        variant.instrumentation.transformClassesWith(
            OkHttpClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) { params ->
            params.targetClass.set("com.bugsee.library.okhttp.BugseeOkHttpInterceptor")
            hostProject?.let { p ->
                params.symbolAvailable.set(
                    SdkSymbolAvailability.of(
                        p,
                        "com.bugsee.library.okhttp.BugseeOkHttpInterceptor",
                        "OkHttp request capture",
                        LOGGER_,
                    )
                )
                params.webSocketCapture.set(webSocketCaptureProvider(p))
            }
            params.excludes.set(excludes)
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COPY_FRAMES
        )
    }

    private companion object {
        val LOGGER_ = org.gradle.api.logging.Logging.getLogger(OkHttpInstrumentation::class.java)

        private val LOGGER = org.gradle.api.logging.Logging.getLogger(OkHttpInstrumentation::class.java)

        /** JVM internal name of the class the WebSocket rewrite targets. */
        private const val WEBSOCKETS_FQN = "com.bugsee.library.okhttp.BugseeOkHttpWebSockets"

    }
}
