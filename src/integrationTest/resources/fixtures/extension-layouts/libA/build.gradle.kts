plugins { id("com.android.library") }

android {
    namespace = "com.example.liba"
    compileSdk = 36
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val layout = project.findProperty("bugseeLayout")?.toString() ?: "direct"
val exts = (project.findProperty("bugseeExtensions")?.toString() ?: "ndk")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
val sdk = listOf("com.bugsee:bugsee-android:7.5.0") + exts.map { "com.bugsee:bugsee-android-$it:7.5.0" }

dependencies {
    when (layout) {
        "library" -> sdk.forEach { implementation(it) }
        "library-api" -> sdk.forEach { api(it) }
        "chain" -> implementation(project(":libB"))
        "split" -> implementation("com.bugsee:bugsee-android:7.5.0")      // core here, the extension in :libB
        "core-only" -> implementation("com.bugsee:bugsee-android:7.5.0")
        else -> {}                                                          // autoadd: nothing declared anywhere
    }
}
