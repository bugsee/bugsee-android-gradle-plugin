export PATH=$PATH:/usr/local/bin
export ANDROID_HOME=$ANDROID_SDK_ROOT
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_25.jdk/Contents/Home/
export PATH=$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools:$PATH

set -e

# 1. Compose Kotlin compiler plugin (separate Gradle build under
#    compose-compiler-plugin/). MUST be published before the main
#    gradle plugin so that the main plugin's `getPluginArtifact()` can
#    resolve the matching version when consumers apply the plugin.
(cd compose-compiler-plugin && ../gradlew publish)

# 2. Main gradle plugin.
./gradlew publish
