// Issue #158: the TextFieldState-based text fields of foundation 1.7+ (`BasicTextField(TextFieldState, ...)` and
// `BasicSecureTextField`). They cannot share a module with `:app` (foundation 1.6.8, which still has the text2 API), so
// they get their own. Kotlin 2.x only: foundation 1.7 is compiled with the K2 Compose compiler, which the 1.9 line's
// Compose compiler 1.5.15 cannot be paired with. Plugin application order matches `:app` (Bugsee, then Compose).
apply(plugin = "com.android.application")
apply(plugin = "org.jetbrains.kotlin.android")
apply(plugin = "com.bugsee.android.gradle")
apply(plugin = "org.jetbrains.kotlin.plugin.compose")

group = "com.bugsee.testfixture"

configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "com.example.app17"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        targetSdk = 35
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    add("implementation", "androidx.compose.runtime:runtime:1.7.8")
    add("implementation", "androidx.compose.ui:ui:1.7.8")
    add("implementation", "androidx.compose.foundation:foundation:1.7.8")
    add("compileOnly", project(":stub"))
}

extensions.configure<com.bugsee.android.gradle.BugseePluginExtension>("bugsee") {
    buildInfo { enabled.set(false) }
}
