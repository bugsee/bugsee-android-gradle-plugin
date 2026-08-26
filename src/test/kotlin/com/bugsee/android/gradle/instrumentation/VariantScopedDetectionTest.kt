package com.bugsee.android.gradle.instrumentation

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C12 — the wrapping matrix for variant-scoped SDK detection.
 *
 * [DependencyDetector.hasBugseeDependency] scans EVERY configuration of a
 * project, so a `debugImplementation` SDK dependency answers `true` for the
 * release variant too and release bytecode gets instrumented against a class
 * absent from its runtime classpath (proven: launch-time
 * `NoClassDefFoundError`). The fix is to scope detection to the configurations
 * that actually feed the variant being instrumented.
 *
 * The regression risk of that fix is larger than the bug, so this class pins
 * the whole deviation space BEFORE the fix lands:
 *
 *  - every declaration shape a consumer can use (`implementation`, `api`,
 *    `compileOnly`, `runtimeOnly`, build-type-scoped, flavor-scoped,
 *    variant-scoped, absent);
 *  - both the direct and the transitive-through-a-project-module forms;
 *  - the Bugsee-project-dependency form (`GROUP=com.bugsee`);
 *  - the legacy unscoped call, which every existing caller still uses and
 *    which must keep behaving exactly as it does today.
 *
 * Scope sets below mirror AGP's source-set-derived configuration names. In
 * production they are not hand-written: they come from walking
 * `variant.runtimeConfiguration`'s `extendsFrom` hierarchy, which AGP builds
 * from the variant's own source sets. `runtimeConfiguration` is also the
 * semantically correct source — instrumentation injects a *reference* that
 * must resolve at RUNTIME, which is precisely what that configuration models.
 */
class VariantScopedDetectionTest {

    private val root: Project = ProjectBuilder.builder().withName("root").build()

    private fun child(name: String): Project =
        ProjectBuilder.builder().withParent(root).withName(name).build()

    private fun Project.declare(configuration: String, coord: String) {
        dependencies.add(configurations.maybeCreate(configuration).name, coord)
    }

    private fun Project.declareProject(configuration: String, other: Project) {
        dependencies.add(
            configurations.maybeCreate(configuration).name,
            dependencies.project(mapOf("path" to other.path)),
        )
    }

    /**
     * The configurations that contribute to one variant — the union of its
     * compile and runtime classpaths, mirroring what the registrar derives from
     * `compileConfiguration`/`runtimeConfiguration`. `compileOnly` is included
     * on purpose; see [compileOnly still enables instrumentation - deliberately unchanged].
     */
    private fun variantScope(vararg sourceSets: String): Set<String> =
        buildSet {
            for (s in listOf("") + sourceSets) {
                val prefix = if (s.isEmpty()) "" else s
                fun name(base: String) =
                    if (prefix.isEmpty()) base else prefix + base.replaceFirstChar { it.uppercase() }
                add(name("implementation"))
                add(name("api"))
                add(name("runtimeOnly"))
                add(name("compileOnly"))
            }
        }

    private val debug = variantScope("debug")
    private val release = variantScope("release")
    private val freeDebug = variantScope("debug", "free", "freeDebug")
    private val paidDebug = variantScope("debug", "paid", "paidDebug")
    private val freeRelease = variantScope("release", "free", "freeRelease")

    private fun detects(project: Project, scope: Set<String>?, prefix: String = "bugsee-android") =
        DependencyDetector.hasBugseeDependency(project, prefix, null, scope)

    // ---------- direct declarations, unscoped configurations ----------

    @Test
    fun `implementation reaches every variant`() {
        val app = child("a1").also { it.declare("implementation", "com.bugsee:bugsee-android:7.0.0") }
        assertTrue("debug", detects(app, debug))
        assertTrue("release", detects(app, release))
    }

    @Test
    fun `api reaches every variant`() {
        val app = child("a2").also { it.declare("api", "com.bugsee:bugsee-android:7.0.0") }
        assertTrue("debug", detects(app, debug))
        assertTrue("release", detects(app, release))
    }

    @Test
    fun `runtimeOnly counts - the class is present at runtime`() {
        val app = child("a3").also { it.declare("runtimeOnly", "com.bugsee:bugsee-android:7.0.0") }
        assertTrue(detects(app, debug))
    }

    @Test
    fun `compileOnly still enables instrumentation - deliberately unchanged`() {
        // The stricter reading would exclude compileOnly: it is absent at
        // runtime, so an injected reference cannot resolve. But excluding it
        // ALSO silently disables instrumentation for a library module that
        // compiles against Bugsee and relies on the consuming app to supply it
        // at runtime — which works today. That is a separate behaviour change
        // with its own regression surface, so C12 changes only the VARIANT
        // dimension and leaves compileOnly's meaning exactly as it was.
        val app = child("a4").also { it.declare("compileOnly", "com.bugsee:bugsee-android:7.0.0") }
        assertTrue(detects(app, debug))
    }

    @Test
    fun `variant scoping still applies to compileOnly`() {
        val app = child("a4b").also {
            it.declare("debugCompileOnly", "com.bugsee:bugsee-android:7.0.0")
        }
        assertTrue("debug", detects(app, debug))
        assertFalse("release never sees it, at compile time or any other time", detects(app, release))
    }

    @Test
    fun `no bugsee dependency at all reaches nothing`() {
        val app = child("a5").also { it.declare("implementation", "androidx.core:core:1.0.0") }
        assertFalse(detects(app, debug))
        assertFalse(detects(app, release))
    }

    // ---------- build-type scoping: THE BUG ----------

    @Test
    fun `debugImplementation reaches debug but NOT release`() {
        val app = child("b1").also {
            it.declare("debugImplementation", "com.bugsee:bugsee-android:7.0.0")
        }
        assertTrue("debug must still be instrumented", detects(app, debug))
        assertFalse(
            "release does not carry the SDK; instrumenting it ships a launch crash",
            detects(app, release),
        )
    }

    @Test
    fun `releaseImplementation reaches release but NOT debug`() {
        val app = child("b2").also {
            it.declare("releaseImplementation", "com.bugsee:bugsee-android:7.0.0")
        }
        assertTrue("release", detects(app, release))
        assertFalse("debug does not carry the SDK", detects(app, debug))
    }

    // ---------- flavor and variant scoping ----------

    @Test
    fun `flavor-scoped dependency reaches only that flavor`() {
        val app = child("c1").also {
            it.declare("freeImplementation", "com.bugsee:bugsee-android:7.0.0")
        }
        assertTrue("freeDebug", detects(app, freeDebug))
        assertTrue("freeRelease", detects(app, freeRelease))
        assertFalse("paidDebug", detects(app, paidDebug))
    }

    @Test
    fun `variant-scoped dependency reaches only that exact variant`() {
        val app = child("c2").also {
            it.declare("freeDebugImplementation", "com.bugsee:bugsee-android:7.0.0")
        }
        assertTrue("freeDebug", detects(app, freeDebug))
        assertFalse("freeRelease", detects(app, freeRelease))
        assertFalse("paidDebug", detects(app, paidDebug))
    }

    // ---------- transitive through a project module ----------

    @Test
    fun `transitive through a module is found when the module edge is in scope`() {
        val lib = child("d1lib").also { it.declare("implementation", "com.bugsee:bugsee-android:7.0.0") }
        val app = child("d1app").also { it.declareProject("implementation", lib) }
        assertTrue("debug", detects(app, debug))
        assertTrue("release", detects(app, release))
    }

    @Test
    fun `transitive is NOT found when the module edge itself is out of scope`() {
        val lib = child("d2lib").also { it.declare("implementation", "com.bugsee:bugsee-android:7.0.0") }
        val app = child("d2app").also { it.declareProject("debugImplementation", lib) }
        assertTrue("debug", detects(app, debug))
        assertFalse(
            "release never depends on the module that carries the SDK",
            detects(app, release),
        )
    }

    @Test
    fun `inside a depended-on module every configuration still counts`() {
        // Documented conservatism: a variant of THIS project does not map onto
        // any particular variant of a module it depends on, so once the module
        // edge is in scope the module is scanned unscoped. Over-detection here
        // preserves the KMP/wrapper case that transitive detection exists for.
        val lib = child("d3lib").also {
            it.declare("debugImplementation", "com.bugsee:bugsee-android:7.0.0")
        }
        val app = child("d3app").also { it.declareProject("implementation", lib) }
        assertTrue(detects(app, release))
    }

    // ---------- Bugsee project dependency (GROUP=com.bugsee) ----------

    @Test
    fun `bugsee project dependency respects scope`() {
        val sdk = child("library").also { it.extensions.extraProperties.set("GROUP", "com.bugsee") }
        val app = child("e1").also { it.declareProject("debugImplementation", sdk) }
        assertTrue("debug", DependencyDetector.hasBugseeDependency(app, "bugsee-android", "library", debug))
        assertFalse("release", DependencyDetector.hasBugseeDependency(app, "bugsee-android", "library", release))
    }

    // ---------- legacy callers must not change ----------

    @Test
    fun `a null scope preserves the legacy all-configurations behaviour`() {
        val app = child("f1").also {
            it.declare("debugImplementation", "com.bugsee:bugsee-android:7.0.0")
        }
        assertTrue(
            "every existing caller passes no scope and must keep seeing today's answer",
            detects(app, null),
        )
        assertTrue(DependencyDetector.hasBugseeDependency(app, "bugsee-android"))
    }

    @Test
    fun `an empty scope matches nothing - it is not a silent no-op`() {
        // An empty set means "no configuration feeds this variant", which must
        // match nothing. The tempting lenient reading (isNullOrEmpty -> scan
        // everything) would quietly resurrect the C12 leak for any caller that
        // computed an empty scope. The registrar never passes one: it degrades
        // an empty hierarchy to `null` (legacy scan) WITH a warning, so the
        // "we could not work it out" case stays loud instead of silently
        // disabling every instrumentation.
        val app = child("h1").also { it.declare("implementation", "com.bugsee:bugsee-android:7.0.0") }
        assertFalse(detects(app, emptySet()))
        assertTrue("sanity: the same project is found when the scope covers it", detects(app, debug))
    }

    // ---------- characterization: prefix matching is intentionally loose ----------

    @Test
    fun `an extension artifact satisfies the core prefix - characterization`() {
        // `isMatchingExternal` uses startsWith, so `bugsee-android-okhttp`
        // matches the core prefix `bugsee-android`. Pinned as-is: tightening it
        // to an exact match is a separate behaviour change with its own
        // regression surface, and in practice the extension brings the core in.
        val app = child("g1").also {
            it.declare("implementation", "com.bugsee:bugsee-android-okhttp:7.0.0")
        }
        assertTrue(detects(app, debug))
    }

    @Test
    fun `extension detection is scoped too`() {
        val app = child("g2").also {
            it.declare("debugImplementation", "com.bugsee:bugsee-android-okhttp:7.0.0")
        }
        assertTrue(detects(app, debug, "bugsee-android-okhttp"))
        assertFalse(detects(app, release, "bugsee-android-okhttp"))
    }
}
