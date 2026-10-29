plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.bugsee.android.gradle")
}

group = project.findProperty("bugseeFixtureGroup")?.toString() ?: "com.customer.kmp"

kotlin {
    androidLibrary {
        namespace = "com.example.kmpfixture"
        compileSdk = 36
        minSdk = 21
    }
}

bugsee {
    buildInfo {
        enabled.set(false)
    }
}
