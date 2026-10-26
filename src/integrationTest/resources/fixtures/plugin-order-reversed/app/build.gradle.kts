// THE POINT OF THIS FIXTURE: the Bugsee plugin is listed BEFORE the Android plugin.
// The wiring must still happen (issue #46).
plugins {
    id("com.bugsee.android.gradle")
    id("com.android.application")
}

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
}

bugsee {
}
