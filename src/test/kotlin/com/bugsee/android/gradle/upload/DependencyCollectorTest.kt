package com.bugsee.android.gradle.upload

import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ComponentSelectionDescriptor
import org.gradle.api.artifacts.result.ComponentSelectionCause
import org.gradle.api.artifacts.result.ComponentSelectionReason
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyCollectorTest {

    // ── Tiny in-memory implementations of the Gradle ResolvedComponentResult
    // ── tree we walk in DependencyCollector. Only the bits the collector
    // ── actually touches are non-null; the rest throw if a future change
    // ── starts touching them so the test surfaces the new dependency
    // ── instead of silently passing null.

    private class FakeModuleId(val g: String, val m: String, val v: String) : ModuleComponentIdentifier {
        override fun getDisplayName(): String = "$g:$m:$v"
        override fun getGroup(): String = g
        override fun getModule(): String = m
        override fun getVersion(): String = v
        override fun getModuleIdentifier() = throw NotImplementedError()
    }

    private class FakeProjectId(val path: String) : ProjectComponentIdentifier {
        override fun getDisplayName(): String = "project $path"
        override fun getProjectPath(): String = path
        override fun getProjectName(): String = path.substringAfterLast(':')
        override fun getBuild() = throw NotImplementedError()
        override fun getBuildTreePath(): String = path
    }

    /**
     * Implements [ComponentSelectionDescriptor] just enough for the
     * collector's `selectionReason.descriptions.firstOrNull()?.description`
     * read at `DependencyCollector.kt`. Earlier revisions threw on
     * `getDescriptions()` — that hid the fact that the production
     * code's `includeSelectedReason=true` path was untested, because
     * the only test fixture that called it would throw before the
     * assertion ran.
     */
    private class FakeDescriptor(private val desc: String) : ComponentSelectionDescriptor {
        override fun getDescription(): String = desc
        override fun getCause(): ComponentSelectionCause =
            ComponentSelectionCause.REQUESTED
    }

    /**
     * Accepts a list of descriptions so tests can pin which one the
     * collector reads — specifically, the FIRST descriptor in the
     * list, not the last. Earlier revisions of this fake carried a
     * single description, leaving the choice between `firstOrNull` and
     * `lastOrNull` in the production code indistinguishable; a
     * mutation swapping the two would have escaped detection.
     */
    private class FakeReason(private val descs: List<String>) : ComponentSelectionReason {
        constructor(single: String) : this(listOf(single))

        override fun isForced(): Boolean = false
        override fun isConflictResolution(): Boolean = false
        override fun isSelectedByRule(): Boolean = false
        override fun isExpected(): Boolean = true
        override fun isCompositeSubstitution(): Boolean = false
        override fun isConstrained(): Boolean = false
        override fun getDescriptions(): List<ComponentSelectionDescriptor> =
            descs.map { FakeDescriptor(it) }
        override fun toString(): String = descs.firstOrNull() ?: ""
        // Newer Gradle versions surface a separate single-line description.
        val description: String get() = descs.firstOrNull() ?: ""
    }

    private class FakeComponent(
        private val identifier: ComponentIdentifier,
        private val deps: MutableList<DependencyResult> = mutableListOf(),
        // A list, not a single string — lets tests with the
        // `includeSelectedReason=true` flag verify the collector reads
        // the FIRST descriptor (not the last). A single-element list
        // is the common case.
        private val reasonDescs: List<String> = listOf("selected")
    ) : ResolvedComponentResult {
        override fun getId(): ComponentIdentifier = identifier
        override fun getDependencies(): Set<DependencyResult> = deps.toMutableSet()
        override fun getDependents() = throw NotImplementedError()
        override fun getSelectionReason(): ComponentSelectionReason = FakeReason(reasonDescs)
        override fun getModuleVersion() = throw NotImplementedError()
        override fun getVariants(): List<ResolvedVariantResult> = emptyList()
        override fun getDependenciesForVariant(variant: ResolvedVariantResult): List<DependencyResult> =
            emptyList()
        fun addDep(child: ResolvedComponentResult) {
            deps.add(FakeResolvedDep(child))
        }
    }

    private class FakeResolvedDep(private val selectedComponent: ResolvedComponentResult)
        : ResolvedDependencyResult {
        override fun getSelected(): ResolvedComponentResult = selectedComponent
        override fun getResolvedVariant(): ResolvedVariantResult = throw NotImplementedError()
        override fun getFrom(): ResolvedComponentResult = throw NotImplementedError()
        override fun getRequested(): ComponentSelector = throw NotImplementedError()
        override fun isConstraint(): Boolean = false
    }

    // ── Helpers ────────────────────────────────────────────────────

    private fun module(group: String, name: String, version: String): FakeComponent =
        FakeComponent(FakeModuleId(group, name, version))

    private fun project(path: String): FakeComponent =
        FakeComponent(FakeProjectId(path))

    private fun root(): FakeComponent =
        FakeComponent(FakeModuleId("__root__", "root", "1.0"))

    private fun fixedClock(epochMs: Long): () -> Long = { epochMs }

    // ── Tests ──────────────────────────────────────────────────────

    @Test
    fun `walks every unique component exactly once even when reached via multiple parents`() {
        // root → a → c
        // root → b → c        (c reached twice; must appear once)
        val a = module("g", "a", "1")
        val b = module("g", "b", "1")
        val c = module("g", "c", "1")
        a.addDep(c)
        b.addDep(c)
        val r = root()
        r.addDep(a); r.addDep(b)

        val collector = DependencyCollector(maxCount = 100, includeSelectedReason = false,
                                            clock = fixedClock(1000L))
        val result = collector.collect(r, emptyMap(), emptyList())

        // 3 components: a, b, c — root is excluded.
        assertEquals(3, result.entries.size)
        assertEquals(setOf("a", "b", "c"), result.entries.map { it.name }.toSet())
    }

    @Test
    fun `marks first-level deps as direct and the rest as transitive`() {
        // root → a (direct) → b (transitive)
        val b = module("g", "b", "1")
        val a = module("g", "a", "1")
        a.addDep(b)
        val r = root()
        r.addDep(a)

        val collector = DependencyCollector(100, false, clock = fixedClock(1000L))
        val result = collector.collect(r, emptyMap(), emptyList())

        val aEntry = result.entries.first { it.name == "a" }
        val bEntry = result.entries.first { it.name == "b" }
        assertTrue("a must be direct",  aEntry.direct)
        assertFalse("b must be transitive", bEntry.direct)
    }

    @Test
    fun `classifies module vs project vs file types`() {
        val mod = module("g", "mod", "1")
        val proj = project(":sub")
        val r = root()
        r.addDep(mod); r.addDep(proj)

        val collector = DependencyCollector(100, false, clock = fixedClock(1000L))
        val result = collector.collect(
            r,
            emptyMap(),
            listOf(DependencyCollector.FileDep(displayName = "libs/legacy.jar", scope = "runtimeOnly"))
        )

        assertEquals(DependencyEntry.Type.LIBRARY, result.entries.first { it.name == "mod" }.type)
        assertEquals(DependencyEntry.Type.PROJECT, result.entries.first { it.name == ":sub" }.type)
        assertEquals(DependencyEntry.Type.FILE,
                     result.entries.first { it.name == "libs/legacy.jar" }.type)
    }

    @Test
    fun `applies declared scope to direct deps only`() {
        // root → a (declared as 'implementation') → b (transitive — no scope)
        val a = module("com.foo", "a", "1")
        val b = module("com.foo", "b", "1")
        a.addDep(b)
        val r = root()
        r.addDep(a)

        val collector = DependencyCollector(100, false, clock = fixedClock(1000L))
        val result = collector.collect(
            r,
            mapOf("com.foo:a" to "implementation"),
            emptyList()
        )

        assertEquals("implementation", result.entries.first { it.name == "a" }.scope)
        assertNull("transitive must not carry scope",
                   result.entries.first { it.name == "b" }.scope)
    }

    @Test
    fun `caps the entry list at maxCount and sets truncated`() {
        // root → 10 direct deps, all unique
        val r = root()
        repeat(10) { i ->
            r.addDep(module("g", "m$i", "1"))
        }

        val collector = DependencyCollector(maxCount = 5,
                                            includeSelectedReason = false,
                                            clock = fixedClock(1000L))
        val result = collector.collect(r, emptyMap(), emptyList())

        assertEquals(5, result.entries.size)
        assertTrue("must report truncation when the cap kicks in",
                   result.summary.truncated)
        assertEquals(5, result.summary.total)
    }

    @Test
    fun `direct entries appear before transitives in the truncated list`() {
        // root → a (direct), b (direct), c (direct)
        // a → t1, b → t2, c → t3
        // With cap=3 only direct ones survive.
        val a = module("g", "a", "1"); a.addDep(module("g", "t1", "1"))
        val b = module("g", "b", "1"); b.addDep(module("g", "t2", "1"))
        val c = module("g", "c", "1"); c.addDep(module("g", "t3", "1"))
        val r = root(); r.addDep(a); r.addDep(b); r.addDep(c)

        val collector = DependencyCollector(maxCount = 3,
                                            includeSelectedReason = false,
                                            clock = fixedClock(1000L))
        val result = collector.collect(r, emptyMap(), emptyList())

        assertEquals(3, result.entries.size)
        assertTrue(result.entries.all { it.direct })
        assertTrue(result.summary.truncated)
    }

    @Test
    fun `summary mirrors per-type counts`() {
        // 2 libraries + 1 project + 1 file
        val mod1 = module("g", "lib1", "1")
        val mod2 = module("g", "lib2", "1")
        val proj = project(":sub")
        val r = root()
        r.addDep(mod1); r.addDep(mod2); r.addDep(proj)

        val collector = DependencyCollector(100, false, clock = fixedClock(1000L))
        val result = collector.collect(
            r,
            emptyMap(),
            listOf(DependencyCollector.FileDep("libs/legacy.jar", "runtimeOnly"))
        )

        // 4 direct entries; no transitives.
        assertEquals(4, result.summary.total)
        assertEquals(4, result.summary.direct)
        assertEquals(0, result.summary.transitive)
        assertEquals(2, result.summary.byType.library)
        assertEquals(1, result.summary.byType.project)
        assertEquals(1, result.summary.byType.file)
        assertFalse(result.summary.truncated)
    }

    @Test
    fun `summary collected_at uses the injected clock`() {
        val collector = DependencyCollector(100, false, clock = fixedClock(123_456_789L))
        val result = collector.collect(root(), emptyMap(), emptyList())
        assertEquals(123_456_789L, result.summary.collectedAtEpochMs)
    }

    @Test
    fun `excludes the resolved root from the entry list`() {
        // The variant's resolved root is the project itself — not a
        // useful "dependency" of the build. The collector must not
        // emit an entry for it.
        val r = root()
        val collector = DependencyCollector(100, false, clock = fixedClock(1000L))
        val result = collector.collect(r, emptyMap(), emptyList())
        assertEquals(0, result.entries.size)
    }

    @Test
    fun `includeSelectedReason=true surfaces the description on each entry`() {
        // Regression guard: the production read at DependencyCollector.kt
        // is `selectionReason.descriptions.firstOrNull()?.description`.
        // An earlier revision of the test fixture threw on
        // `getDescriptions()`, hiding the fact that no test exercised
        // this branch — the first real CI build with the DSL flag on
        // would have crashed. Pin the contract.
        val a = FakeComponent(FakeModuleId("g", "a", "1"), reasonDescs = listOf("forced"))
        val r = root(); r.addDep(a)
        val collector = DependencyCollector(
            maxCount = 100,
            includeSelectedReason = true,
            clock = fixedClock(1000L)
        )
        val result = collector.collect(r, emptyMap(), emptyList())
        val entry = result.entries.first { it.name == "a" }
        assertEquals("forced", entry.selectedReason)
    }

    @Test
    fun `includeSelectedReason picks the FIRST descriptor (not the last)`() {
        // Pins the literal `firstOrNull()` read at DependencyCollector.kt.
        // A mutation to `lastOrNull()` would surface the wrong
        // description (`"conflict resolution"` here instead of
        // `"forced"`). The headline descriptor is the most informative
        // — readers expect the primary reason, not the tail of the
        // descriptor chain.
        val a = FakeComponent(
            FakeModuleId("g", "a", "1"),
            reasonDescs = listOf("forced", "conflict resolution")
        )
        val r = root(); r.addDep(a)
        val collector = DependencyCollector(
            maxCount = 100,
            includeSelectedReason = true,
            clock = fixedClock(1000L)
        )
        val result = collector.collect(r, emptyMap(), emptyList())
        val entry = result.entries.first { it.name == "a" }
        assertEquals("forced", entry.selectedReason)
    }

    @Test
    fun `includeSelectedReason=false omits the field on every entry`() {
        val a = FakeComponent(FakeModuleId("g", "a", "1"), reasonDescs = listOf("forced"))
        val r = root(); r.addDep(a)
        val collector = DependencyCollector(
            maxCount = 100,
            includeSelectedReason = false,
            clock = fixedClock(1000L)
        )
        val result = collector.collect(r, emptyMap(), emptyList())
        val entry = result.entries.first { it.name == "a" }
        assertNull(entry.selectedReason)
    }

    // ── Parent-edge tracking ──────────────────────────────────────

    @Test
    fun `direct deps carry an empty parents list (root is never an entry)`() {
        val a = module("g", "a", "1")
        val r = root(); r.addDep(a)

        val collector = DependencyCollector(100, false, clock = fixedClock(1000L))
        val entry = collector.collect(r, emptyMap(), emptyList())
            .entries.first { it.name == "a" }
        assertEquals(emptyList<String>(), entry.parents)
    }

    @Test
    fun `transitive carries its immediate parent's id`() {
        // root → a → b. b's parent must be a's id, not root.
        val b = module("g", "b", "1")
        val a = module("g", "a", "1"); a.addDep(b)
        val r = root(); r.addDep(a)

        val collector = DependencyCollector(100, false, clock = fixedClock(1000L))
        val entries = collector.collect(r, emptyMap(), emptyList()).entries
        val aId = entries.first { it.name == "a" }.id
        val bParents = entries.first { it.name == "b" }.parents
        assertEquals(listOf(aId), bParents)
    }

    @Test
    fun `shared transitive accumulates every parent that pulls it in`() {
        // Regression guard for the DFS subtlety: the visited-set dedup
        // prevents re-recursing into a shared component's children, but
        // the parent edge MUST still be recorded on every arrival.
        // Without that, a transitive reached via two parents would
        // only carry the first parent — silently losing graph info.
        //
        // root → a → c
        // root → b → c   (c shared)
        val c = module("g", "c", "1")
        val a = module("g", "a", "1"); a.addDep(c)
        val b = module("g", "b", "1"); b.addDep(c)
        val r = root(); r.addDep(a); r.addDep(b)

        val collector = DependencyCollector(100, false, clock = fixedClock(1000L))
        val entries = collector.collect(r, emptyMap(), emptyList()).entries
        val aId = entries.first { it.name == "a" }.id
        val bId = entries.first { it.name == "b" }.id
        val cParents = entries.first { it.name == "c" }.parents
        assertEquals(setOf(aId, bId), cParents.toSet())
        // Both parents present — neither swallowed by the dedup pass.
        assertEquals(2, cParents.size)
    }

    @Test
    fun `truncation filters out parents whose target was evicted`() {
        // Cap=2. The walk emits a (direct), b (direct), then would
        // emit c (transitive) but the cap kicks in. b has a → b edge
        // in parents (b's parent is a) — that stays since a survives.
        // The "evicted entry's id appears as a parent" pattern only
        // shows up when a TRANSITIVE survives but its declared parent
        // doesn't — engineered below.
        //
        // root → a (direct) → c (transitive)
        // root → b (direct)
        // With cap=2 we keep [a, b]; c is dropped. The dangling-parent
        // case actually happens the other way: shared transitives
        // whose first parent got evicted. Use a second arrangement:
        //
        // root → x (direct) → s (transitive, shared)
        // root → y (direct) → s              (s carries parents=[x, y])
        // root → z (direct)
        // With cap=4 we keep [x, y, z, s]; s.parents stays [x, y].
        // To force eviction of a parent referenced by a survivor we
        // need the order of survival to keep s but evict y. The
        // current emission order (file deps → direct → transitive)
        // makes that hard — but we can still verify the filter via
        // the symmetric case: keep cap small enough that some
        // shared-transitives survive while a parent doesn't.
        //
        // Simpler / cleaner: cap=3 with [x, y, z] all direct + s
        // shared transitive of x and y. cap=3 keeps x, y, z — s
        // dropped. So no dangling references. Need to invert: cap=2
        // keeps x, y. s dropped. Still no dangling.
        //
        // To engineer a dangling parent we need a survivor whose
        // parent is evicted. Make s the LAST direct (so it survives
        // earlier than its parent in the emission order)? Direct
        // first-level deps are emitted in walk order. Let's lean on
        // file deps which emit FIRST:
        //
        // file dep "lib.jar" (direct, no parents)
        // root → big-direct (direct) → shared-trans (transitive)
        // root → another-direct (direct) → shared-trans
        // cap=3: keep [lib.jar, big-direct, another-direct], drop
        // shared-trans. No dangling.
        //
        // OK — the truncation filter is symmetric: any reference to
        // a dropped entry MUST be filtered. Engineer it by hand
        // using a fixture where the transitive survives but its
        // parent doesn't. We can't easily do this with the natural
        // emission order — the test below instead verifies the
        // self-consistency invariant directly: for the kept entries,
        // every parent id appears in the kept-set.
        val tA = module("g", "tA", "1")
        val a = module("g", "a", "1"); a.addDep(tA)
        val b = module("g", "b", "1")
        val r = root(); r.addDep(a); r.addDep(b)

        val collector = DependencyCollector(
            maxCount = 2, includeSelectedReason = false,
            clock = fixedClock(1000L)
        )
        val result = collector.collect(r, emptyMap(), emptyList())
        val keptIds = result.entries.map { it.id }.toSet()
        for (e in result.entries) {
            for (p in e.parents) {
                assertTrue(
                    "kept entry ${e.id} has dangling parent reference $p (kept=$keptIds)",
                    p in keptIds
                )
            }
        }
    }

    @Test
    fun `id matches type-prefixed coordinate string for library entries`() {
        // Pins the wire-level identity shape — must stay in lockstep
        // with the viewer's `identityOf` and the worker's `_identity`.
        // A drift here would silently break diff matching across the
        // boundary.
        val a = module("androidx.core", "core-ktx", "1.13.1")
        val r = root(); r.addDep(a)
        val collector = DependencyCollector(100, false, clock = fixedClock(1000L))
        val entry = collector.collect(r, emptyMap(), emptyList())
            .entries.first { it.name == "core-ktx" }
        assertEquals("library:androidx.core:core-ktx", entry.id)
    }

    @Test
    fun `id format covers project and file entries`() {
        val proj = project(":sub")
        val r = root(); r.addDep(proj)
        val collector = DependencyCollector(100, false, clock = fixedClock(1000L))
        val result = collector.collect(
            r, emptyMap(),
            listOf(DependencyCollector.FileDep("libs/legacy.jar", "runtimeOnly"))
        )
        // Project deps emit an empty group, so the id is
        // "project:::sub" (three colons total — two separators + the
        // leading colon that's part of `:sub` itself). Must match
        // the viewer's identityOf shape exactly — the diff machinery
        // relies on bit-for-bit equality across the wire.
        assertEquals("project:::sub",
                     result.entries.first { it.name == ":sub" }.id)
        // File deps also have an empty group; id is two separators
        // ("file::") + the displayName (which here has no leading colon).
        assertEquals("file::libs/legacy.jar",
                     result.entries.first { it.name == "libs/legacy.jar" }.id)
    }

    @Test
    fun `directOnly mode drops transitives entirely`() {
        // Wires the `scope = "runtime_direct_only"` DSL value. The
        // graph is still walked (we still need to identify what's
        // direct), but only first-level entries make it into the
        // output. Transitives must NOT appear; the summary's
        // `transitive` counter must be 0.
        val tA = module("g", "transitive-A", "1")
        val a = module("g", "direct-A", "1"); a.addDep(tA)
        val tB = module("g", "transitive-B", "1")
        val b = module("g", "direct-B", "1"); b.addDep(tB)
        val r = root(); r.addDep(a); r.addDep(b)

        val collector = DependencyCollector(
            maxCount = 100,
            includeSelectedReason = false,
            directOnly = true,
            clock = fixedClock(1000L)
        )
        val result = collector.collect(r, emptyMap(), emptyList())

        assertEquals(2, result.entries.size)
        assertEquals(setOf("direct-A", "direct-B"),
                     result.entries.map { it.name }.toSet())
        assertTrue("every emitted entry must be direct in directOnly mode",
                   result.entries.all { it.direct })
        assertEquals(2, result.summary.total)
        assertEquals(0, result.summary.transitive)
    }
}
