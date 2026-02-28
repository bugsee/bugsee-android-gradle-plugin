package com.bugsee.android.gradle

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class BugseePluginExtension @Inject constructor(objects: ObjectFactory) {

    /** Bugsee API endpoint URL. */
    val endpoint: Property<String> = objects.property(String::class.java).convention("https://api.bugsee.com")

    /** Enable debug logging from the plugin. */
    val debug: Property<Boolean> = objects.property(Boolean::class.javaObjectType).convention(false)

    /** Enable NDK symbol upload. */
    val ndk: Property<Boolean> = objects.property(Boolean::class.javaObjectType).convention(false)

    /** Per-feature instrumentation configuration. */
    val instrumentation: BugseeInstrumentationExtension = objects.newInstance(BugseeInstrumentationExtension::class.java)

    /** Configure instrumentation via a DSL block. */
    fun instrumentation(action: Action<BugseeInstrumentationExtension>) {
        action.execute(instrumentation)
    }

    /** Global instrumentation switch. Delegates to [instrumentation.enabled] for backward compatibility. */
    val instrumentationEnabled: Property<Boolean> get() = instrumentation.enabled

    /** Default app token, used when no variant-specific token is provided. */
    internal var defaultAppToken: String? = null

    /** Closure-style variant token resolver: (variantName) -> token. */
    internal var appTokenByVariant: ((String) -> String?)? = null

    /** Strong-typed app token provider interface. */
    internal var appTokenProvider: AppTokenProvider? = null

    /** Set the default app token. */
    fun appToken(token: String) {
        defaultAppToken = token
    }

    /**
     * Set a variant-based app token resolver function.
     * The function receives the variant name and returns the app token.
     */
    fun appToken(tokenResolver: (String) -> String?) {
        appTokenByVariant = tokenResolver
    }

    /** Set a strong-typed app token provider. */
    fun appToken(provider: AppTokenProvider) {
        appTokenProvider = provider
    }

    /** Enable or disable NDK symbol upload. */
    fun ndk(enabled: Boolean) {
        ndk.set(enabled)
    }
}
