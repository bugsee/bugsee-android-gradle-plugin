// Root build script for the isolated-projects fixture: two modules (:app depends on :lib).
// The Bugsee plugin is deliberately NOT declared here. Each module requests it in its own
// `plugins {}` block, and :lib also applies another plugin, so the two modules load the plugin
// in DIFFERENT classloaders (the shape that broke a build-service-typed shared state).
plugins {
    id("com.android.application") version "8.6.0" apply false
    id("com.android.library") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
