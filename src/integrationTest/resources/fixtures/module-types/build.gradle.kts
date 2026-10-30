// Root build script for the module-types fixture (issue #159): modules whose Android plugin exposes no
// `android` extension (fused library, lint) or that are not Android modules at all (a JVM module), each
// applying the Bugsee plugin. Used by `BugseeModuleTypesTest`. Declaring the AGP plugins here keeps AGP on
// the shared build classpath, the only way a non-Android module gets as far as the module-type check;
// `:lib` is an ordinary Android library that does not apply Bugsee.
plugins {
    id("com.android.library") version "8.6.0" apply false
    id("com.android.fused-library") version "8.6.0" apply false
    id("com.android.lint") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
    id("com.bugsee.android.gradle") apply false
}
