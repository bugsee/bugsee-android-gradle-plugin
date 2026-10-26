// AGP 7.4.2 is deliberately BELOW the plugin's minimum (8.6.0): the test asserts the
// readable "requires AGP 8.6.0 or newer (found 7.4.2)" failure, not a linkage error.
// The Kotlin plugin is on the classpath because the Bugsee plugin references its API.
plugins {
    id("com.android.application") version "7.4.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.bugsee.android.gradle") apply false
}
