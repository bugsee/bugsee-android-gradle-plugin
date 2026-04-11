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

// Empty sources/javadoc JARs — Maven Central requires them but this is proprietary.
val emptySourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
}
val emptyJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
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
// The nexus publish plugin is applied at the root project. This subproject
// only needs to define its publication — publishToSonatype from the root
// will aggregate it into the same staging repository as the main plugin.

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "bugsee-compose-compiler-plugin"
            artifact(emptySourcesJar)
            artifact(emptyJavadocJar)

            pom {
                name.set("Bugsee Compose Compiler Plugin")
                description.set("Kotlin compiler plugin for Bugsee Compose tag injection and secure modifier auto-insertion.")
                url.set("https://www.bugsee.com")

                licenses {
                    license {
                        name.set("Proprietary")
                        url.set("https://bugsee.com/tos/")
                    }
                }
                developers {
                    developer {
                        id.set("bugsee")
                        name.set("Bugsee, Inc")
                        email.set("support@bugsee.com")
                    }
                }
                scm {
                    url.set("https://bugsee.com/")
                }
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
        !version.toString().contains("SNAPSHOT") && project.hasProperty("signing.keyId")
    }
}
