#!/bin/bash

set -e

# 1. Compose Kotlin compiler plugin (separate Gradle build under
#    compose-compiler-plugin/). MUST be published BEFORE the main plugin
#    is consumed because the main plugin's `getPluginArtifact()`
#    references this jar by Maven coordinates at the same version, and
#    consumer SDK builds (e.g. :app:compileDebugKotlin) will fail
#    resolution if mavenLocal only has the main plugin.
#
#    The compose-compiler-plugin's version is read from `../version.txt`
#    so it stays in sync with the main plugin automatically when the
#    parent version is bumped.
(cd compose-compiler-plugin && ../gradlew clean build publishToMavenLocal -x sign)

# 2. Main gradle plugin.
./gradlew clean build
./gradlew publishToMavenLocal -x signPluginMavenPublication
