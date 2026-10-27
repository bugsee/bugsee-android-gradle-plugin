plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // A second plugin only this module requests: it gives :lib a different plugin classloader
    // than :app, so the Bugsee plugin classes are loaded twice in one build.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
    id("com.bugsee.android.gradle")
}

group = "com.bugsee.testfixture"

android {
    namespace = "com.example.isolated.lib"
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
