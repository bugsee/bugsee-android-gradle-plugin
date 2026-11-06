plugins {
    id("com.android.application")
    id("com.bugsee.android.gradle")
}

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
    // `-PbugseeOwnProvider=true`: the app's own manifest declares a known extension provider (see src/ownProvider).
    if (project.findProperty("bugseeOwnProvider")?.toString() == "true") {
        sourceSets.getByName("main").manifest.srcFile("src/ownProvider/AndroidManifest.xml")
    }
    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
        getByName("release") { isMinifyEnabled = false }
    }
}

// Where the Bugsee SDK sits, chosen by -PbugseeLayout (the test sets it); the SDK is always an EXTERNAL
// artifact from the fake repo. `-PbugseeExtensions=ndk,feedback,...` picks which extension modules exist.
val layout = project.findProperty("bugseeLayout")?.toString() ?: "direct"
val exts = (project.findProperty("bugseeExtensions")?.toString() ?: "ndk")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
fun sdk(): List<String> = listOf("com.bugsee:bugsee-android:7.5.0") + exts.map { "com.bugsee:bugsee-android-$it:7.5.0" }

dependencies {
    when (layout) {
        "direct" -> sdk().forEach { implementation(it) }
        "library", "library-api" -> implementation(project(":libA"))
        "chain" -> implementation(project(":libA"))
        "split" -> { implementation(project(":libA")); implementation(project(":libB")) }
        "autoadd" -> implementation(project(":libA"))
        "core-only" -> implementation(project(":libA"))
        "none" -> {}
        "wrapper", "wrapper-autoload" -> implementation("com.acme:wrapper:1.0")   // SDK + NDK only transitively, via a non-Bugsee module
        else -> error("unknown layout $layout")
    }
}

bugsee {
    if (layout == "autoadd") ndk { enabled.set(true) }
    if (layout == "none" || layout == "wrapper") sdkAutoLoad.set(false)
    // `-PbugseeOptimize=false` turns the extension consolidation off.
    project.findProperty("bugseeOptimize")?.toString()?.let { optimizeExtensionsLoading.set(it == "true") }
}
