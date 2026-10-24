package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.FixtureProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end coverage of per-variant settings: `variant.<name>.*` keys in
 * `<rootProject>/bugsee.properties`, `bugsee { variant("x") { } }` DSL blocks,
 * `preferSettingsFile`, and their configuration-cache behaviour.
 *
 * Fixture: `app-startup-tracing` with `-PbugseeFixtureMultiFlavor=true`, which gives
 * four application variants — `freeDebug`, `freeRelease`, `paidDebug`, `paidRelease`.
 * Each test writes its own `bugsee.properties` into the materialized root and appends
 * Bugsee DSL to the materialized `app/build.gradle.kts`; the static fixture is untouched.
 *
 * Observable: with an effective `debug=true`, `onVariants` logs once per variant
 * `Bugsee: variant <name> resolved: token=<masked|none> endpoint=<url>` (tokens masked as
 * first 4 + `...` + last 4). All tokens below are > 8 chars with distinct prefixes and
 * suffixes, so the masked form still identifies which token won.
 *
 * Most tests only need the variants configured, so they run `:app:help` (configuration
 * runs `onVariants` for every variant regardless of the requested task).
 */
class PerVariantSettingsIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun perFlavorTokenAndEndpointReachEachVariant() {
        val fixture = materialize("per-flavor")
        fixture.writeSettingsFile(
            """
            app_token=$ROOT_TOKEN
            plugin.debug=true
            plugin.endpoint=https://root.example
            variant.free.app_token=$FREE_TOKEN
            variant.free.plugin.endpoint=https://free.example
            variant.debug.app_token=$DEBUG_TOKEN
            variant.debug.plugin.endpoint=https://debug.example
            variant.freeDebug.plugin.endpoint=https://freedebug.example
            """,
        )

        val output = fixture.configure()

        assertEquals(
            mapOf(
                // variant name beats flavor (endpoint); flavor beats build type (token)
                "freeDebug" to listOf("token=free...0001 endpoint=https://freedebug.example"),
                // flavor only
                "freeRelease" to listOf("token=free...0001 endpoint=https://free.example"),
                // build type only
                "paidDebug" to listOf("token=dbug...0002 endpoint=https://debug.example"),
                // no variant entry matches: root values
                "paidRelease" to listOf("token=root...0000 endpoint=https://root.example"),
            ),
            resolvedLines(output),
        )
    }

    @Test
    fun dslVariantBlockAndFilePrecedence() {
        val fixture = materialize("dsl-precedence")
        fixture.writeSettingsFile(
            """
            app_token=$ROOT_TOKEN
            plugin.debug=true
            plugin.endpoint=https://file-root.example
            variant.free.app_token=$FREE_TOKEN
            variant.free.plugin.endpoint=https://file-free.example
            variant.paid.app_token=$FILE_PAID_TOKEN
            variant.paid.plugin.endpoint=https://file-paid.example
            """,
        )
        // Real Kotlin-DSL form of the variant block (sam-with-receiver `Action`).
        fixture.appendAppScript(
            """
            bugsee {
                if (project.findProperty("ptPreferFile")?.toString() == "true") {
                    preferSettingsFile.set(true)
                }
                appToken("$DSL_GLOBAL_TOKEN")
                endpoint.set("https://dsl-global.example")
                variant("free") {
                    appToken("$DSL_FREE_TOKEN")
                    endpoint.set("https://dsl-free.example")
                }
            }
            """,
        )

        // Default mode: the DSL (variant block, then global) beats every file value.
        val dslWins = fixture.configure()
        assertEquals(
            mapOf(
                "freeDebug" to listOf("token=dslf...0003 endpoint=https://dsl-free.example"),
                "freeRelease" to listOf("token=dslf...0003 endpoint=https://dsl-free.example"),
                // DSL GLOBAL beats the file's variant.paid.* entries
                "paidDebug" to listOf("token=dslg...0004 endpoint=https://dsl-global.example"),
                "paidRelease" to listOf("token=dslg...0004 endpoint=https://dsl-global.example"),
            ),
            resolvedLines(dslWins),
        )
        // The shadowed variant keys are reported, once each.
        for (key in listOf(
            "variant.free.app_token",
            "variant.free.plugin.endpoint",
            "variant.paid.app_token",
            "variant.paid.plugin.endpoint",
        )) {
            assertEquals(
                "expected one shadow warning for $key",
                1,
                dslWins.lines().count { it.startsWith("Bugsee: $key ignored:") },
            )
        }

        // preferSettingsFile: the file wins for the whole build.
        val fileWins = fixture.configure("-PptPreferFile=true")
        assertEquals(
            mapOf(
                "freeDebug" to listOf("token=free...0001 endpoint=https://file-free.example"),
                "freeRelease" to listOf("token=free...0001 endpoint=https://file-free.example"),
                "paidDebug" to listOf("token=paid...0005 endpoint=https://file-paid.example"),
                "paidRelease" to listOf("token=paid...0005 endpoint=https://file-paid.example"),
            ),
            resolvedLines(fileWins),
        )
        assertNoShadowOrIgnoringWarnings(fileWins)
    }

    @Test
    fun globalOnlySettingInsideVariantBlockFailsToCompile() {
        // Inside variant { } the @BugseeDsl marker hides the outer `bugsee` receiver,
        // so a global-only setting is a script compilation error ...
        val rejected = materialize("dsl-marker-rejected")
        rejected.appendAppScript(
            """
            bugsee {
                variant("free") {
                    feedback.set(true)
                }
            }
            """,
        )
        val (ok, output) = rejected.fixture.runTasksAllowingFailure(listOf(":app:help"), MULTI_FLAVOR)
        assertFalse("variant { feedback.set(true) } must not compile", ok)
        assertTrue(
            "expected a script compilation error about the implicit receiver for `feedback`; got:\n" +
                output.takeLast(3000),
            output.contains("Script compilation error") &&
                output.contains("implicit receiver") &&
                output.contains("feedback"),
        )

        // ... while the same setting at the top level of bugsee { } compiles and configures.
        val accepted = materialize("dsl-marker-accepted")
        accepted.appendAppScript(
            """
            bugsee {
                feedback.set(true)
                variant("free") {
                    endpoint.set("https://free.example")
                }
            }
            """,
        )
        val (acceptedOk, acceptedOutput) =
            accepted.fixture.runTasksAllowingFailure(listOf(":app:help"), MULTI_FLAVOR)
        assertTrue(
            "top-level feedback.set(true) must compile; got:\n" + acceptedOutput.takeLast(3000),
            acceptedOk,
        )
    }

    @Test
    fun configCacheReusedThenInvalidatedByFileEdit() {
        val fixture = materialize("config-cache")
        val settings = { token: String, endpoint: String ->
            """
            app_token=$ROOT_TOKEN
            plugin.debug=true
            plugin.endpoint=https://root.example
            variant.free.app_token=$token
            variant.free.plugin.endpoint=$endpoint
            """
        }
        fixture.writeSettingsFile(settings(FREE_TOKEN, "https://free.example"))
        // assembleFreeDebug realizes the variant's upload tasks (finalizedBy), so their
        // per-variant inputs are serialized. No mapping exists for a non-minified debug
        // build, so the mapping upload task returns before any network access.
        val tasks = listOf(":app:assembleFreeDebug")
        val ccArgs = arrayOf(MULTI_FLAVOR, "--configuration-cache", "--configuration-cache-problems=fail")

        val first = fixture.fixture.buildTasks(tasks, null, *ccArgs).output
        assertEquals(
            listOf("token=free...0001 endpoint=https://free.example"),
            resolvedLines(first)["freeDebug"],
        )
        assertTrue("first run must store a CC entry", first.contains("Configuration cache entry stored."))
        assertNoConfigurationCacheProblems(first)

        val second = fixture.fixture.buildTasks(tasks, null, *ccArgs).output
        assertTrue(
            "second run must reuse the configuration cache; head:\n" + second.lines().take(40).joinToString("\n"),
            second.contains("Reusing configuration cache."),
        )
        assertEquals("configuration must not run on reuse", emptyMap<String, List<String>>(), resolvedLines(second))

        fixture.writeSettingsFile(settings(NEW_FREE_TOKEN, "https://free-new.example"))
        val third = fixture.fixture.buildTasks(tasks, null, *ccArgs).output
        assertTrue(
            "editing bugsee.properties must invalidate the CC entry; head:\n" +
                third.lines().take(40).joinToString("\n"),
            third.lines().any {
                it.contains("configuration cache cannot be reused because") && it.contains("bugsee.properties")
            },
        )
        assertEquals(
            listOf("token=free...0009 endpoint=https://free-new.example"),
            resolvedLines(third)["freeDebug"],
        )
        assertTrue("third run must store a new CC entry", third.contains("Configuration cache entry stored."))
        assertNoConfigurationCacheProblems(third)
    }

    @Test
    fun noVariantInput_behavesAsBefore() {
        val fixture = materialize("no-variant-input")
        fixture.writeSettingsFile(
            """
            app_token=$ROOT_TOKEN
            plugin.debug=true
            plugin.endpoint=https://root.example
            """,
        )

        val output = fixture.configure()

        val expected = listOf("token=root...0000 endpoint=https://root.example")
        assertEquals(
            mapOf(
                "freeDebug" to expected,
                "freeRelease" to expected,
                "paidDebug" to expected,
                "paidRelease" to expected,
            ),
            resolvedLines(output),
        )
        assertNoShadowOrIgnoringWarnings(output)
        assertFalse(
            "no variant { } block exists, so no variant-block token may be reported",
            output.contains("Bugsee: Using variant("),
        )
    }

    /**
     * Backward-compat pin: a DSL value set in a user `afterEvaluate` (which runs AFTER
     * AGP's `onVariants`) still reaches the task inputs, because each variant's effective
     * property is linked to the global one rather than copied.
     *
     * Observable: the resolved debug line cannot show the late value (it is printed inside
     * `onVariants`, before the late block runs — asserted below as a guard that the scenario
     * really is "late"). Instead a probe `afterEvaluate`, registered after the late one, reads
     * `uploadBugseeDebugMapping.endpoint` and prints it; that is the value the task executes with.
     */
    @Test
    fun lateAfterEvaluateDslValueStillReachesTasks() {
        val fixture = FixtureProject.materialize("app-startup-tracing", temp.newFolder("late-after-evaluate"))
        File(fixture.projectDir, "bugsee.properties").writeText("plugin.debug=true\n")
        File(fixture.projectDir, "app/build.gradle.kts").appendText(
            """

            bugsee {
                endpoint.set("https://early.example")
            }
            afterEvaluate {
                bugsee {
                    endpoint.set("https://late.example")
                }
            }
            afterEvaluate {
                val endpoint = tasks.getByName("uploadBugseeDebugMapping").property("endpoint")
                    as org.gradle.api.provider.Provider<*>
                println("BUGSEE_PROBE mapping.endpoint=" + endpoint.get())
            }
            """.trimIndent() + "\n",
        )

        val output = fixture.buildTasks(listOf(":app:help")).output

        assertEquals(
            "guard: onVariants must have run before the late afterEvaluate",
            mapOf("debug" to listOf("token=none endpoint=https://early.example")),
            resolvedLines(output).filterKeys { it == "debug" },
        )
        assertEquals(
            listOf("BUGSEE_PROBE mapping.endpoint=https://late.example"),
            output.lines().filter { it.startsWith("BUGSEE_PROBE ") },
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────

    /** A materialized multi-flavor fixture plus its settings-file / script helpers. */
    private class Project(val fixture: FixtureProject) {
        fun writeSettingsFile(text: String) {
            File(fixture.projectDir, "bugsee.properties").writeText(text.trimIndent() + "\n")
        }

        fun appendAppScript(text: String) {
            File(fixture.projectDir, "app/build.gradle.kts").appendText("\n" + text.trimIndent() + "\n")
        }

        /** Configures every variant (`:app:help`) and returns the build output. */
        fun configure(vararg extraArgs: String): String =
            fixture.buildTasks(listOf(":app:help"), null, MULTI_FLAVOR, *extraArgs).output
    }

    private fun materialize(dirName: String): Project =
        Project(FixtureProject.materialize("app-startup-tracing", temp.newFolder(dirName)))

    /** Variant name → every `token=… endpoint=…` summary logged for it, in output order. */
    private fun resolvedLines(output: String): Map<String, List<String>> =
        output.lines()
            .mapNotNull { RESOLVED_LINE.matchEntire(it.trim()) }
            .groupBy({ it.groupValues[1] }, { it.groupValues[2] })

    private fun assertNoShadowOrIgnoringWarnings(output: String) {
        val offending = output.lines().filter { line ->
            line.startsWith("Bugsee:") && (line.contains(" ignored:") || line.contains(" ignored —") ||
                line.contains("ignoring"))
        }
        assertEquals("unexpected shadow / ignored-key warnings", emptyList<String>(), offending)
    }

    private fun assertNoConfigurationCacheProblems(output: String) {
        assertFalse(
            "configuration cache problems reported:\n" + output.takeLast(3000),
            Regex("""\d+ problems? (was|were) found""").containsMatchIn(output) ||
                output.contains("Configuration cache problems found"),
        )
    }

    private companion object {
        const val MULTI_FLAVOR = "-PbugseeFixtureMultiFlavor=true"

        val RESOLVED_LINE = Regex("""Bugsee: variant (\S+) resolved: (token=\S+ endpoint=\S+)""")

        // Masked as first 4 + "..." + last 4 (all longer than 8 chars).
        const val ROOT_TOKEN = "root-token-0000" // root...0000
        const val FREE_TOKEN = "free-token-0001" // free...0001
        const val DEBUG_TOKEN = "dbug-token-0002" // dbug...0002
        const val DSL_FREE_TOKEN = "dslf-token-0003" // dslf...0003
        const val DSL_GLOBAL_TOKEN = "dslg-token-0004" // dslg...0004
        const val FILE_PAID_TOKEN = "paid-token-0005" // paid...0005
        const val NEW_FREE_TOKEN = "free-token-0009" // free...0009
    }
}
