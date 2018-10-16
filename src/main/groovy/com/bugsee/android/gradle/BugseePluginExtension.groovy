package com.bugsee.android.gradle

import groovy.transform.PackageScope

class BugseePluginExtension {
    def String endpoint = 'https://api.bugsee.com'
    @PackageScope String defaultAppToken = null
    @PackageScope Closure<String> appTokenByVariant;
    // It is hard to use closures from Kotlin KTS, hence add another way to provide app token based on current build variant. AppTokenProvider is implemented
    // in a strong type style and is easy to use from Kotlin KTS.
    AppTokenProvider appTokenProvider

    def boolean debug = false;

    def appToken(String token) {
        defaultAppToken = token;
    }

    def appToken(Closure<String> tokenClosure) {
        appTokenByVariant = tokenClosure
    }

    def appToken(AppTokenProvider appTokenProvider) {
        this.appTokenProvider = appTokenProvider
    }

    AppTokenProvider getAppTokenProvider() {
        return appTokenProvider
    }

    // The following 2 functions are necessary for Gradle versions < 3, because they can't access defaultAppToken and appTokenByVariant directly - they fail with
    // MissingPropertyException.
    Closure<String> getAppTokenByVariant() {
        return appTokenByVariant;
    }

    String getDefaultAppToken() {
        return defaultAppToken;
    }
}
