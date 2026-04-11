plugins {
    kotlin("jvm") version "2.1.0"
    `maven-publish`
    signing
}

group = "com.bugsee"

val versionBase = file("../version.txt").readText().trim()
version = if (System.getenv("RELEASE")?.toBoolean() == true) versionBase else "$versionBase-SNAPSHOT"

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
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.1.0")
}

// --- Publishing ---

fun isReleaseBuild(): Boolean = !version.toString().contains("SNAPSHOT")

fun getReleaseRepositoryUrl(): String =
    if (project.hasProperty("RELEASE_REPOSITORY_URL")) project.property("RELEASE_REPOSITORY_URL") as String
    else "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"

fun getSnapshotRepositoryUrl(): String =
    if (project.hasProperty("SNAPSHOT_REPOSITORY_URL")) project.property("SNAPSHOT_REPOSITORY_URL") as String
    else "https://central.sonatype.com/repository/maven-snapshots/"

fun getRepositoryUsername(): String =
    if (project.hasProperty("NEXUS_USERNAME")) project.property("NEXUS_USERNAME") as String else ""

fun getRepositoryPassword(): String =
    if (project.hasProperty("NEXUS_PASSWORD")) project.property("NEXUS_PASSWORD") as String else ""

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "bugsee-compose-compiler-plugin"
        }
    }
    repositories {
        maven {
            url = uri(if (isReleaseBuild()) getReleaseRepositoryUrl() else getSnapshotRepositoryUrl())
            credentials {
                username = getRepositoryUsername()
                password = getRepositoryPassword()
            }
        }
    }
}

signing {
    isRequired = false
    sign(publishing.publications)
}

tasks.withType<Sign>().configureEach {
    onlyIf {
        isReleaseBuild() && project.hasProperty("signing.keyId")
    }
}
