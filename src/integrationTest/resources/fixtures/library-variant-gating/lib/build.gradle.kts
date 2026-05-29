plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.bugsee.android.gradle")
}

// Mirror the root group so the auto-install short-circuit treats
// this as a Bugsee internal.
group = "com.bugsee.testfixture"

android {
    namespace = "com.example.libfixture"
    compileSdk = 36
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    compileOnly("com.bugsee:bugsee-android:99.0.0")
}

bugsee {
    // Disable upload tasks — irrelevant to the gating test under
    // library-variant scope.
    buildInfo {
        enabled.set(false)
    }
}
