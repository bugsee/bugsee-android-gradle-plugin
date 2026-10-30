// A plain Kotlin/JVM module with the Bugsee plugin and NO Android Gradle Plugin anywhere on the build's
// plugin classpath (#161). Used by `BugseeNoAgpTest`. The Kotlin plugin version is fixed on purpose
// (`toolchainOverrides = false`: the matrix's AGP and Kotlin-android overrides do not apply to this fixture).
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("com.bugsee.android.gradle")
}

repositories { mavenCentral() }
