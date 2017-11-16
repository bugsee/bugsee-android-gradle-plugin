package com.bugsee.android.gradle

import groovy.transform.PackageScope

class BugseePluginExtension {
    def String endpoint = 'https://api.bugsee.com'
    @PackageScope String defaultAppToken = null
    @PackageScope Closure<String> appTokenByVariant;
    def boolean debug = false;

    def appToken(String token) {
        defaultAppToken = token;
    }

    def appToken(Closure<String> tokenClosure) {
        appTokenByVariant = tokenClosure
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
