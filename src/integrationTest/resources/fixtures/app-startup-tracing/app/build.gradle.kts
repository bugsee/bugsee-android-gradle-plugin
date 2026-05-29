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
    // Upload tasks (build-info + size analysis) are OFF by default in
    // this fixture — they need a Bugsee app token and are otherwise
    // unrelated to app-startup tracing. BundleUploadConfigCacheTest
    // flips `bugseeFixtureBuildInfoCc=true` to turn build-info ON for
    // ALL build types, so the dependency-collection task wiring is
    // realized and its configuration-cache compatibility can be
    // asserted. No app token is configured, so the upload task skips the
    // network at execution (BundleUploadTask returns early on an
    // unresolved token) — the test only cares that CONFIGURATION
    // serializes cleanly under CC.
    val buildInfoCc = project.findProperty("bugseeFixtureBuildInfoCc")?.toString() == "true"
    buildInfo {
        enabled.set(buildInfoCc)
        if (buildInfoCc) {
            allBuildTypes.set(true)
        }
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

    // Optional opt-out for the extension-stripping path. Defaults to
    // the plugin's default (`true`) when the property is absent. Used
    // by the manifest-stripping integration test to exercise BOTH
    // sides of the `optimizeExtensionsLoading` switch from the same
    // fixture.
    val optimizeExt = project.findProperty("bugseeFixtureOptimizeExtensions")?.toString()
    if (optimizeExt != null) {
        optimizeExtensionsLoading.set(optimizeExt == "true")
    }
}
