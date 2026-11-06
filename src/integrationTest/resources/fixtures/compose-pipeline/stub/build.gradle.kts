plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

group = "com.bugsee.testfixture"

android {
    namespace = "com.example.stub"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.compose.ui:ui:1.6.8")
}
