import com.bugsee.android.gradle.StartupTier

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.bugsee.android.gradle")
}

// Mirror the root group so this submodule is also treated as a Bugsee
// internal by the auto-install short-circuit (project.group prefix check).
group = "com.bugsee.testfixture"

android {
    namespace = "com.example.fixture"
    compileSdk = 36
    defaultConfig {
        minSdk = 21
        targetSdk = 35
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
    }
}

// Stub Bugsee SDK is published by the plugin's integrationTest task under
// the real `com.bugsee:bugsee-android:1.0.0` Maven coordinates into a
// per-build local repo. Declaring it as compileOnly here serves two
// purposes:
//   (1) Provides the dispatcher + annotation classes on AGP's compile-time
//       classpath so `ClassContext.loadClassData` finds them and the
//       plugin proceeds with bytecode wrapping.
//   (2) Makes `DependencyDetector.hasBugseeDependency(project, "bugsee-android")`
//       return true, triggering AppStartupTracing registration.
// compileOnly because the fixture is only assembled — never executed —
// and we don't want the stub bytecode in the dex output.
dependencies {
    compileOnly("com.bugsee:bugsee-android:1.0.0")
    implementation("androidx.startup:startup-runtime:1.1.1")
}

bugsee {
    // Disable upload tasks — they require a Bugsee app token and have
    // configuration-cache incompatibilities unrelated to the
    // app-startup tracing feature under test. Keeping them on would
    // pollute the integration test with unrelated failures.
    buildInfo {
        enabled.set(false)
    }

    // Tier comes from the bugseeStartupTier project property — when
    // missing the typed DSL property is left unset and the plugin's
    // resolver falls through to the Gradle-property / manifest source.
    // Passing the property as `bugseeStartupTier=DETAILED` exercises
    // the typed DSL path.
    val tier = project.findProperty("bugseeStartupTier")?.toString()
    if (!tier.isNullOrBlank()) {
        instrumentation {
            startupTier.set(StartupTier.valueOf(tier))
        }
    }
}
