plugins { id("com.android.library") }

android {
    namespace = "com.example.libb"
    compileSdk = 36
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val layout = project.findProperty("bugseeLayout")?.toString() ?: "direct"
val sdkVersion = project.findProperty("bugseeSdkVersion")?.toString() ?: "7.5.0"
val exts = (project.findProperty("bugseeExtensions")?.toString() ?: "ndk")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

dependencies {
    when (layout) {
        "chain" -> {
            implementation("com.bugsee:bugsee-android:$sdkVersion")
            exts.forEach { implementation("com.bugsee:bugsee-android-$it:$sdkVersion") }
        }
        "split" -> exts.forEach { implementation("com.bugsee:bugsee-android-$it:$sdkVersion") }
        else -> {}
    }
}
