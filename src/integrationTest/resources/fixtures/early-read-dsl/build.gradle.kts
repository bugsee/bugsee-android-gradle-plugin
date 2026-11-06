// Root build script — required by AGP/Kotlin plugin DSL even though it
// contains no real configuration. Plugins are declared `apply false` here
// and applied in the :app submodule.
plugins {
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.bugsee.android.gradle") apply false
}
