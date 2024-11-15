# Comment the block
sed -i '' '/^signing {/,/^}/ s/^/\/\//g' maven-push.gradle   

./gradlew clean
./gradlew build
./gradlew publishToMavenLocal

# Uncomment the block
sed -i '' '/^\/\/signing {/,/^\/\/}/ s/^\/\///' maven-push.gradle 