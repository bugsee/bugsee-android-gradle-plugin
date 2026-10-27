// Root build script. Plugins are declared `apply false` here and applied in :app.
plugins {
    id("com.android.application") version "8.6.0" apply false
    // Not applied in :app; on the classpath because the Bugsee plugin references Kotlin Gradle Plugin types.
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.bugsee.android.gradle") apply false
}
