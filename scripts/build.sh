export PATH=$PATH:/usr/local/bin
export ANDROID_HOME=$ANDROID_SDK_ROOT
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_25.jdk/Contents/Home/
export PATH=$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools:$PATH

set -e

# 1. Compose Kotlin compiler plugin (separate Gradle build under
#    compose-compiler-plugin/). The main gradle-plugin's
#    `KotlinCompilerPluginSupportPlugin.getPluginArtifact()` references
#    this jar by Maven coordinates at consumer-build time, so it must be
#    built (and later published) at the same version as the main plugin.
#    Its version.txt symlink-reads `../version.txt`, so a single bump
#    keeps both subprojects in sync.
(cd compose-compiler-plugin && ../gradlew clean build)

# 2. Main gradle plugin.
./gradlew clean build
