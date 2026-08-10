package com.bugsee.android.gradle.instrumentation.okhttp

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.Variant
import com.bugsee.android.gradle.instrumentation.SdkClassProbe
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

    override fun shouldApply(project: Project, coreSdkAutoLoad: Boolean): Boolean {
        // Extension-gated: requires the okhttp extension AAR specifically, so
        // core-SDK auto-load alone does not enable it.
        return DependencyDetector.hasBugseeDependency(project, "bugsee-android-okhttp", "okhttp")
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
     * Whether the SDK on this variant's classpath actually defines
     * `BugseeOkHttpWebSockets`, as a lazy provider evaluated at execution time.
     *
     * This asks the same question the injected bytecode will ask of the runtime
     * classpath, so it is correct for cases a version comparison cannot reach:
     * Gradle ranges, platform/BOM-managed versions, project and composite
     * dependencies — none of which expose a usable version string — and a class
     * that was present in source but stripped from the published artifact, which
     * is exactly how this symbol was missing from every release before 7.1.0.
     *
     * Resolution stays lazy: `artifacts.elements` is a Gradle provider, so the
     * classpath is resolved when the transform runs rather than during
     * configuration.
     */
    private fun webSocketCaptureProvider(variant: Variant): Provider<Boolean> =
        variant.runtimeConfiguration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
            val present = SdkClassProbe.containsClass(artifacts.map { it.file }, WEBSOCKETS_CLASS)
            if (!present) {
                LOGGER.warn(
                    "Bugsee gradle plugin: the resolved Bugsee SDK does not contain " +
                        "BugseeOkHttpWebSockets, so OkHttp WebSocket capture is unavailable. " +
                        "Skipping the newWebSocket rewrite; HTTP request capture is unaffected. " +
                        "Upgrade the Bugsee SDK to capture WebSocket frames."
                )
            }
            present
        }

    override fun apply(variant: Variant, excludes: Set<String>) {
        variant.instrumentation.transformClassesWith(
            OkHttpClassVisitorFactory::class.java,
            InstrumentationScope.ALL
        ) { params ->
            params.targetClass.set("com.bugsee.library.okhttp.BugseeOkHttpInterceptor")
            params.excludes.set(excludes)
            params.webSocketCapture.set(webSocketCaptureProvider(variant))
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COPY_FRAMES
        )
    }

    private companion object {
        private val LOGGER = org.gradle.api.logging.Logging.getLogger(OkHttpInstrumentation::class.java)

        /** JVM internal name of the class the WebSocket rewrite targets. */
        private const val WEBSOCKETS_CLASS = "com/bugsee/library/okhttp/BugseeOkHttpWebSockets"

    }
}
