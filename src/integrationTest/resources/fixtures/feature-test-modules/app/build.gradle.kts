plugins {
    id("com.android.application")
    id("com.bugsee.android.gradle")
}

// A customer-like group on purpose: a `com.bugsee*` group is exempt from the auto-add and the warnings.
group = project.findProperty("bugseeFixtureGroup")?.toString() ?: "com.customer.app"

android {
    namespace = "com.example.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    dynamicFeatures += ":feature"
}

// Default: no SDK declared, so the auto-add is observable on `implementation`. With
// `-PbugseeFixtureDeclareSdk=true` the stub SDK is declared by the base app (build mode: compiles,
// instruments). The base app owns the SDK; AGP rejects a compileOnly SDK shared with a feature.
if (project.findProperty("bugseeFixtureDeclareSdk")?.toString() == "true") {
    dependencies {
        implementation("com.bugsee:bugsee-android:99.0.0")
    }
}

// `-PbugseeFixtureCompose=true`: the module uses Compose (Kotlin plugin + a Compose runtime dependency), so
// the Bugsee Compose compiler plugin applies to it and needs the SDK.
if (project.findProperty("bugseeFixtureCompose")?.toString() == "true") {
    apply(plugin = "org.jetbrains.kotlin.android")
    dependencies { implementation("androidx.compose.runtime:runtime:1.7.8") }
}
