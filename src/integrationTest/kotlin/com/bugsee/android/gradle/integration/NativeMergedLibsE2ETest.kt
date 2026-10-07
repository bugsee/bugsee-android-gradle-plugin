package com.bugsee.android.gradle.integration

import com.bugsee.android.gradle.integration.harness.ElfInfo
import com.bugsee.android.gradle.integration.harness.FixtureProject
import com.bugsee.android.gradle.integration.harness.MockSymbolServer
import org.gradle.testkit.runner.BuildResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end coverage for native symbol upload from the unstripped
 * `merged_native_libs` (issue #10), driven through a real AGP + NDK build of a
 * one-library app, the real `bugsee-cli`, and an in-process mock symbols server.
 *
 * Needs a bugsee-cli >= 0.8.0 binary in `BUGSEE_CLI_BIN`; skipped otherwise (the
 * same convention as `CliUploaderRealBinaryTest`).
 */
class NativeMergedLibsE2ETest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var cli: String

    @Before
    fun requireCli() {
        val bin = System.getenv("BUGSEE_CLI_BIN")?.let(::File)
        assumeTrue("BUGSEE_CLI_BIN must point at a bugsee-cli >= 0.8.0 binary", bin?.canExecute() == true)
        cli = bin!!.absolutePath
    }

    private fun fixture() = FixtureProject.materialize("native-merged-libs", temp.newFolder("fx"))

    private fun assemble(
        fx: FixtureProject,
        server: MockSymbolServer,
        cliPath: String = cli,
        vararg extra: String,
    ): BuildResult = fx.buildTasks(
        listOf(":app:assembleRelease"),
        null,
        "-PbugseeE2eEndpoint=${server.url}",
        "-PbugseeE2eCli=$cliPath",
        *extra,
    )

    private fun mergedLibs(fx: FixtureProject): List<File> =
        fx.projectDir.resolve("app/build/intermediates/merged_native_libs/release")
            .walk().filter { it.isFile && it.name == "libbugseefixture.so" }.toList()

    @Test
    fun `uploads the unstripped library with no debugSymbolLevel configured`() {
        MockSymbolServer().use { server ->
            val fx = fixture()
            val result = assemble(fx, server)

            val lib = mergedLibs(fx).single()
            assertTrue("merged_native_libs holds the UNSTRIPPED library (DWARF)", ElfInfo.hasDwarf(lib))
            val buildId = ElfInfo.buildId(lib)
            assertNotNull("fixture library carries a GNU build-id", buildId)
            buildId!!

            val post = server.symbolPosts.single()
            assertTrue("registered by the library's GNU build-id", post.text.contains(buildId))
            assertTrue(post.text.contains("breakpad"))
            assertFalse("no --force by default", post.text.contains("\"overwrite\":true"))
            assertEquals("bytes were transferred once", 1, server.puts.size)
            assertFalse(result.output.contains("using AGP's native debug symbols instead"))
        }
    }

    @Test
    fun `uploads the unstripped library even when AGP produces no symbols (debugSymbolLevel NONE)`() {
        MockSymbolServer().use { server ->
            val fx = fixture()
            val result = assemble(fx, server, extra = arrayOf("-PbugseeE2eSymbolLevel=NONE"))
            assertFalse(
                "NONE: AGP writes no native-debug-symbols.zip",
                fx.projectDir.resolve("app/build/outputs/native-debug-symbols").exists(),
            )
            val buildId = ElfInfo.buildId(mergedLibs(fx).single())!!
            assertTrue(server.symbolPosts.single().text.contains(buildId))
            assertEquals(1, server.puts.size)
            assertFalse(result.output.contains("using AGP's native debug symbols instead"))
        }
    }

    @Test
    fun `useMergedNativeLibs=false with NONE uploads nothing`() {
        MockSymbolServer().use { server ->
            val result = assemble(
                fixture(), server,
                extra = arrayOf("-PbugseeE2eUseMerged=false", "-PbugseeE2eSymbolLevel=NONE"),
            )
            assertTrue("opt-out restores the debugSymbolLevel-dependent behaviour", server.symbolPosts.isEmpty())
            assertFalse(result.output.contains("Uploading unstripped native libraries"))
        }
    }

    @Test
    fun `useMergedNativeLibs=false uses AGP's own zip, not the library directory`() {
        MockSymbolServer().use { server ->
            val fx = fixture()
            val result = assemble(fx, server, extra = arrayOf("-PbugseeE2eUseMerged=false"))
            assertFalse(result.output.contains("Uploading unstripped native libraries"))
            assertTrue(
                "AGP's default release output is the zip the legacy path uploads",
                result.output.contains("Native symbols file found"),
            )
            assertEquals(1, server.symbolPosts.size)
        }
    }

    @Test
    fun `a library the server already has is skipped, and --force replaces it`() {
        MockSymbolServer(alreadyHasSymbols = true).use { server ->
            val fx = fixture()
            assemble(fx, server)
            assertEquals(1, server.symbolPosts.size)
            assertTrue("deduped by build-id: nothing transferred", server.puts.isEmpty())

            assemble(fx, server, extra = arrayOf("-PbugseeE2eForce=true"))
            assertEquals(2, server.symbolPosts.size)
            assertTrue(server.symbolPosts.last().text.contains("\"overwrite\":true"))
            assertEquals("forced upload transfers the bytes", 1, server.puts.size)
        }
    }

    @Test
    fun `legacy FULL zip path still uploads with --force when merged libs are disabled`() {
        MockSymbolServer(alreadyHasSymbols = true).use { server ->
            val fx = fixture()
            assemble(fx, server, extra = arrayOf("-PbugseeE2eUseMerged=false", "-PbugseeE2eSymbolLevel=FULL"))
            val buildId = ElfInfo.buildId(mergedLibs(fx).single())!!
            val post = server.symbolPosts.single()
            assertTrue(post.text.contains(buildId))
            assertTrue("FULL (.so.dbg) zips are forced so they replace SYMBOL_TABLE", post.text.contains("\"overwrite\":true"))
            assertEquals(1, server.puts.size)
        }
    }

    private fun shim(exitCode: Int): File = temp.newFile("bugsee-cli-shim-$exitCode").apply {
        // Answers `exitCode` for a directory input and delegates everything else to the real CLI.
        writeText(
            "#!/bin/sh\nfor last; do :; done\n" +
                "if [ -d \"\$last\" ]; then echo 'shim: directory input refused' >&2; exit $exitCode; fi\n" +
                "exec \"$cli\" \"\$@\"\n",
        )
        setExecutable(true)
    }

    private fun assertFallsBackToZip(exitCode: Int) {
        MockSymbolServer().use { server ->
            val fx = fixture()
            val result = assemble(
                fx, server, cliPath = shim(exitCode).absolutePath,
                extra = arrayOf("-PbugseeE2eSymbolLevel=FULL"),
            )
            assertTrue(result.output.contains("using AGP's native debug symbols instead"))
            val buildId = ElfInfo.buildId(mergedLibs(fx).single())!!
            assertTrue("the zip fallback uploaded the library", server.symbolPosts.single().text.contains(buildId))
            assertEquals(1, server.puts.size)
        }
    }

    @Test
    fun `falls back to AGP symbols when the CLI reports no libraries in the directory (exit 10)`() =
        assertFallsBackToZip(10)

    @Test
    fun `falls back to AGP symbols when the CLI cannot take the directory (exit 11)`() =
        assertFallsBackToZip(11)

    @Test
    fun `a substantive CLI failure on the directory does not retry through the zip`() {
        MockSymbolServer().use { server ->
            val result = assemble(
                fixture(), server, cliPath = shim(21).absolutePath,
                extra = arrayOf("-PbugseeE2eSymbolLevel=FULL"),
            )
            assertFalse(result.output.contains("using AGP's native debug symbols instead"))
            assertTrue(server.symbolPosts.isEmpty())
        }
    }

    @Test
    fun `running the upload task alone builds the merged libraries first`() {
        MockSymbolServer().use { server ->
            val fx = fixture()
            val result = fx.buildTasks(
                listOf(":app:uploadBugseeReleaseNative"), null,
                "-PbugseeE2eEndpoint=${server.url}", "-PbugseeE2eCli=$cli", "-PbugseeE2eSymbolLevel=NONE",
            )
            assertTrue(result.output.contains(":app:mergeReleaseNativeLibs"))
            val buildId = ElfInfo.buildId(mergedLibs(fx).single())!!
            assertTrue(server.symbolPosts.single().text.contains(buildId))
        }
    }

    @Test
    fun `works with the configuration cache and re-uploads on a cache hit`() {
        MockSymbolServer().use { server ->
            val fx = fixture()
            val first = assemble(fx, server, extra = arrayOf("--configuration-cache"))
            assertTrue(first.output.contains("Configuration cache entry stored"))
            val second = assemble(fx, server, extra = arrayOf("--configuration-cache"))
            assertTrue(second.output.contains("Reusing configuration cache"))
            assertFalse(second.output.contains("configuration cache problems"))
            assertEquals("each build registers the library; the server dedups", 2, server.symbolPosts.size)
        }
    }
}
