// Root build script for the kmp-library fixture: a Kotlin Multiplatform module using the AGP KMP
// library plugin (`com.android.kotlin.multiplatform.library`, AGP 8.10+, the supported KMP-Android
// target from AGP 9), with the Bugsee plugin applied. Used by `BugseeKmpLibraryTest`.
plugins {
    id("com.android.kotlin.multiplatform.library") version "8.6.0" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.1.0" apply false
    id("com.bugsee.android.gradle") apply false
}
