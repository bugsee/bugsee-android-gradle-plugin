package com.bugsee.android.gradle.integration.harness

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A local Maven repository with fake, published-shape Bugsee AARs, so a test can put the SDK where a real
 * app has it (an EXTERNAL artifact, not a class compiled from the app's own sources) without the network.
 *
 * Shape copied from the real 7.5.0 artifacts (inspected, not guessed):
 *  - `bugsee-android` ships one `<provider>` (`com.bugsee.library.BugseeInitProvider`) and that class, with
 *    the empty `initializeExtensions()` hook the plugin rewrites;
 *  - every `bugsee-android-<x>` extension ships exactly one provider and its facade class, and depends on
 *    the core SDK at runtime scope.
 *
 * The facade classes are deliberately EMPTY: a `register<X>Extension` string can then only reach the dex through
 * the call the plugin injects into `initializeExtensions()`, which makes "injected" observable by a string
 * search of the dex, with no false positive from a facade that defines the method itself.
 */
internal object FakeBugseeRepo {

    const val VERSION = "7.5.0"

    /** The manifest-merger placeholder real AARs use in provider authorities (a single dollar sign). */
    private const val PLACEHOLDER = "\${applicationId}"

    /** A fake extension: its artifact, provider class, and the name used in `register<Name>Extension`. */
    data class Extension(val artifactId: String, val providerFqn: String, val name: String)

    val NDK = Extension("bugsee-android-ndk", "com.bugsee.library.BugseeNdkInitProvider", "Ndk")
    val FEEDBACK = Extension("bugsee-android-feedback", "com.bugsee.library.BugseeFeedbackInitProvider", "Feedback")
    val OKHTTP = Extension("bugsee-android-okhttp", "com.bugsee.library.BugseeOkHttpInitProvider", "OkHttp")
    val COMPOSE = Extension("bugsee-android-compose", "com.bugsee.library.compose.BugseeComposeInitProvider", "Compose")
    /** No `bugsee-android-remoting` is published (7.5.0); the plugin knows the provider and the naming convention. */
    val REMOTING = Extension("bugsee-android-remoting", "com.bugsee.library.BugseeRemotingInitProvider", "Remoting")
    val ALL = listOf(NDK, FEEDBACK, OKHTTP, COMPOSE, REMOTING)

    const val CORE_PROVIDER = "com.bugsee.library.BugseeInitProvider"

    /** The dex string only an injected call can introduce. */
    fun registerCall(e: Extension) = "register${e.name}Extension"

    /**
     * @param version the version every artifact is published under; extensions depend on the core at the same version.
     * @param coreHook `false` publishes a core whose `BugseeInitProvider` has NO `initializeExtensions()` (an SDK older than
     *   7.0.0-beta11, the release that introduced the hook).
     * @param withCore `false` publishes only the extensions, for a build whose core SDK comes from somewhere else
     *   (the stub SDK repo, at the same [version]).
     */
    fun create(dir: File, version: String = VERSION, withCore: Boolean = true, coreHook: Boolean = true): File {
        dir.mkdirs()
        if (withCore) {
            publishAar(
                dir, version, "bugsee-android", "com.bugsee.fake.core",
                providers = listOf(CORE_PROVIDER),
                classes = mapOf(CORE_PROVIDER.replace('.', '/') to coreInitProvider(coreHook)),
                deps = emptyList(),
            )
        }
        for (e in ALL) {
            publishAar(
                dir, version, e.artifactId, "com.bugsee.fake.${e.name.lowercase()}",
                providers = listOf(e.providerFqn),
                classes = mapOf(
                    e.providerFqn.replace('.', '/') to emptyClass(e.providerFqn.replace('.', '/')),
                    "com/bugsee/library/Bugsee${e.name}" to emptyClass("com/bugsee/library/Bugsee${e.name}"),
                ),
                deps = listOf("bugsee-android"),
            )
        }
        // A third-party wrapper SDK: not a Bugsee module, so nothing the app DECLARES names Bugsee, yet it brings the
        // core SDK and the NDK extension onto the runtime classpath transitively (POM-only: no artifact of its own).
        if (withCore) {
            val wrapper = File(dir, "com/acme/wrapper/1.0").also { it.mkdirs() }
            File(wrapper, "wrapper-1.0.pom").writeText(
                """<project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId><artifactId>wrapper</artifactId>
                   <version>1.0</version><packaging>pom</packaging><dependencies>
                   <dependency><groupId>com.bugsee</groupId><artifactId>bugsee-android</artifactId><version>$version</version></dependency>
                   <dependency><groupId>com.bugsee</groupId><artifactId>bugsee-android-ndk</artifactId><version>$version</version></dependency>
                   </dependencies></project>""",
            )
        }
        // The plugin's auto-add resolves `[MIN_SDK_VERSION,)`, a range: Gradle needs the repository's version list.
        for (artifact in (if (withCore) listOf("bugsee-android") else emptyList()) + ALL.map { it.artifactId }) {
            File(dir, "com/bugsee/$artifact/maven-metadata.xml").writeText(
                """<metadata><groupId>com.bugsee</groupId><artifactId>$artifact</artifactId>
                   <versioning><latest>$version</latest><release>$version</release>
                   <versions><version>$version</version></versions></versioning></metadata>""",
            )
        }
        return dir
    }

    private fun publishAar(
        repo: File,
        version: String,
        artifactId: String,
        pkg: String,
        providers: List<String>,
        classes: Map<String, ByteArray>,
        deps: List<String>,
    ) {
        val dir = File(repo, "com/bugsee/$artifactId/$version").also { it.mkdirs() }
        val providerXml = providers.joinToString("\n") {
            "<provider android:name=\"$it\" android:authorities=\"${PLACEHOLDER}.${it.substringAfterLast('.').lowercase()}\" android:exported=\"false\" />"
        }
        val manifest = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg">
    <application>
$providerXml
    </application>
</manifest>
"""
        val aar = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.put("AndroidManifest.xml", manifest.toByteArray())
                z.put("classes.jar", jar(classes))
                z.put("R.txt", ByteArray(0))
            }
        }.toByteArray()
        File(dir, "$artifactId-$version.aar").writeBytes(aar)
        val depXml = deps.joinToString("\n") {
            "<dependency><groupId>com.bugsee</groupId><artifactId>$it</artifactId><version>$version</version><scope>runtime</scope></dependency>"
        }
        File(dir, "$artifactId-$version.pom").writeText(
            """<project><modelVersion>4.0.0</modelVersion><groupId>com.bugsee</groupId><artifactId>$artifactId</artifactId>
               <version>$version</version><packaging>aar</packaging><dependencies>$depXml</dependencies></project>""",
        )
    }

    private fun ZipOutputStream.put(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name)); write(bytes); closeEntry()
    }

    private fun jar(classes: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> classes.forEach { (n, b) -> z.put("$n.class", b) } }
    }.toByteArray()

    private fun emptyClass(internalName: String): ByteArray = ClassWriter(0).run {
        visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN); visitMaxs(1, 1); visitEnd()
        }
        visitEnd(); toByteArray()
    }

    /** The core provider: the empty `initializeExtensions()V` the plugin rewrites. */
    private fun coreInitProvider(hook: Boolean): ByteArray = ClassWriter(0).run {
        val name = CORE_PROVIDER.replace('.', '/')
        visit(Opcodes.V11, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN); visitMaxs(1, 1); visitEnd()
        }
        if (hook) {
            visitMethod(0, "initializeExtensions", "()V", null, null).apply {
                visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 1); visitEnd()
            }
        }
        visitEnd(); toByteArray()
    }
}
