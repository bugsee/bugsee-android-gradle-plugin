#!/usr/bin/env bash
#
# Publish the Bugsee Gradle plugin and Compose compiler plugin to Maven Central.
#
# Both artifacts are published to a single Sonatype staging repository via
# the io.github.gradle-nexus.publish-plugin. After upload, the staging repo
# is closed but NOT released — review and release manually from
# https://central.sonatype.com (Deployments tab).
#
# Artifacts published:
#   com.bugsee:bugsee-android-gradle-plugin:<version>
#   com.bugsee.android.gradle:com.bugsee.android.gradle.gradle.plugin:<version>  (marker)
#   com.bugsee:bugsee-compose-compiler-plugin:<version>
#
# Required gradle properties (typically in ~/.gradle/gradle.properties):
#   NEXUS_USERNAME, NEXUS_PASSWORD
#   signing.keyId, signing.password, signing.secretKeyRingFile

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

export RELEASE="${RELEASE:-true}"

VERSION="$(head -n 1 version.txt | tr -d '[:space:]')"
echo "Publishing Bugsee Gradle Plugin version: $VERSION (RELEASE=$RELEASE)"

./gradlew clean

# publishToSonatype aggregates all subproject publications (main plugin +
# compose-compiler-plugin) into a single staging repository.
# closeSonatypeStagingRepository closes it for validation.
# Release is done manually from the Central dashboard.
./gradlew \
    publishToSonatype \
    closeSonatypeStagingRepository

echo "Staging repository closed. Review and release at https://central.sonatype.com"
