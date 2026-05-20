#!/bin/bash

set -e

# Stop any running Gradle daemon before starting. Kotlin 2.1's incremental
# compiler daemon occasionally caches stale internal-visibility state across
# main/test boundaries, producing "Unresolved reference" errors on `internal`
# symbols that resolve fine on a cold daemon. Cost: ~2-3s; reward: deterministic
# publishes regardless of prior daemon state.
./gradlew --stop 2>/dev/null || true

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
