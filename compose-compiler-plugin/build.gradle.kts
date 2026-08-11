plugins {
    kotlin("jvm") version "2.1.0"
    `maven-publish`
    signing
}

group = "com.bugsee"

// The Kotlin line the default (`k21`) variant is built against — also the version of the Kotlin
// plugin applied above, since that compilation IS the k21 artifact.
val K21_KOTLIN = "2.1.0"

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

// The default `main` compilation builds the k21 variant: legacy IR API + the registrar without an
// `override` on pluginId, which is what Kotlin 2.1 accepts. The other two variants are built by
// `composeVariants` below, from the same shared sources with a different IR source set.
sourceSets {
    main {
        java.srcDirs("src/legacyIr/kotlin", "src/registrarPlain/kotlin")
    }
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:$K21_KOTLIN")

    // The Kotlin compiler API is normally `compileOnly` because the
    // host compiler provides it at plugin load time. Tests need it
    // available at runtime to construct CompilerConfiguration and
    // ExtensionStorage instances, so wire it in via testImplementation.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$K21_KOTLIN")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

// ---------------------------------------------------------------------------
// Per-Kotlin-line variants
// ---------------------------------------------------------------------------
// A compiler plugin binds the EXACT descriptors of the compiler API it was compiled against, so one
// artifact cannot serve several Kotlin lines. Verified by running each line's real compiler over a
// Compose consumer (see ComposeVariantMatrixTest):
//
//   k21  built vs 2.1.0  covers Kotlin 1.9 – 2.1   (legacy IR API)
//   k22  built vs 2.2.0  covers Kotlin 2.2 – 2.3   (2.2 widened the irCall/irString receiver)
//   k24  built vs 2.4.0  covers Kotlin 2.4          (2.4 moved extension registration and
//                                                   removed valueParameters/putValueArgument)
//
// Each is built by invoking that line's own compiler, because Kotlin metadata is not readable by an
// older compiler — the 2.1 compiler bundled with this module cannot read 2.4-compiled classes.
data class ComposeVariant(
    val id: String,
    val kotlinVersion: String,
    val irSourceSet: String,
    val registrarSourceSet: String,
    val supports: String,
)

val composeVariants = listOf(
    ComposeVariant("k22", "2.2.0", "modernIr", "registrarPlain", "Kotlin 2.2 and 2.3"),
    ComposeVariant("k24", "2.4.0", "modernIr", "registrarOverride", "Kotlin 2.4"),
)

val variantJars = composeVariants.associate { variant ->
    val compilerClasspath = configurations.create("kotlinCompiler${variant.id.replaceFirstChar { it.uppercase() }}") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    dependencies {
        // Everything K2JVMCompiler needs to RUN, plus the API we compile AGAINST — the same jar
        // serves both roles.
        compilerClasspath.name("org.jetbrains.kotlin:kotlin-compiler-embeddable:${variant.kotlinVersion}")
        compilerClasspath.name("org.jetbrains.kotlin:kotlin-reflect:${variant.kotlinVersion}")
        compilerClasspath.name("org.jetbrains.kotlin:kotlin-script-runtime:${variant.kotlinVersion}")
        compilerClasspath.name("org.jetbrains.kotlin:kotlin-stdlib:${variant.kotlinVersion}")
        // CoreApplicationEnvironment reaches for kotlinx.coroutines, which the compiler's own POM
        // does not pull in.
        compilerClasspath.name("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0")
    }

    val outputDir = layout.buildDirectory.dir("composeVariants/${variant.id}/classes")
    val sourceDirs = listOf(
        "src/main/kotlin",
        "src/${variant.irSourceSet}/kotlin",
        "src/${variant.registrarSourceSet}/kotlin",
    ).map { file(it) }

    // A FileCollection, not the Configuration itself: the argument provider below closes over this,
    // and the configuration cache cannot serialize a Configuration ("cannot serialize object of
    // type ... Configuration" at store time).
    val compilerFiles = objects.fileCollection().from(compilerClasspath)

    val compileTask = tasks.register<JavaExec>("compile${variant.id.replaceFirstChar { it.uppercase() }}") {
        group = "build"
        description = "Compiles the Compose compiler plugin against Kotlin ${variant.kotlinVersion} (${variant.supports})."
        classpath = compilerFiles
        mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
        inputs.files(sourceDirs).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("kotlinVersion", variant.kotlinVersion)
        outputs.dir(outputDir)
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "-no-stdlib",
                "-jvm-target", "11",
                "-classpath", compilerFiles.asPath,
                "-d", outputDir.get().asFile.absolutePath,
            ) + sourceDirs.map { it.absolutePath }
        })
        doFirst { outputDir.get().asFile.deleteRecursively() }
    }

    val jarTask = tasks.register<Jar>("jar${variant.id.replaceFirstChar { it.uppercase() }}") {
        group = "build"
        description = "Packages the ${variant.id} Compose compiler plugin (${variant.supports})."
        archiveBaseName.set("compose-compiler-plugin")
        archiveAppendix.set(variant.id)
        from(compileTask.map { outputDir })
        // The CompilerPluginRegistrar service file is what makes the jar loadable via -Xplugin.
        from("src/main/resources")
    }

    tasks.named("assemble") { dependsOn(jarTask) }
    variant.id to jarTask
}

// ---------------------------------------------------------------------------
// Cross-version verification
// ---------------------------------------------------------------------------
// Runs every variant through the real compiler of every Kotlin line we make a claim about. Kept in
// its own source set and task because it shells out to N compilers and is far slower than the unit
// tests — but it is the only check that can catch a variant that loads and then silently does
// nothing, which is how the 4.0.3 bound came to be wrong.
// 2.0.21 and 2.3.0 are here because the mapping CLAIMS them: 2.0 routes to the base artifact and
// 2.3.0-exact is the boundary where the k22 registrar's plain `val pluginId` must satisfy the
// abstract member 2.3 introduced. A claimed line with no row here is an unverified claim.
val matrixKotlinVersions =
    listOf("1.9.22", "2.0.21", "2.1.0", "2.2.0", "2.2.21", "2.3.0", "2.3.21", "2.4.0", "2.4.10")

val matrixClasspaths = matrixKotlinVersions.associateWith { kotlinVersion ->
    val cfg = configurations.create("matrixCompiler${kotlinVersion.replace(".", "_")}") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    dependencies {
        cfg.name("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")
        cfg.name("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
        cfg.name("org.jetbrains.kotlin:kotlin-script-runtime:$kotlinVersion")
        cfg.name("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
        cfg.name("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0")
    }
    cfg
}

val variantTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

configurations["variantTestImplementation"].extendsFrom(configurations["testImplementation"])

val composeVariantMatrix by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs every Compose compiler-plugin variant against every supported Kotlin line."
    testClassesDirs = variantTest.output.classesDirs
    classpath = variantTest.runtimeClasspath
    useJUnit()

    // The k21 variant IS the module's own jar; the others come from the variant tasks.
    //
    // Their CONTENTS are declared inputs, not just their paths. Passing the paths as system
    // properties alone leaves this task UP-TO-DATE after a variant source change: the jar rebuilds,
    // the path string is unchanged, and the one suite that can detect a silently no-op'ing compiler
    // plugin quietly stops running. Verified by mutation — disabling tag injection in modernIr kept
    // this task green until these inputs existed. That is the exact failure class this suite exists
    // to catch, so the wiring matters as much as the assertions.
    val variantJarFiles: Map<String, Provider<RegularFile>> =
        mapOf("k21" to tasks.named<Jar>("jar").flatMap { it.archiveFile }) +
            variantJars.mapValues { (_, jarTask) -> jarTask.flatMap { it.archiveFile } }
    variantJarFiles.forEach { (id, jar) ->
        inputs.file(jar).withPropertyName("variantJar.$id").withPathSensitivity(PathSensitivity.NONE)
    }

    // The compiler jars come from immutable Maven coordinates, so the version list pins them; there
    // is no need to hash ~400 MB of compiler distributions on every up-to-date check.
    inputs.property("matrixKotlinVersions", matrixKotlinVersions)
    val matrixClasspathFiles: Map<String, FileCollection> =
        matrixClasspaths.mapValues { (_, cfg) -> objects.fileCollection().from(cfg) }

    // Resolved lazily: building the argument list at configuration time would force all seven
    // compiler distributions to download merely to CONFIGURE the task (e.g. on `gradle tasks`).
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        variantJarFiles.map { (id, jar) -> "-Dbugsee.variantJar.$id=${jar.get().asFile.absolutePath}" } +
            matrixClasspathFiles.map { (v, files) -> "-Dbugsee.kotlinClasspath.$v=${files.asPath}" }
    })

    // Each case forks a compiler; the default 10-minute Gradle test timeout is ample but the
    // downloads on a cold cache are not, so make the failure legible.
    testLogging { showStandardStreams = false }
}

tasks.named("check") { dependsOn(composeVariantMatrix) }

// --- Publishing ---
// The nexus publish plugin is applied at the root project. This subproject
// only needs to define its publication — publishToSonatype from the root
// will aggregate it into the same staging repository as the main plugin.

// Empty sources/javadoc JARs — Maven Central requires them but this is proprietary.
// Each publication gets its own pair: shared jars make every Sign task claim the same
// `.asc` outputs, which Gradle 8.7 rejects as an undeclared dependency between
// e.g. publishK22* and signK24Publication.
fun emptyJars(name: String): Pair<TaskProvider<Jar>, TaskProvider<Jar>> {
    val cap = name.replaceFirstChar { it.uppercase() }
    val sources = tasks.register<Jar>("emptySourcesJar$cap") {
        archiveClassifier.set("sources")
        archiveAppendix.set(name)
    }
    val javadoc = tasks.register<Jar>("emptyJavadocJar$cap") {
        archiveClassifier.set("javadoc")
        archiveAppendix.set(name)
    }
    return sources to javadoc
}

val (emptySourcesJar, emptyJavadocJar) = emptyJars("k21")
val variantEmptyJars = composeVariants.associate { variant ->
    variant.id to emptyJars(variant.id)
}

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

        // One publication per Kotlin line. The unsuffixed artifact above stays the k21 build so
        // already-published coordinates keep meaning what they meant; newer lines get their own
        // artifactId, which the Gradle plugin selects from the consumer's Kotlin version.
        composeVariants.forEach { variant ->
            val (sourcesJar, javadocJar) = variantEmptyJars.getValue(variant.id)
            create<MavenPublication>(variant.id) {
                artifactId = "bugsee-compose-compiler-plugin-${variant.id}"
                artifact(variantJars.getValue(variant.id))
                artifact(sourcesJar)
                artifact(javadocJar)

                pom {
                    name.set("Bugsee Compose Compiler Plugin (${variant.id})")
                    description.set(
                        "Kotlin compiler plugin for Bugsee Compose tag injection and secure " +
                            "modifier auto-insertion, built for ${variant.supports}."
                    )
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
}

signing {
    isRequired = false
    sign(publishing.publications)
}

// See root build.gradle.kts for rationale — `enabled` beats `onlyIf`
// here because Gradle still evaluates the Sign task's built-in
// "Signing is required, or signatory is set" spec under `onlyIf`
// alone, and that spec's lazy `getSignatory()` call throws on some
// no-credentials Gradle 8.7+ shapes rather than returning null.
tasks.withType<Sign>().configureEach {
    enabled = !version.toString().contains("SNAPSHOT") && project.hasProperty("signing.keyId")
}

// Same defense as the root project: custom artifacts + signing can leave
// PublishToMaven* without an explicit edge to every Sign task Gradle later
// validates against.
tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(tasks.withType<Sign>())
}
tasks.withType<PublishToMavenLocal>().configureEach {
    dependsOn(tasks.withType<Sign>())
}
