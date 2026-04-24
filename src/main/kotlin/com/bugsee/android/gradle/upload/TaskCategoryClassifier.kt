package com.bugsee.android.gradle.upload

/**
 * Classifies an AGP / Gradle task path (e.g. `:app:compileReleaseKotlin`)
 * into one of a small fixed set of categories used by the per-build
 * timing rollup. Dozens-of-unique-task-name builds fold down to a
 * handful of buckets users can actually reason about.
 *
 * The rules are precedence-ordered — FIRST match wins. Order matters
 * because task names overlap across dimensions (e.g.
 * `packageReleaseResources` is simultaneously a "package" and a
 * "resources" task — AGP treats it as resource packaging, so the
 * resources rule must come first).
 *
 * Pure / stateless — safe to call from any thread. Exposed as a
 * top-level function instead of an object so the list of rules
 * composes naturally with tests.
 */
internal enum class TaskCategory {
    // JVM-bytecode compilation (kotlinc / javac / R8 / desugar) —
    // renamed from the earlier `JAVA` as part of cross-platform
    // schema harmonisation. The wire-format field on the
    // appserver is `managed_code_ms`; iOS omits it entirely since
    // Swift / Obj-C / C++ all compile into the Mach-O and land
    // in `NATIVE` on that platform.
    MANAGED_CODE,
    NATIVE,
    RESOURCES,
    PACKAGING,
    OTHER,
}


internal object TaskCategoryClassifier {

    // Precedence-ordered list. Each rule matches against the basename
    // of the task path (anything after the last ':') case-insensitively.
    // Using a list (not a Map<regex, category>) pins the iteration
    // order — critical for the "resources beats packaging" precedence.
    private val RULES: List<Pair<Regex, TaskCategory>> = listOf(
        // --- Resources / assets / manifests --------------------------
        // Matched first so `packageReleaseResources` (which also matches
        // the packaging rule) gets classified as RESOURCES. The generic
        // `package*` rule below wouldn't otherwise distinguish.
        // NOTE: the suffix is intentionally plural (`resources`) — the
        // singular `mergeReleaseJavaResource` falls through to the
        // Java/Kotlin bucket below where it belongs (jar-content
        // META-INF resources, not Android res/).
        Regex("^(merge|process|package|generate|compile)[A-Z].*?(resources|assets|resvalues|shaders|renderscript)$", RegexOption.IGNORE_CASE) to TaskCategory.RESOURCES,
        Regex("^crunch[A-Z].*", RegexOption.IGNORE_CASE) to TaskCategory.RESOURCES,
        Regex("^mapSourceSetPaths$", RegexOption.IGNORE_CASE) to TaskCategory.RESOURCES,
        Regex("^parseLibraryResources$", RegexOption.IGNORE_CASE) to TaskCategory.RESOURCES,
        Regex("^optimize[A-Z].*Resources$", RegexOption.IGNORE_CASE) to TaskCategory.RESOURCES,
        // Manifest processing (merging, per-package prep) — these can
        // take tens of seconds on large apps with many modules / feature
        // deliveries, so tracking them alongside resource processing is
        // the right grouping for "non-code input packaging".
        Regex("^process[A-Z].*Manifest(ForPackage)?$", RegexOption.IGNORE_CASE) to TaskCategory.RESOURCES,

        // --- Native ---------------------------------------------------
        Regex("^externalNative.*", RegexOption.IGNORE_CASE) to TaskCategory.NATIVE,
        Regex("^configureCMake.*", RegexOption.IGNORE_CASE) to TaskCategory.NATIVE,
        Regex("^buildCMake.*", RegexOption.IGNORE_CASE) to TaskCategory.NATIVE,
        // AGP emits `extract<Variant>NativeDebugMetadata` AND
        // `extract<Variant>NativeSymbolTables` depending on the
        // `android.ndkVersion` / DebugSymbolLevel setting. Scoped to
        // the known suffix alternatives so future unrelated
        // `extractNative<X>` tasks don't get dragged into NATIVE by
        // accident.
        Regex("^extract[A-Z].*Native(DebugMetadata|SymbolTables|DebugSymbols)$", RegexOption.IGNORE_CASE) to TaskCategory.NATIVE,
        Regex("^merge[A-Z].*(NativeLibs|JniLibFolders)$", RegexOption.IGNORE_CASE) to TaskCategory.NATIVE,
        Regex("^strip[A-Z].*?(Symbols|DebugSymbols)$", RegexOption.IGNORE_CASE) to TaskCategory.NATIVE,

        // --- Java / Kotlin bytecode pipeline --------------------------
        Regex("^(compile|kapt|ksp)[A-Z].*(Java|Kotlin|JavaWithJavac|JavaResources)$", RegexOption.IGNORE_CASE) to TaskCategory.MANAGED_CODE,
        Regex("^compile[A-Z].*(Aidl|Renderscript)$", RegexOption.IGNORE_CASE) to TaskCategory.MANAGED_CODE,
        Regex("^(merge|package)[A-Z].*JavaResource$", RegexOption.IGNORE_CASE) to TaskCategory.MANAGED_CODE,
        Regex("^desugar[A-Z].*", RegexOption.IGNORE_CASE) to TaskCategory.MANAGED_CODE,
        Regex("^(dex|mergeDex|minify).*", RegexOption.IGNORE_CASE) to TaskCategory.MANAGED_CODE,
        Regex("^(r8|proguard).*", RegexOption.IGNORE_CASE) to TaskCategory.MANAGED_CODE,

        // --- Packaging / signing -------------------------------------
        // Catch-all for packaging verbs. Placed last so the resources
        // and java-resources rules above claim their ownership first.
        Regex("^(package|bundle|sign|zip)[A-Z].*", RegexOption.IGNORE_CASE) to TaskCategory.PACKAGING,
        Regex("^makeApkFromBundle.*", RegexOption.IGNORE_CASE) to TaskCategory.PACKAGING
    )


    /**
     * @param taskPath Fully qualified Gradle task path
     *                 (e.g. `:app:compileReleaseKotlin`).
     * @return the matching category, or [TaskCategory.OTHER] when no
     *         rule matches.
     */
    fun classify(taskPath: String): TaskCategory {
        val basename = taskPath.substringAfterLast(':')
        if (basename.isEmpty()) return TaskCategory.OTHER
        for ((pattern, category) in RULES) {
            if (pattern.matches(basename)) return category
        }
        return TaskCategory.OTHER
    }
}
