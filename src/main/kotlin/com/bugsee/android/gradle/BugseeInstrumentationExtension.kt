package com.bugsee.android.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * DSL block for controlling individual bytecode instrumentations.
 *
 * ```kotlin
 * bugsee {
 *     instrumentation {
 *         enabled.set(true)           // global switch
 *         okhttp.set(false)           // disable OkHttp instrumentation
 *         httpEngine.set(true)        // HttpEngine network instrumentation
 *         log.set(true)
 *         thread.set(true)
 *         mainThreadMisuse.set(true)
 *         ktor.set(true)              // Ktor HTTP client plugin auto-loading
 *         cronet.set(true)            // Cronet HTTP client plugin auto-loading
 *     }
 * }
 * ```
 *
 * Properties without an explicit value fall back to gradle properties,
 * then manifest meta-data, then default `true`.
 */
abstract class BugseeInstrumentationExtension @Inject constructor(objects: ObjectFactory) {

    /** Global switch for all bytecode instrumentation. */
    val enabled: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /** OkHttp interceptor injection. */
    val okhttp: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /** HttpEngine (Cronet) network request instrumentation. */
    val httpEngine: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /** android.util.Log redirection to BugseeLogAdapter. */
    val log: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /** Thread/Runnable run() registration. */
    val thread: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /** Main-thread misuse detection (disk I/O, network, DB, SharedPreferences). */
    val mainThreadMisuse: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /** Operation dispatch (start/end tracking for disk I/O, network, DB, SharedPreferences). */
    val operationDispatch: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /** Compose tag injection via Kotlin compiler plugin. */
    val compose: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /**
     * Compose secure-field auto-detection via Kotlin compiler plugin.
     *
     * When enabled (default), the Bugsee compose-compiler-plugin scans
     * user-code call sites of `TextField`, `OutlinedTextField`, and
     * `BasicTextField` from `androidx.compose.material`,
     * `androidx.compose.material3`, and `androidx.compose.foundation.text`,
     * and injects `Modifier.bugseeSecure()` into calls whose
     * `visualTransformation` argument is statically detectable as
     * `PasswordVisualTransformation`. The marked composables' bounds are
     * then redacted in captured screenshots and video.
     *
     * Disable this flag if you prefer to mark sensitive composables
     * exclusively via manual `Modifier.bugseeSecure()`.
     *
     * Independent of [compose] (tag injection): each subfeature can be
     * enabled or disabled in isolation.
     */
    val composeSecure: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /** Compose touch input capture via AndroidComposeView instrumentation. */
    val composeInput: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /** Ktor HTTP client plugin auto-loading. */
    val ktor: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /** Cronet HTTP client plugin auto-loading. */
    val cronet: Property<Boolean> = objects.property(Boolean::class.javaObjectType)

    /**
     * Returns the DSL property for the given instrumentation key, or `null`
     * if the key does not match any known property.
     */
    internal fun propertyForKey(key: String): Property<Boolean>? = when (key) {
        "okhttp" -> okhttp
        "http_engine" -> httpEngine
        "log" -> log
        "thread" -> thread
        "mainThreadMisuse" -> mainThreadMisuse
        "operationDispatch" -> operationDispatch
        "compose" -> compose
        "composeSecure" -> composeSecure
        "composeInput" -> composeInput
        else -> null
    }
}
