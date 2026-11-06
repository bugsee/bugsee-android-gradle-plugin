plugins {
    id("com.android.application")
    id("com.bugsee.android.gradle")
}

group = "com.bugsee.testfixture"

android {
    namespace = "com.example.oldagp"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
        targetSdk = 34
    }
}
