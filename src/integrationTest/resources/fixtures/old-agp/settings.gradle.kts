pluginManagement {
    // Same sourcing as the other fixtures: the plugin under test shares AGP's classloader.
    val bugseePluginDir = settings.providers
        .gradleProperty("bugseePluginProjectDir").orNull
    if (bugseePluginDir != null) {
        includeBuild(bugseePluginDir)
    }

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "old-agp-fixture"
include(":app")
