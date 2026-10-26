plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.bugsee.android.gradle")
}

// Mirrors the root group so the auto-install short-circuit treats this as a Bugsee internal.
group = "com.bugsee.testfixture"

android {
    namespace = "com.example.isolated.app"
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
}

dependencies {
    implementation(project(":lib"))
    compileOnly("com.bugsee:bugsee-android:99.0.0")
}

bugsee {
    appToken("00000000-aaaa-bbbb-cccc-111111111111")
    buildInfo {
        enabled.set(false)
    }
}
