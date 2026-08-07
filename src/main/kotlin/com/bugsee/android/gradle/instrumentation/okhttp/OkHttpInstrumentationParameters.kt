package com.bugsee.android.gradle.instrumentation.okhttp

import com.bugsee.android.gradle.instrumentation.BugseeInstrumentationParameters
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input

/**
 * Parameters for the OkHttp instrumentation.
 *
 * Adds a second, finer gate on top of the lane's own extension check. The
 * interceptor rewrite (`addIfAbsent`) works against every SDK that ships the
 * okhttp extension, but the WebSocket rewrite targets a class introduced later,
 * so the two cannot share a single on/off decision.
 */
internal interface OkHttpInstrumentationParameters : BugseeInstrumentationParameters {

    /**
     * Whether to rewrite `newWebSocket` call sites to
     * `BugseeOkHttpWebSockets.newWebSocket`.
     *
     * Resolved once at configuration time (see
     * [OkHttpInstrumentation.shouldApply]) rather than probed per class: a
     * `ClassContext.loadClassData` probe is unreliable across AGP's
     * artifact-transform isolation boundary, which is exactly why that gate was
     * removed from the call-site factories.
     *
     * When `false` the lane still injects the HTTP interceptor — only the
     * WebSocket branch stands down. Skipping the whole lane instead would take
     * ordinary request capture down with it on older SDKs, which is a far worse
     * outcome than losing WebSocket frames.
     */
    @get:Input
    val webSocketCapture: Property<Boolean>
}
