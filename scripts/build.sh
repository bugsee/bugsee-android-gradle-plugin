export PATH=$PATH:/usr/local/bin
export ANDROID_HOME=$ANDROID_SDK_ROOT
export PATH=$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools:$PATH

set -e

# Single Gradle invocation builds both the main plugin and the
# compose-compiler-plugin subproject.
./gradlew clean build :compose-compiler-plugin:build
