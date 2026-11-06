import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// `-PbugseeFixtureKotlin2=true` applies the Kotlin 2.x Compose Gradle plugin; Kotlin 1.9 has none, and
// AGP's composeOptions below supplies the compiler there. `-PbugseeFixtureComposeFirst=true` applies
// that plugin before Bugsee instead of after it.
val kotlin2 = project.findProperty("bugseeFixtureKotlin2")?.toString() == "true"
val composeFirst = project.findProperty("bugseeFixtureComposeFirst")?.toString() == "true"
if (kotlin2 && composeFirst) apply(plugin = "org.jetbrains.kotlin.plugin.compose")
apply(plugin = "com.bugsee.android.gradle")
if (kotlin2 && !composeFirst) apply(plugin = "org.jetbrains.kotlin.plugin.compose")

group = "com.bugsee.testfixture"

android {
    namespace = "com.example.app"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
        targetSdk = 34
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (!kotlin2) {
        buildFeatures { compose = true }
        composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    }
}

dependencies {
    implementation("androidx.compose.runtime:runtime:1.6.8")
    implementation("androidx.compose.ui:ui:1.6.8")
    // REAL library text fields (issues #149, #151): their callee name carries the file-facade class on Kotlin 1.9.
    implementation("androidx.compose.material:material:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.foundation:foundation:1.6.8")
    compileOnly(project(":stub"))
}

extensions.configure<com.bugsee.android.gradle.BugseePluginExtension>("bugsee") {
    buildInfo { enabled.set(false) }
}

// Reports the compiler-plugin classpath IN ORDER, which is the order the compiler's -Xplugin
// arguments carry and therefore the order its registrars are loaded in.
tasks.register("printComposePluginOrder") {
    val compile = tasks.named<KotlinCompile>("compileDebugKotlin")
    val classpath = compile.map { task -> task.pluginClasspath.files.map { it.name } }
    doLast { classpath.get().forEachIndexed { i, name -> println("PLUGIN_CP[$i]=$name") } }
}
