// Root build script. `@KOTLIN@` and the `@COMPOSE_PLUGIN@` line are substituted by the test:
// Kotlin 1.9 has no Compose Gradle plugin (AGP's composeOptions supplies the compiler), Kotlin 2.x does.
plugins {
    id("com.android.application") version "8.6.0" apply false
    id("com.android.library") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "@KOTLIN@" apply false
    // @COMPOSE_PLUGIN@
    id("com.bugsee.android.gradle") apply false
}
