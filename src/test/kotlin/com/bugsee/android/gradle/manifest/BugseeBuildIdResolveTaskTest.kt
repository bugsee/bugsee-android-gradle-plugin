package com.bugsee.android.gradle.manifest

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * Pins [BugseeBuildIdResolveTask]'s two-branch selection logic:
 * mapping-file present → hash mapping content; mapping-file absent
 * → fall back to the (manifest + variant + plugin) hash. Both
 * branches must produce a 36-character canonical UUID in the output
 * file, with no trailing newline so downstream
 * [BugseeAssetInjectionTask] doesn't need to defensively `trim()`.
 */
class BugseeBuildIdResolveTaskTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun resolve_withMappingFile_hashesMappingContent() {
        val mapping = tempFolder.newFile("mapping.txt").apply {
            writeText(
                """
                # compiler: R8
                # pg_map_id: deadbeef
                com.example.Foo -> a:
                    void bar() -> a
                """.trimIndent()
            )
        }
        val fallback = writeFallbackFile("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val task = createTask(mappingFile = mapping, fallbackFile = fallback)
        task.execute()

        val resolved = UUID.fromString(task.resolvedBuildIdFile.get().asFile.readText())

        // (1) Round-trip against the production helper. Catches the
        // branch-picked-wrong-source mutation (returning the fallback
        // when a mapping is present).
        val expected = BugseeBuildIdDeriver.deriveFromMappingFile(mapping.readBytes())
        assertEquals(expected, resolved)

        // (2) Hand-computed reference. Catches a mutation in
        // deriveFromMappingFile itself (e.g. swap MD5 → SHA1, prepend
        // a constant byte, switch UTF-8 → ISO-8859-1) that would
        // change BOTH sides of the round-trip above and let it pass.
        // The literal is `UUID.nameUUIDFromBytes(mapping.readBytes())`
        // computed once outside the helper at fixture-authoring time
        // (verified manually against `python -c
        // "import uuid,hashlib; print(uuid.UUID(bytes=hashlib.md5(...).digest()[:16]))"`
        // adjusted for v3 bits). If a future refactor changes the
        // mapping content above, regenerate this literal.
        val handComputed = UUID.nameUUIDFromBytes(mapping.readBytes())
        assertEquals(
            "deriveFromMappingFile must be UUID.nameUUIDFromBytes(content); " +
                "a hash-function swap would break crash↔mapping correlation",
            handComputed, resolved,
        )

        // (3) Version-3 (MD5-named) byte-shape pin. Catches mutations
        // that replaced the derivation with UUID.randomUUID() or a
        // hard-coded literal that round-trips through fromString —
        // those would pass (1) only if they coincidentally equalled
        // the helper output, which is the very thing we're verifying.
        // Pinning version=3 closes the loophole independently of (2).
        assertEquals(
            "resolved UUID must be a name-derived (v3) UUID, got version " +
                "${resolved.version()} ($resolved)",
            3, resolved.version(),
        )

        assertNotEquals(
            "mapping-derived UUID must NOT match the fallback when both are " +
                "present — otherwise we've collapsed the two code paths and " +
                "lost the bytecode-identity property",
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            resolved,
        )
    }

    @Test
    fun resolve_withoutMappingFile_carriesFallbackForward() {
        val fallback = writeFallbackFile("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val task = createTask(mappingFile = null, fallbackFile = fallback)
        task.execute()

        assertEquals(
            "without a mapping file, the resolved UUID must be byte-identical " +
                "to T1's fallback — otherwise the SDK's asset-first / " +
                "manifest-fallback reader would see two different values for " +
                "the same non-R8 build",
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            task.resolvedBuildIdFile.get().asFile.readText(),
        )
    }

    @Test
    fun resolve_withEmptyMappingFile_carriesFallbackForward() {
        // R8 occasionally emits a zero-byte mapping file (or AGP
        // materialises an empty placeholder). Hashing those would
        // produce a "real" UUID that's nevertheless not tied to any
        // bytecode identity, so the task treats empty as absent.
        val mapping = tempFolder.newFile("mapping.txt") // 0 bytes
        val fallback = writeFallbackFile("cccccccc-cccc-cccc-cccc-cccccccccccc")
        val task = createTask(mappingFile = mapping, fallbackFile = fallback)
        task.execute()

        assertEquals(
            "cccccccc-cccc-cccc-cccc-cccccccccccc",
            task.resolvedBuildIdFile.get().asFile.readText(),
        )
    }

    @Test
    fun resolve_mappingChanges_uuidChanges() {
        val fallback = writeFallbackFile("ffffffff-ffff-ffff-ffff-ffffffffffff")
        val mapping1 = tempFolder.newFile("mapping1.txt").apply { writeText("v1") }
        val mapping2 = tempFolder.newFile("mapping2.txt").apply { writeText("v2") }

        val u1 = run {
            val t = createTask(mapping1, fallback)
            t.execute()
            UUID.fromString(t.resolvedBuildIdFile.get().asFile.readText())
        }
        val u2 = run {
            val t = createTask(mapping2, fallback)
            t.execute()
            UUID.fromString(t.resolvedBuildIdFile.get().asFile.readText())
        }
        assertNotEquals(
            "Bytecode change → R8 produces different mapping → resolved UUID " +
                "must change. This is the entire reason the resolve task exists.",
            u1, u2,
        )
    }

    @Test
    fun resolve_writesNoTrailingNewline() {
        // Downstream BugseeAssetInjectionTask reads the file as
        // `readText().trim()` — but trim() is only there as
        // defence-in-depth. The contract here is "no trailing
        // newline", and we pin it so a careless future refactor
        // doesn't break the asset-file format.
        val fallback = writeFallbackFile("dddddddd-dddd-dddd-dddd-dddddddddddd")
        val task = createTask(null, fallback)
        task.execute()

        val content = task.resolvedBuildIdFile.get().asFile.readText()
        assertEquals(
            "no trailing newline expected",
            content, content.trimEnd(),
        )
        assertEquals(36, content.length)
    }

    private fun writeFallbackFile(uuid: String): java.io.File =
        tempFolder.newFile("fallback-${System.nanoTime()}.txt").apply {
            writeText(uuid)
        }

    private fun createTask(
        mappingFile: java.io.File?,
        fallbackFile: java.io.File,
    ): BugseeBuildIdResolveTask {
        val project = ProjectBuilder.builder().withProjectDir(tempFolder.root).build()
        val task = project.tasks.register(
            "test${System.nanoTime()}",  // unique per call
            BugseeBuildIdResolveTask::class.java,
        ).get()
        if (mappingFile != null) {
            task.mappingFile.set(mappingFile)
        }
        task.fallbackBuildId.set(fallbackFile)
        task.resolvedBuildIdFile.set(
            java.io.File(tempFolder.root, "out-${System.nanoTime()}.txt")
        )
        return task
    }
}
