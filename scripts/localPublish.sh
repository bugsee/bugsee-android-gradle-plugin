#!/bin/bash

./gradlew clean
./gradlew build
./gradlew publishToMavenLocal -x signPluginMavenPublication
