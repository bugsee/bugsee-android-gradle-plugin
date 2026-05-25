pluginManagement {
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

rootProject.name = "library-variant-gating-fixture"
include(":lib")
