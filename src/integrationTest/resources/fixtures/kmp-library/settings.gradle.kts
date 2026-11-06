pluginManagement {
    val bugseePluginDir = settings.providers
        .gradleProperty("bugseePluginProjectDir").orNull
    // Matrix legs on a newer Gradle consume the plugin as a PRE-BUILT jar from a local Maven repo
    // (`bugseePluginRepo` + `bugseePluginVersion`, written into gradle.properties by FixtureProject):
    // an included build is compiled by the Gradle under test, and the plugin's own Kotlin 2.1 compiler
    // cannot read the newest Gradle APIs. A jar is also what real consumers get.
    val bugseePluginRepo = settings.providers.gradleProperty("bugseePluginRepo").orNull
    val bugseePluginVersion = settings.providers.gradleProperty("bugseePluginVersion").orNull
    if (bugseePluginRepo != null) {
        requireNotNull(bugseePluginVersion) { "bugseePluginRepo needs bugseePluginVersion" }
        resolutionStrategy.eachPlugin {
            if (requested.id.id == "com.bugsee.android.gradle") useVersion(bugseePluginVersion)
        }
    } else if (bugseePluginDir != null) {
        includeBuild(bugseePluginDir)
    }

    repositories {
        if (bugseePluginRepo != null) {
            maven { url = uri(bugseePluginRepo) }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        val stubRepo = settings.providers
            .gradleProperty("bugseeStubSdkRepo").orNull
        if (stubRepo != null) {
            maven {
                url = uri(stubRepo)
                metadataSources {
                    mavenPom()
                    artifact()
                }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "kmp-library-fixture"
include(":kmp")
