plugins {
    id("com.android.application")
    id("com.bugsee.android.gradle")
}

group = "com.bugsee.testfixture"

fun prop(name: String): String? = project.findProperty(name)?.toString()

android {
    namespace = "com.example.nativefixture"
    compileSdk = 36
    ndkVersion = "26.1.10909125"
    defaultConfig {
        minSdk = 21
        targetSdk = 35
        versionCode = 7
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
        // Unset by default: that is the case #10 fixes (AGP then produces no
        // native debug symbols, so only merged_native_libs can be uploaded).
        prop("bugseeE2eSymbolLevel")?.let { ndk.debugSymbolLevel = it }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

bugsee {
    endpoint.set(prop("bugseeE2eEndpoint") ?: "https://api.bugsee.com")
    appToken("e2e-token")
    debug.set(true)
    cliAutoUpdate.set(false)
    prop("bugseeE2eCli")?.let { cliPath.set(it) }
    ndk {
        enabled.set(true)
        prop("bugseeE2eUseMerged")?.let { useMergedNativeLibs.set(it.toBoolean()) }
        prop("bugseeE2eForce")?.let { forceDebugSymbolsUpload.set(it.toBoolean()) }
    }
}
