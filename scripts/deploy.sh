export PATH=$PATH:/usr/local/bin
export ANDROID_HOME=$ANDROID_SDK_ROOT
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_25.jdk/Contents/Home/
export PATH=$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools:$PATH

./gradlew publish