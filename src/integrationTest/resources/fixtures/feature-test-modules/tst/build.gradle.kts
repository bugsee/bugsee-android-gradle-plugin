plugins {
    id("com.android.test")
    id("com.bugsee.android.gradle")
}

group = project.findProperty("bugseeFixtureGroup")?.toString() ?: "com.customer.tst"

android {
    namespace = "com.example.tst"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        targetSdk = 35
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    targetProjectPath = ":app"
}

// `-PbugseeFixtureCompose=true`: the module uses Compose (Kotlin plugin + a Compose runtime dependency), so
// the Bugsee Compose compiler plugin applies to it and needs the SDK.
if (project.findProperty("bugseeFixtureCompose")?.toString() == "true") {
    apply(plugin = "org.jetbrains.kotlin.android")
    dependencies { implementation("androidx.compose.runtime:runtime:1.7.8") }
    // `-PbugseeFixtureComposeInTests=true`: every compilation of a test module is test code, so it needs this.
    if (project.findProperty("bugseeFixtureComposeInTests")?.toString() == "true") {
        extensions.configure<com.bugsee.android.gradle.BugseePluginExtension>("bugsee") {
            instrumentation { composeInTests.set(true) }
        }
    }
}
