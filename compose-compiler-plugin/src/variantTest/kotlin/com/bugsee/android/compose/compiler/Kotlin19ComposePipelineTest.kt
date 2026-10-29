package com.bugsee.android.compose.compiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The REAL Compose compiler plugin on Kotlin 1.9 (issues #119 and #138) and on Kotlin 2.1, 2.2 and
 * 2.4 (issue #146: an omitted `modifier` must keep the callee's own default; the 2.x rows run the
 * Compose 1.5 runtime, which is enough for `Composer`).
 *
 * [ComposeVariantMatrixTest] cannot see this line's behaviour: its fixture uses stub Compose
 * artifacts, so no Compose lowering ever runs and every call site reaches the transform in its
 * source form. On Kotlin 1.9 the real androidx Compose compiler (1.5.x) runs, and three facts only
 * show up with it:
 *
 * 1. **The transform must not abort the build (#119).** The default-value check read
 *    `IrStatementOrigin.DEFAULT_VALUE`, which on a 1.9 compiler is not reachable through the
 *    `IrStatementOrigin.Companion` field the k21 jar was compiled against, so the first call site
 *    Compose had lowered threw `NoSuchFieldError` and aborted the consumer's compilation.
 *    The check is now by origin name.
 *
 * 2. **Order decides whether an OMITTED `modifier` is tagged (#138).** Compose compiler 1.5.15
 *    registers through the legacy `ComponentRegistrar` service, which the 1.9 compiler runs AHEAD of
 *    every `CompilerPluginRegistrar`. The k21 jar therefore also registers through
 *    `ComponentRegistrar` ([BugseeComposeLegacyComponentRegistrar]), and the two legacy registrars
 *    run in plugin classpath order:
 *    - **Bugsee first** (what the Gradle plugin produces, see `ComposePluginOrderTest`): Bugsee sees
 *      the call sites as written, finds `modifier` absent and chains `Modifier.bugseeTag(..)` in.
 *      Compose's own lowering then treats the slot as supplied. Both `ProdDefault` sites and the
 *      explicit one are tagged.
 *    - **Compose first**: Compose has already rewritten each omitted `modifier` into a
 *      `DEFAULT_VALUE` composite when Bugsee runs. The transform skips those on purpose (the callee's
 *      `$default` fixup would overwrite the slot, and chaining onto its null crashed host apps in
 *      4.0.5), so only the explicit site is tagged. This is the old behaviour, still pinned.
 *
 * 3. **Neither order may emit code that fails verification or crashes at runtime.** The compiled
 *    classes are run under `-Xverify:all` against the real Compose runtime, with a recording
 *    `Composer`, so the 4.0.5 failure mode (`null.bugseeTag(..)` raising "Parameter specified as
 *    non-null is null") and a `$default` fixup that clobbers the injected modifier are both caught
 *    by executing the very call paths, not by inspecting bytecode.
 */
class Kotlin19ComposePipelineTest {

    @Test
    fun `Kotlin 1_9_25 with Bugsee first tags omitted and explicit modifier sites and runs clean`() {
        assertBugseeFirst("1.9.25")
    }

    @Test
    fun `Kotlin 1_9_22 with Bugsee first tags omitted and explicit modifier sites and runs clean`() {
        assertBugseeFirst("1.9.22")
    }

    @Test
    fun `Kotlin 1_9_25 with Compose first still skips omitted modifier sites and runs clean`() {
        assertComposeFirst("1.9.25")
    }

    @Test
    fun `Kotlin 1_9_22 with Compose first still skips omitted modifier sites and runs clean`() {
        assertComposeFirst("1.9.22")
    }

    // ---- Kotlin 2.x with the REAL Compose compiler (issue #146) ----
    //
    // Same fixture. Bugsee's rewrite of an OMITTED `modifier` makes the slot a supplied argument, so
    // the callee's own default (`ChildCustom`'s marker) is lost. The guard from #138 now applies on
    // the modern IR path too: only a statically visible bare-`Modifier` default is rewritten.

    @Test
    fun `Kotlin 2_1_0 with Bugsee first keeps custom callee defaults and still tags bare defaults`() {
        assertBugseeFirst("2.1.0", "k21")
    }

    @Test
    fun `Kotlin 2_2_0 with Bugsee first keeps custom callee defaults and still tags bare defaults`() {
        assertBugseeFirst("2.2.0", "k22")
    }

    @Test
    fun `Kotlin 2_4_0 with Bugsee first keeps custom callee defaults and still tags bare defaults`() {
        assertBugseeFirst("2.4.0", "k24")
    }

    @Test
    fun `Kotlin 2_1_0 with Compose first skips omitted modifier sites and runs clean`() {
        assertComposeFirst("2.1.0", "k21")
    }

    @Test
    fun `Kotlin 2_2_0 with Compose first skips omitted modifier sites and runs clean`() {
        assertComposeFirst("2.2.0", "k22")
    }

    @Test
    fun `Kotlin 2_4_0 with Compose first skips omitted modifier sites and runs clean`() {
        assertComposeFirst("2.4.0", "k24")
    }

    private fun assertBugseeFirst(kotlin: String, variant: String = "k21") {
        val result = compileAndRun(kotlin, bugseeFirst = true, variant = variant)
        assertEquals(
            "Kotlin $kotlin (bugsee-first): every site carries exactly one bugseeTag in the bytecode. " +
                "A count of 4 for ProdDefault would mean the extension registered twice.",
            mapOf("ProdDefault" to 2, "ProdExplicit" to 1, "ProdCustomDefault" to 0, "ProdCross" to 0, "ProdCrossExplicit" to 1, "ProdHelper" to 0) + SECURE_TAG_CALLS,
            result.tagCalls,
        )
        assertEquals(
            "Kotlin $kotlin (bugsee-first): the modifier the callee RECEIVES at run time is the tagged " +
                "one for every site, i.e. Compose's `\$default` fixup did not overwrite it and nothing was " +
                "chained onto a null.",
            mapOf(
                "ProdDefault" to listOf("tag:ProdDefault", "tag:ProdDefault"),
                "ProdExplicit" to listOf("tag:ProdExplicit"),
                "ProdCustomDefault" to listOf("tag:marker"),
                "ProdCross" to listOf("plain"),
                "ProdCrossExplicit" to listOf("tag:ProdCrossExplicit"),
                "ProdHelper" to listOf("plain", "plain", "plain"),
            ) + SECURE_RECEIVED + ("ProdSecureOmitted" to listOf("secure")),
            result.received,
        )
        assertEquals(
            "Kotlin $kotlin (bugsee-first): bugseeSecure is injected exactly once into every allowlisted " +
                "LIBRARY text field with a PasswordVisualTransformation (issues #149, #151), including the one " +
                "whose modifier is omitted, and never into the non-androidx look-alike.",
            SECURE_CALLS + ("ProdSecureOmitted" to 1),
            result.secureCalls,
        )
    }

    private fun assertComposeFirst(kotlin: String, variant: String = "k21") {
        val result = compileAndRun(kotlin, bugseeFirst = false, variant = variant)
        assertEquals(
            "Kotlin $kotlin (compose-first): the omitted-modifier sites (ProdDefault) are DEFAULT_VALUE " +
                "composites by the time Bugsee runs and are skipped; only the explicit site is tagged.",
            mapOf("ProdDefault" to 0, "ProdExplicit" to 1, "ProdCustomDefault" to 0, "ProdCross" to 0, "ProdCrossExplicit" to 1, "ProdHelper" to 0) + SECURE_TAG_CALLS,
            result.tagCalls,
        )
        assertEquals(
            "Kotlin $kotlin (compose-first): skipped sites reach the callee untagged, and nothing crashed.",
            mapOf(
                "ProdDefault" to listOf("plain", "plain"),
                "ProdExplicit" to listOf("tag:ProdExplicit"),
                "ProdCustomDefault" to listOf("tag:marker"),
                "ProdCross" to listOf("plain"),
                "ProdCrossExplicit" to listOf("tag:ProdCrossExplicit"),
                "ProdHelper" to listOf("plain", "plain", "plain"),
            ) + SECURE_RECEIVED + ("ProdSecureOmitted" to listOf("plain")),
            result.received,
        )
        assertEquals(
            "Kotlin $kotlin (compose-first): explicit-modifier library text fields are still secured; the " +
                "omitted-modifier one is a DEFAULT_VALUE composite by then and is skipped on purpose.",
            SECURE_CALLS + ("ProdSecureOmitted" to 0),
            result.secureCalls,
        )
    }

    private class Result(
        /** `bugseeTag` call count per top-level composable, from the emitted bytecode. */
        val tagCalls: Map<String, Int>,
        /** What the callee observed for its `modifier`, per composable, from EXECUTING the classes. */
        val received: Map<String, List<String>>,
        /** `bugseeSecure` call count per library text-field composable, from the emitted bytecode. */
        val secureCalls: Map<String, Int>,
    )

    private fun compileAndRun(kotlin: String, bugseeFirst: Boolean, variant: String = "k21"): Result {
        val work = Files.createTempDirectory("bugsee-k19-compose-").toFile()
        try {
            return compileAndRunIn(work, kotlin, bugseeFirst, variant)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun compileAndRunIn(work: File, kotlin: String, bugseeFirst: Boolean, variant: String): Result {
        val bugseeJar = requireProperty("bugsee.variantJar.$variant")
        val modern = !kotlin.startsWith("1.")
        val composeCompilerJar = requireProperty(
            if (modern) "bugsee.compose2.compilerPlugin.$kotlin" else "bugsee.compose19.compilerPlugin"
        )
        val composeRuntimeJar = requireProperty("bugsee.compose19.runtime")
        val compilerClasspath = requireProperty("bugsee.kotlinClasspath.$kotlin")
        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val stdlib = compilerClasspath.split(File.pathSeparator)
            // The version is whatever dependency resolution settled on (the coroutines artifact pulls a
            // newer stdlib than the compiler's), so match the artifact, not a version.
            .firstOrNull { Regex("""kotlin-stdlib-\d.*\.jar""").matches(File(it).name) }
            ?: error("no kotlin-stdlib on the matrix classpath: $compilerClasspath")

        // Compiled as its own module: a `bugseeTag` declared in the module being compiled is a
        // source declaration, not the library function a consumer resolves, and codegen rejects
        // calling it ("Unhandled intrinsic"). The tag is wrapped in a modifier of its own so the
        // callee can report what it received.
        val libSrc = File(work, "lib-src").apply { mkdirs() }
        File(libSrc, "Modifier.kt").writeText(
            """
            package androidx.compose.ui
            interface Modifier { companion object : Modifier }
            """.trimIndent()
        )
        File(libSrc, "BugseeRuntime.kt").writeText(
            """
            package com.bugsee.library.compose
            import androidx.compose.ui.Modifier
            class TagModifier(val tag: String, val inner: Modifier) : Modifier
            fun Modifier.bugseeTag(tag: String): Modifier = TagModifier(tag, this)
            class SecureModifier(val inner: Modifier) : Modifier
            fun Modifier.bugseeSecure(): Modifier = SecureModifier(this)
            """.trimIndent()
        )
        File(libSrc, "VisualTransformation.kt").writeText(
            """
            package androidx.compose.ui.text.input
            interface VisualTransformation
            class PasswordVisualTransformation : VisualTransformation
            """.trimIndent()
        )
        // What each callee received, shared by the app and the library composable below.
        File(libSrc, "Sink.kt").writeText(
            """
            package com.bugsee.library.compose
            import androidx.compose.ui.Modifier
            val received = mutableListOf<String>()
            fun describe(modifier: Modifier): String =
                (modifier as? TagModifier)?.let { "tag:" + it.tag }
                    ?: (if (modifier is SecureModifier) "secure" else "plain")
            """.trimIndent()
        )
        val libOut = File(work, "lib-out").apply { mkdirs() }
        compile(kotlin, java, compilerClasspath, listOf(), libSrc, libOut, extraClasspath = emptyList())

        // A composable from ANOTHER module: compiled by the Compose compiler alone (no Bugsee), so the
        // app sees an already-lowered library whose default for `modifier` it cannot inspect.
        val versionCheckArgs = if (modern) emptyList() else listOf(
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:suppressKotlinVersionCompatibilityCheck=true",
        )
        val composeArgs = listOf("-Xplugin=$composeCompilerJar") + versionCheckArgs
        val lib2Src = File(work, "lib2-src").apply { mkdirs() }
        File(lib2Src, "LibChild.kt").writeText(
            """
            package com.example.lib2
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import com.bugsee.library.compose.describe
            import com.bugsee.library.compose.received

            @Composable fun LibChild(modifier: Modifier = Modifier) { received += describe(modifier) }

            // A plain, non-composable helper from another module that merely takes a Modifier.
            fun libHelper(modifier: Modifier = Modifier) { received += describe(modifier) }
            """.trimIndent()
        )
        // Allowlisted text fields from ANOTHER module (issues #149, #151). Same fully-qualified names as
        // the real androidx ones; compiled separately so the app sees them as lazy library
        // declarations, whose callee fqName on Kotlin 1.9 carries the file-facade class (`TextFieldKt`).
        val fields = listOf(
            "androidx.compose.material" to "TextField",
            "androidx.compose.material" to "OutlinedTextField",
            "androidx.compose.material3" to "TextField",
            "androidx.compose.material3" to "OutlinedTextField",
            "androidx.compose.foundation.text" to "BasicTextField",
            // NOT androidx: a same-named function in an `...Kt` facade must never be matched.
            "com.example.lib2" to "TextField",
        )
        for ((pkg, name) in fields) {
            val dir = File(lib2Src, pkg.replace('.', '/')).apply { mkdirs() }
            File(dir, "$name.kt").writeText(
                """
                package $pkg
                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Modifier
                import androidx.compose.ui.text.input.VisualTransformation
                import com.bugsee.library.compose.describe
                import com.bugsee.library.compose.received

                @Composable fun $name(
                    value: String,
                    modifier: Modifier = Modifier,
                    visualTransformation: VisualTransformation? = null
                ) { received += describe(modifier) }
                """.trimIndent()
            )
        }
        val lib2Out = File(work, "lib2-out").apply { mkdirs() }
        compile(
            kotlin, java, compilerClasspath, composeArgs, lib2Src, lib2Out,
            extraClasspath = listOf(composeRuntimeJar, libOut.absolutePath),
        )

        val appSrc = File(work, "app-src").apply { mkdirs() }
        File(appSrc, "Prod.kt").writeText(
            """
            package com.example.app
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import com.bugsee.library.compose.TagModifier
            import com.bugsee.library.compose.describe
            import com.bugsee.library.compose.received
            import com.example.lib2.LibChild
            import com.example.lib2.libHelper
            import androidx.compose.ui.text.input.PasswordVisualTransformation

            // A default that is NOT the bare Modifier: it must survive an omitted-modifier call.
            val Marker: Modifier = TagModifier("marker", Modifier)

            @Composable fun Child(modifier: Modifier = Modifier, enabled: Boolean = true) {
                received += describe(modifier)
            }
            @Composable fun ChildCustom(modifier: Modifier = Marker) { received += describe(modifier) }

            @Composable fun ProdDefault() { Child(); Child(enabled = false) }
            @Composable fun ProdExplicit() { Child(Modifier) }
            @Composable fun ProdCustomDefault() { ChildCustom() }
            @Composable fun ProdCross() { LibChild() }
            // A cross-module composable with an EXPLICIT modifier is tagged: the callee's @Composable
            // annotation is read from the already-lowered library (issue #147).
            @Composable fun ProdCrossExplicit() { LibChild(Modifier) }

            // Plain helpers that merely accept a Modifier must be left alone, in-module and
            // cross-module (issue #147).
            fun plainHelper(modifier: Modifier) { received += describe(modifier) }
            @Composable fun ProdHelper() { plainHelper(Modifier); libHelper(Modifier); libHelper() }

            // Library text fields with a statically visible PasswordVisualTransformation (issues #149, #151).
            @Composable fun ProdSecureMaterial() {
                androidx.compose.material.TextField("a", Modifier, PasswordVisualTransformation())
            }
            @Composable fun ProdSecureMaterialOutlined() {
                androidx.compose.material.OutlinedTextField("a", Modifier, PasswordVisualTransformation())
            }
            @Composable fun ProdSecureMaterial3() {
                androidx.compose.material3.TextField("a", Modifier, PasswordVisualTransformation())
            }
            @Composable fun ProdSecureMaterial3Outlined() {
                androidx.compose.material3.OutlinedTextField("a", Modifier, PasswordVisualTransformation())
            }
            @Composable fun ProdSecureBasic() {
                androidx.compose.foundation.text.BasicTextField("a", Modifier, PasswordVisualTransformation())
            }
            // `modifier` omitted: the allowlisted default is the bare Modifier (exemption from #153).
            @Composable fun ProdSecureOmitted() {
                androidx.compose.material.TextField("a", visualTransformation = PasswordVisualTransformation())
            }
            // Not an androidx field: a facade-stripped match on the name alone would wrongly secure it.
            @Composable fun ProdSecureFake() {
                com.example.lib2.TextField("a", Modifier, PasswordVisualTransformation())
            }
            """.trimIndent()
        )
        // Runs the composables without a composition: a recording `Composer` stand-in whose
        // group/skipping queries answer "not skipping", so every body executes. Calls go through
        // reflection because a non-composable function may not call a composable one in source.
        File(appSrc, "Main.kt").writeText(
            """
            package com.example.app
            import androidx.compose.runtime.Composer
            import java.lang.reflect.Proxy

            fun main() {
                val composer = Proxy.newProxyInstance(
                    Composer::class.java.classLoader, arrayOf(Composer::class.java)
                ) { proxy, method, _ ->
                    val type = method.returnType
                    when {
                        Composer::class.java.isAssignableFrom(type) -> proxy
                        type == java.lang.Boolean.TYPE -> false
                        type == Integer.TYPE -> 0
                        type == java.lang.Long.TYPE -> 0L
                        else -> null
                    }
                } as Composer
                val prod = Class.forName("com.example.app.ProdKt")
                @Suppress("UNCHECKED_CAST")
                val received = Class.forName("com.bugsee.library.compose.SinkKt")
                    .getMethod("getReceived").invoke(null) as MutableList<String>
                for (name in listOf("ProdDefault", "ProdExplicit", "ProdCustomDefault", "ProdCross", "ProdCrossExplicit", "ProdHelper", ${SECURE_NAMES.joinToString(", ") { "\"" + it + "\"" }})) {
                    received.clear()
                    prod.getMethod(name, Composer::class.java, Integer.TYPE).invoke(null, composer, 0)
                    println("RECEIVED " + name + "=" + received.joinToString(","))
                }
            }
            """.trimIndent()
        )
        val appOut = File(work, "app-out").apply { mkdirs() }
        val plugins = if (bugseeFirst) listOf(bugseeJar, composeCompilerJar) else listOf(composeCompilerJar, bugseeJar)
        compile(
            kotlin, java, compilerClasspath,
            plugins.map { "-Xplugin=$it" } + versionCheckArgs + listOf(
                "-P", "plugin:${BugseeComposeCommandLineProcessor.PLUGIN_ID}:${BugseeComposeCommandLineProcessor.OPTION_ENABLED}=true",
            ),
            appSrc, appOut,
            extraClasspath = listOf(composeRuntimeJar, libOut.absolutePath, lib2Out.absolutePath),
        )

        return Result(
            tagCalls = countCalls(File(appOut, "com/example/app/ProdKt.class"), ".bugseeTag:", TAG_NAMES + SECURE_NAMES),
            secureCalls = countCalls(File(appOut, "com/example/app/ProdKt.class"), ".bugseeSecure:", SECURE_NAMES),
            received = execute(
                kotlin, java, listOf(appOut.absolutePath, libOut.absolutePath, lib2Out.absolutePath, composeRuntimeJar, stdlib),
            ),
        )
    }

    /** Count of `invokestatic ...[callSuffix]` per named top-level composable of the fixture, from the emitted bytecode. */
    private fun countCalls(classFile: File, callSuffix: String, names: List<String>): Map<String, Int> {
        val javap = File(File(System.getProperty("java.home"), "bin"), "javap").absolutePath
        val javapProcess = ProcessBuilder(javap, "-c", "-p", classFile.absolutePath)
            .redirectErrorStream(true).start()
        val dump = javapProcess.inputStream.bufferedReader().readText()
        assertEquals("javap failed:\n$dump", 0, javapProcess.waitFor())
        val counts = linkedMapOf<String, Int>().apply { names.forEach { put(it, 0) } }
        val seen = mutableSetOf<String>()
        var current: String? = null
        for (line in dump.lines()) {
            val declared = METHOD_HEADER.find(line)
            if (declared != null) {
                current = declared.groupValues[1]
                seen += current
            }
            if (current in counts && line.contains("invokestatic") && line.contains(callSuffix)) {
                counts[current!!] = counts.getValue(current) + 1
            }
        }
        assertTrue(
            "javap output no longer exposes the fixture's method headers (saw $seen); the header " +
                "regex or javap format changed:\n${dump.take(2000)}",
            seen.containsAll(counts.keys),
        )
        return counts
    }

    /**
     * Runs the compiled fixture's `main` with the JVM verifier forced on for EVERY class and returns
     * what each composable's callee received. A `VerifyError`, a `NullPointerException` from a
     * non-null parameter check, or any other failure is a non-zero exit and fails the test.
     */
    private fun execute(kotlin: String, java: String, classpath: List<String>): Map<String, List<String>> {
        val process = ProcessBuilder(
            java, "-Xverify:all", "-cp", classpath.joinToString(File.pathSeparator), "com.example.app.MainKt",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        assertEquals(
            "Kotlin $kotlin: the compiled fixture failed verification or crashed at run time (the 4.0.5 " +
                "failure mode is \"Parameter specified as non-null is null: ... bugseeTag ... <this>\"):\n$output",
            0, exit,
        )
        val received = linkedMapOf<String, List<String>>()
        for (line in output.lines()) {
            val match = RECEIVED.matchEntire(line.trim()) ?: continue
            received[match.groupValues[1]] =
                match.groupValues[2].split(',').filter { it.isNotEmpty() }
        }
        assertEquals("fixture main did not report every composable:\n$output", (TAG_NAMES + SECURE_NAMES).toSet(), received.keys)
        return received
    }

    private fun compile(
        kotlin: String,
        java: String,
        compilerClasspath: String,
        pluginArgs: List<String>,
        source: File,
        out: File,
        extraClasspath: List<String>,
    ) {
        val classpath = (listOf(compilerClasspath) + extraClasspath).joinToString(File.pathSeparator)
        val process = ProcessBuilder(
            listOf(
                java, "-cp", compilerClasspath,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib", "-nowarn",
                "-classpath", classpath,
            ) + pluginArgs + listOf("-d", out.absolutePath, source.absolutePath)
        ).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        assertTrue(
            "Kotlin $kotlin compilation failed (exit $exit). A NoSuchFieldError / " +
                "IrStatementOrigin here is the #119 abort:\n${log.take(4000)}",
            exit == 0,
        )
    }

    private fun requireProperty(key: String): String =
        System.getProperty(key) ?: error(
            "System property '$key' is not set. This test is driven by the composeVariantMatrix " +
                "Gradle task, which resolves the variant jars, the Compose 1.5 plugin and runtime, " +
                "and the per-line compiler classpaths."
        )

    private companion object {
        val TAG_NAMES = listOf("ProdDefault", "ProdExplicit", "ProdCustomDefault", "ProdCross", "ProdCrossExplicit", "ProdHelper")

        /** Library text-field call sites; `ProdSecureFake` calls a non-androidx look-alike that must not be secured. */
        val SECURE_NAMES = listOf(
            "ProdSecureMaterial", "ProdSecureMaterialOutlined", "ProdSecureMaterial3", "ProdSecureMaterial3Outlined",
            "ProdSecureBasic", "ProdSecureOmitted", "ProdSecureFake",
        )
        val SECURE_CALLS: Map<String, Int> = SECURE_NAMES.associateWith { 1 } + ("ProdSecureFake" to 0) - "ProdSecureOmitted"

        /** Tag injection skips androidx callees; only the look-alike (a user-style callee) is tagged. */
        val SECURE_TAG_CALLS: Map<String, Int> = SECURE_NAMES.associateWith { 0 } + ("ProdSecureFake" to 1)
        val SECURE_RECEIVED: Map<String, List<String>> = (SECURE_NAMES - "ProdSecureOmitted" - "ProdSecureFake")
            .associateWith { listOf("secure") } + ("ProdSecureFake" to listOf("tag:ProdSecureFake"))

        /** A javap method header, e.g. `  public static final void ProdDefault(...);`. */
        val METHOD_HEADER = Regex("""^\s{2}\S[^(]*?\b([\w$]+)\(.*;$""")

        /** One line of the fixture's report: `RECEIVED ProdDefault=tag:ProdDefault,tag:ProdDefault`. */
        val RECEIVED = Regex("""RECEIVED (\w+)=(.*)""")
    }
}
