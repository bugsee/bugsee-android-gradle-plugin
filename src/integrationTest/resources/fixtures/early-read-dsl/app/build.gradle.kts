plugins {
    id("com.android.application")
    id("com.bugsee.android.gradle")
}

android {
    namespace = "com.example.fixture"
    compileSdk = 34
    defaultConfig { minSdk = 21; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// An early reader (a convention / analysis plugin would do this at apply time): reading the
// dependency set fires the plugin's auto-add BEFORE the bugsee { } block below has run.
// -PearlyRead=false is the normal-order control.
if (providers.gradleProperty("earlyRead").orNull != "false") {
    println("EARLY-READ implementation deps: " + configurations.getByName("implementation").incoming.dependencies.size)
}

val declareSdk = providers.gradleProperty("declareSdk").orNull == "true"

bugsee {
    // -PdeclareSdk=true: the SDK is on the classpath, so the instrumentation must run.
    if (!declareSdk) sdkAutoLoad.set(false)
}

dependencies {
    // The SDK is absent unless the control asks for it (the auto-add range is not published in
    // the stub repo, so a leaked auto-add would also fail resolution).
    if (declareSdk) implementation("com.bugsee:bugsee-android:99.0.0")
}
