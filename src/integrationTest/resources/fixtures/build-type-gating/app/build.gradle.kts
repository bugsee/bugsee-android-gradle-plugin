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
        versionCode = 1
        versionName = "1.0"
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
        // Release kept UNMINIFIED so the transform's output is inspectable. R8 is not
        // what is under test here — the question is whether the transform runs at all
        // for a variant whose classpath lacks the SDK.
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    // Optional product-flavor wiring. When the
    // `bugseeFixtureMultiFlavor` property is `true` the fixture
    // exposes two flavors (`free`, `paid`) so multi-variant
    // integration tests can assert per-variant BUILD_UUID
    // discrimination + end-to-end manifest behavior. Default-off so
    // existing tier-matrix tests are unaffected.
    val multiFlavor = project.findProperty("bugseeFixtureMultiFlavor")?.toString() == "true"
    if (multiFlavor) {
        flavorDimensions += "tier"
        productFlavors {
            create("free") { dimension = "tier" }
            create("paid") { dimension = "tier" }
        }
    }
}

// Stub Bugsee SDK is published by the plugin's integrationTest task under
// the real `com.bugsee:bugsee-android:99.0.0` Maven coordinates into a
// per-build local repo. The version is a high sentinel (>= app-startup
// tracing's MIN_SDK_VERSION_WITH_DISPATCHER) so the version gate in
// AppStartupTracingInstrumentation.shouldApply does NOT skip instrumentation;
// it must stay in lockstep with the publish task in the plugin's
// build.gradle.kts. Declaring it as compileOnly here serves two purposes:
//   (1) Provides the dispatcher + annotation classes on AGP's compile-time
//       classpath so `ClassContext.loadClassData` finds them and the
//       plugin proceeds with bytecode wrapping.
//   (2) Makes `DependencyDetector.hasBugseeDependency(project, "bugsee-android")`
//       return true, triggering AppStartupTracing registration.
// compileOnly because the fixture is only assembled — never executed —
// and we don't want the stub bytecode in the dex output.
dependencies {
    // THE POINT OF THIS FIXTURE: the SDK is declared for the DEBUG build type only.
    // This is the shape a consumer uses when Bugsee ships in debug builds but not in
    // release — including the customer whose report started this investigation.
    //
    // DependencyDetector.hasBugseeDependency scans project.configurations.any { },
    // i.e. EVERY configuration rather than the variant's, so the gate answers true for
    // release too. If nothing downstream re-checks per variant, release bytecode gets
    // Bugsee calls injected against a class that is not on its runtime classpath.
    debugCompileOnly("com.bugsee:bugsee-android:99.0.0")
    implementation("androidx.startup:startup-runtime:1.1.1")
}

bugsee {
}
