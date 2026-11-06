plugins {
    id("com.android.dynamic-feature")
    id("com.bugsee.android.gradle")
}

// `-PbugseeFixtureEarlyRead=true`: an early reader of `implementation` (another plugin at apply time) fires the
// SDK auto-add before the `bugsee { }` block and the Compose dependency exist; afterEvaluate must correct it.
// With `-PbugseeFixtureComposeDepEarly=true` the Compose dependency is already declared at that point, so the
// provisional decision adds the SDK and the later DSL (ComposeOff) must take it back.
if (project.findProperty("bugseeFixtureEarlyRead")?.toString() == "true") {
    if (project.findProperty("bugseeFixtureComposeDepEarly")?.toString() == "true") {
        dependencies.add("implementation", "androidx.compose.runtime:runtime:1.7.8")
    }
    println("EARLY-READ " + configurations.getByName("implementation").incoming.dependencies.size)
}

group = project.findProperty("bugseeFixtureGroup")?.toString() ?: "com.customer.feature"

android {
    namespace = "com.example.feature"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":app"))
    // The documented pattern for a feature that calls the SDK: the base app owns the SDK at runtime, the
    // feature declares it compileOnly (the SDK is never packaged into the feature split).
    if (project.findProperty("bugseeFixtureDeclareSdk")?.toString() == "true") {
        compileOnly("com.bugsee:bugsee-android:99.0.0")
    }
}

// `-PbugseeFixtureCompose=true`: the module uses Compose (Kotlin plugin + a Compose runtime dependency), so
// the Bugsee Compose compiler plugin applies to it and needs the SDK.
if (project.findProperty("bugseeFixtureCompose")?.toString() == "true") {
    apply(plugin = "org.jetbrains.kotlin.android")
    dependencies { implementation("androidx.compose.runtime:runtime:1.7.8") }
}

// `-PbugseeFixtureComposeOff=true`: compose tag and secure instrumentation both disabled.
if (project.findProperty("bugseeFixtureComposeOff")?.toString() == "true") {
    extensions.configure<com.bugsee.android.gradle.BugseePluginExtension>("bugsee") {
        instrumentation {
            compose.set(false)
            composeSecure.set(false)
        }
    }
}
