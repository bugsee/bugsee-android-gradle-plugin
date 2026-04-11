plugins {
    `java-gradle-plugin`
    `maven-publish`
    signing
    kotlin("jvm") version "2.1.0"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

group = property("GROUP") as String

val versionBase = file("version.txt").readText().trim()
version = if (System.getenv("RELEASE")?.toBoolean() == true) versionBase else "$versionBase-SNAPSHOT"

println("Build version $version")
println("Release build: ${System.getenv("RELEASE")?.toBoolean() == true}")

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(11)
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    compileOnly("com.android.tools.build:gradle-api:8.6.0")
    compileOnly("com.android.tools.build:gradle:8.6.0")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.1.0")
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    implementation("org.apache.httpcomponents:httpmime:4.5.14")
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

// Generate version resource so the plugin can read its own version at runtime.
// Must include the -SNAPSHOT suffix when applicable.
tasks.named<Copy>("processResources") {
    val fullVersion = version.toString()
    from("version.txt") {
        rename { "bugsee-plugin-version.txt" }
        filter { fullVersion }
    }
}

gradlePlugin {
    plugins {
        create("bugsee") {
            id = "com.bugsee.android.gradle"
            implementationClass = "com.bugsee.android.gradle.BugseePlugin"
            displayName = property("POM_NAME") as String
        }
    }
}

// Publishing configuration

fun getRepositoryUsername(): String =
    if (hasProperty("NEXUS_USERNAME")) property("NEXUS_USERNAME") as String else ""

fun getRepositoryPassword(): String =
    if (hasProperty("NEXUS_PASSWORD")) property("NEXUS_PASSWORD") as String else ""

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") {
            artifactId = property("POM_ARTIFACT_ID") as String
        }
    }
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username = getRepositoryUsername()
            password = getRepositoryPassword()
        }
    }
}

signing {
    // Only sign when signing keys are available (CI/release builds)
    isRequired = false
    sign(publishing.publications)
}

tasks.withType<Sign>().configureEach {
    onlyIf {
        // Only run signing tasks for release builds when signing keys are present
        !version.toString().contains("SNAPSHOT") && project.hasProperty("signing.keyId")
    }
}
