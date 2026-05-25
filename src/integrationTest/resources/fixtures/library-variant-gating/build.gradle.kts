// Root build script for the library-variant-gating fixture. The
// fixture itself is a library module that applies the bugsee plugin
// — used by `BugseeLibraryVariantGatingTest` to verify the plugin
// correctly skips its side effects on `LibraryVariant` rather than
// stripping providers without compensating bytecode.
plugins {
    id("com.android.library") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.bugsee.android.gradle") apply false
}
