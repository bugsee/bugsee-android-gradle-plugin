package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.bugsee.android.gradle.StartupTier
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import org.objectweb.asm.ClassVisitor

/**
 * ASM class visitor factory for app-startup tracing.
 *
 * Picks startup-relevant classes — `Application` and `ContentProvider`
 * subclasses, AndroidX `Initializer`s, Firebase `ComponentRegistrar`s,
 * and WorkManager `Configuration.Provider`s — and injects calls to
 * `BugseeAppStartupDispatcher` around their init-time methods. The
 * shape of those injections is selected per the configured tier:
 *
 *  - **OFF** — short-circuits in [createClassVisitor]; no bytecode is
 *    rewritten. Effectively a build-time no-op except for the cheap
 *    class-data probe.
 *  - **MINIMAL** — only the kind-based candidate method bodies are
 *    wrapped: `onMethodStart` + try / catch-any + `onMethodEnd` (via
 *    `MethodBodyWrapper`). One pair of dispatch calls per matched
 *    method.
 *  - **STANDARD** — MINIMAL plus per-call wraps on top-level
 *    `INVOKE*` instructions inside each candidate method (via
 *    `TopLevelCallWrapper`): each user call inside an init method emits
 *    its own `onCallStart` / `onCallEnd` pair, surfacing the per-callee
 *    breakdown inside the parent method span.
 *  - **DETAILED** — STANDARD plus per-loop wraps (via `LoopWrapper`):
 *    detected loop entries get their own `onLoopStart` / `onLoopEnd`
 *    pair so heavy init-time loops show up as nested spans rather than
 *    being absorbed into the surrounding method.
 *  - **FULL** — DETAILED plus `@BugseeTrace` annotation pickup on any
 *    non-denylisted class. The [isInstrumentable] gate widens to allow
 *    ANY non-denylisted class through; per-method, the visitor uses a
 *    lightweight pre-body peek to detect the annotation before
 *    buffering the body, so memory cost is bounded for classes whose
 *    methods are not annotated.
 *
 * Two gates select what gets transformed:
 *  - **[isInstrumentable]** is the AGP-side coarse filter: package
 *    prefix denylist short-circuits first, then kind classification on
 *    `superClasses` / `interfaces`. At FULL tier the result is widened
 *    to include any non-denylisted class so per-method annotation
 *    pickup can run on it.
 *  - **[createClassVisitor]** re-classifies kinds, picks the candidate
 *    method set, and instantiates the tier-aware
 *    [AppStartupTracingClassVisitor]. SDK-presence gating is NOT done
 *    here per-class — see [AppStartupTracingInstrumentation.shouldApply],
 *    which checks `bugsee-android` is declared AND meets the
 *    minimum version that ships `BugseeAppStartupDispatcher` once at
 *    configuration time. The previous per-class
 *    `ClassContext.loadClassData` probe was unreliable across AGP's
 *    artifact-transform isolation boundaries (it spuriously returned
 *    `null` for third-party JARs whose transform context didn't see
 *    the consumer's `:library` dep, causing non-deterministic skip of
 *    valid instrumentation targets like `androidx.startup.InitializationProvider`
 *    and `io.sentry.android.core.SentryInitProvider`).
 */
abstract class AppStartupTracingClassVisitorFactory :
    AsmClassVisitorFactory<AppStartupTracingParameters> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        val params = parameters.get()
        val dispatcherClassFqn = params.targetClass.get()

        val tier = resolveTier()
        if (tier == StartupTier.OFF) {
            return nextClassVisitor
        }

        // SDK presence is verified once at plugin apply() time via
        // [AppStartupTracingInstrumentation.shouldApply]'s
        // DependencyDetector.hasBugseeDependency check. We deliberately
        // do NOT re-probe per-class via [ClassContext.loadClassData]
        // here: AGP processes project classes and external-JAR classes
        // through DIFFERENT artifact-transform isolation boundaries,
        // each with its own classpath. Third-party JARs (e.g.
        // `androidx.startup`, `io.sentry.android.core`) run in
        // isolation boundaries where the consumer's `:library` dep is
        // NOT visible — a per-class probe would spuriously return
        // false for those JARs and silently skip valid instrumentation
        // targets, producing different waterfalls across otherwise
        // identical builds depending on AGP's transform-pipeline
        // ordering. The SDK + plugin are version-coupled and shipped
        // together (per the apply-time DependencyDetector contract);
        // SDK version skew would surface as a `NoClassDefFoundError`
        // at runtime with an obvious stack trace pointing at the
        // dispatcher FQN, which is sufficient.

        // Recompute class kinds for this specific class (isInstrumentable
        // was a coarse opt-in; we still need the kind set here to pick
        // the right candidate methods).
        //
        // Then gate the kind set by tier — at MINIMAL only APPLICATION
        // and CONTENT_PROVIDER are active; INITIALIZER /
        // COMPONENT_REGISTRAR / CONFIGURATION_PROVIDER kick in at
        // STANDARD and above. Filtering kinds (rather than methods)
        // means a class that ONLY qualifies as one of the STANDARD+
        // kinds gets `candidates.isEmpty()` at MINIMAL and bails out
        // cheaply at [shouldInstantiateVisitor] — no visitor allocation,
        // no per-method overhead.
        val current = classContext.currentClassData
        val kinds = kindsActiveAtTier(classifyKinds(current), tier)
        val candidates = StartupMethodFilter.candidateMethodsFor(kinds)

        if (!shouldInstantiateVisitor(candidates, tier)) {
            return nextClassVisitor
        }

        return AppStartupTracingClassVisitor(
            apiVersion = instrumentationContext.apiVersion.get(),
            nextClassVisitor = nextClassVisitor,
            candidateMethods = candidates,
            dispatcherInternalName = dispatcherClassFqn.replace('.', '/'),
            tier = tier,
        )
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        if (isInDenylist(classData.className)) {
            return false
        }
        val tier = resolveTier()
        if (kindsActiveAtTier(classifyKinds(classData), tier).isNotEmpty()) {
            return true
        }
        // FULL tier extends instrumentability to ANY non-denylisted
        // class so the visitor can scan for @BugseeTrace-annotated
        // methods. AGP's ClassData exposes class-level annotations
        // cheaply but NOT method-level ones, and @BugseeTrace is a
        // method/constructor target, so we can't pre-filter here.
        // The visitor will buffer methods at FULL tier and discard
        // the buffer for any method that turns out NOT to be
        // annotated. Build-time cost is bounded per-method (MethodNode
        // is GC'd as soon as visitEnd completes) but real in
        // aggregate. **Phase 9** plans to add a constant-pool
        // pre-scan optimization (skip classes whose raw bytecode
        // doesn't contain the literal string "BugseeTrace") to cut
        // this cost by 10-100× depending on annotation density.
        return tier.picksUpAnnotated()
    }

    /**
     * Resolves the [ClassKind] set for the given [classData].
     *
     * <p>AGP invokes the factory's [isInstrumentable] and
     * [createClassVisitor] back-to-back per class — both call this
     * method with what is functionally the same input, so a 1-element
     * per-thread cache hits ~100% on the second call and avoids
     * redoing the superclass/interface walks. AGP doesn't guarantee
     * the same [ClassData] instance across the two calls, so the
     * cache is keyed by [ClassData.className] (a stable string) rather
     * than by identity.
     *
     * <p>Static [ThreadLocal] rather than an instance field because
     * AGP/Gradle serializes [AsmClassVisitorFactory] instances across
     * the artifact-transform isolation boundary; an instance field
     * with non-Gradle-managed state breaks parameter isolation (same
     * bug class as the historical `by lazy` regression).
     */
    private fun classifyKinds(classData: ClassData): Set<ClassKind> {
        val className = classData.className
        val cached = sLastClassifyKindsCache.get()
        if (cached != null && cached.first == className) {
            return cached.second
        }
        val result = computeKinds(classData)
        sLastClassifyKindsCache.set(className to result)
        return result
    }

    private fun computeKinds(classData: ClassData): Set<ClassKind> {
        // AndroidX `InitializationProvider` is itself a `ContentProvider`
        // and is intentionally NOT excluded — its `onCreate` is the
        // umbrella span under which each user-defined
        // `Initializer.create()` (independently instrumented at
        // STANDARD+ via the INITIALIZER kind) appears as a nested
        // child. This matches Sentry's behavior and gives the startup
        // waterfall the proper parent/child structure for the
        // AndroidX-startup pass (and surfaces a span even when an app
        // pulls in `androidx.startup` transitively without registering
        // any Initializers itself).
        val kinds = HashSet<ClassKind>(2)
        val supers = classData.superClasses
        // superClasses is the transitive chain (immediate parent first,
        // through java.lang.Object). After Hilt's class transform runs
        // ahead of us, generated subclasses (Hilt_MyApp) still have
        // android.app.Application transitively, so both the user class
        // and the Hilt-generated parent are picked up — and they nest
        // naturally in the resulting span tree.
        if ("android.app.Application" in supers) {
            kinds += ClassKind.APPLICATION
        }
        if ("android.content.ContentProvider" in supers) {
            kinds += ClassKind.CONTENT_PROVIDER
        }
        val ifaces = classData.interfaces
        if ("androidx.startup.Initializer" in ifaces) {
            kinds += ClassKind.INITIALIZER
        }
        if ("com.google.firebase.components.ComponentRegistrar" in ifaces) {
            kinds += ClassKind.COMPONENT_REGISTRAR
        }
        if ("androidx.work.Configuration\$Provider" in ifaces) {
            kinds += ClassKind.CONFIGURATION_PROVIDER
        }
        return kinds
    }

    private fun isInDenylist(className: String): Boolean {
        for (prefix in PACKAGE_DENYLIST) {
            if (className.startsWith(prefix)) {
                return true
            }
        }
        return false
    }

    /**
     * Resolves the tier from the typed `Property<StartupTier>` parameter.
     *
     * Must NOT cache via `by lazy` — AGP serializes
     * [AsmClassVisitorFactory] instances across the artifact-transform
     * isolation boundary, and Kotlin's `Lazy<T>` is not
     * [java.io.Serializable] (it holds a captured lambda). A `by lazy`
     * field here breaks Gradle's "Could not isolate parameters
     * AsmClassesTransform$Parameters_Decorated" with "Could not serialize
     * value of type AppStartupTracingClassVisitorFactory".
     *
     * Inlining is cheap: AGP invokes `isInstrumentable` and
     * `createClassVisitor` thousands of times per build at FULL tier, but
     * each call here just reads a resolved Gradle [Property] — effectively
     * a hashmap lookup. Falls back to [StartupTier.DEFAULT] defensively
     * in case AGP ever invokes the factory without the parameter set
     * (the resolver always sets it at `apply(variant)`).
     */
    private fun resolveTier(): StartupTier =
        parameters.get().tier.orNull ?: StartupTier.DEFAULT

    private companion object {
        /**
         * Prefix denylist short-circuits [isInstrumentable] before
         * touching the superclass / interface lists, keeping build-time
         * cost low for the vast majority of classes (JDK, Kotlin runtime,
         * Android framework stubs, our own SDK code).
         */
        private val PACKAGE_DENYLIST = arrayOf(
            "com.bugsee.",
            "android.",
            "kotlin.",
            "kotlinx.",
            "java.",
            "androidx.compose.runtime.",
            // JVM / Dalvik internals — never legitimate instrumentation
            // targets. `dalvik.` covers ART/Dalvik runtime stubs that can
            // appear on the desugaring classpath; `sun.` and
            // `jdk.internal.` cover the JDK internals that R8/D8 may not
            // strip on every platform; `org.jetbrains.annotations.` covers
            // compiler-intrinsic nullability annotations bundled into many
            // Kotlin libraries (instrumenting an annotation declaration is
            // never useful and risks corrupting downstream toolchain
            // assumptions about annotation classes).
            "dalvik.",
            "sun.",
            "jdk.internal.",
            "org.jetbrains.annotations.",
        )

        /**
         * One-element per-thread cache for {@link #classifyKinds} —
         * stores the most recent (className → kinds) pair seen on the
         * current thread. AGP invokes [isInstrumentable] then
         * [createClassVisitor] back-to-back per class, both pass the
         * same logical input through [classifyKinds], so a tiny "last
         * one" cache hits ~100% on the second call. Cleared and
         * overwritten on every miss; never grows.
         *
         * ThreadLocal so concurrent AGP workers don't share state
         * (cleaner than a global ConcurrentHashMap, since the access
         * pattern is "recently-used in this thread" not "shared cache").
         */
        private val sLastClassifyKindsCache: ThreadLocal<Pair<String, Set<ClassKind>>?> =
            ThreadLocal()
    }
}

/**
 * Decides whether [AppStartupTracingClassVisitorFactory.createClassVisitor]
 * should instantiate an [AppStartupTracingClassVisitor] for a class with
 * the given kind-candidate methods + tier.
 *
 * Two paths trigger instantiation:
 *
 *  - **Kind-candidate path** — at least one kind is present and the
 *    candidate method set is non-empty. Always wraps regardless of
 *    tier (subject to tier predicates inside the visitor).
 *
 *  - **FULL-tier annotation-pickup path** — [candidates] may be empty
 *    (this is a non-kind class), but [StartupTier.picksUpAnnotated]
 *    is true, so the visitor must scan EVERY method for the
 *    `@BugseeTrace` descriptor. `candidateMethods` is empty in
 *    this case — the visitor's `visitMethod` correctly handles
 *    that: `isKindCandidate = false` plus `tier.picksUpAnnotated() =
 *    true` routes through the annotation-peek branch.
 *
 * If neither path applies (no kinds AND tier doesn't pick up annotated
 * methods), bail out cheaply. Tier `OFF` is already short-circuited
 * by the caller; this function does not need to handle it.
 *
 * Extracted into a top-level helper so it can be unit-tested without
 * mocking AGP's `AsmClassVisitorFactory` / `ClassContext` machinery.
 * Regression history: an earlier revision short-circuited the
 * annotation-pickup path by returning early on `candidates.isEmpty()`
 * — silently disabling `@BugseeTrace` on any non-kind class at FULL
 * tier, despite the documented behavior. See
 * `AppStartupTracingClassVisitorFactoryGatingTest`.
 */
internal fun shouldInstantiateVisitor(
    candidates: Set<MethodKey>,
    tier: StartupTier,
): Boolean = candidates.isNotEmpty() || tier.picksUpAnnotated()

/**
 * Returns the subset of [kinds] that are active at [tier], per the
 * tier ladder documented on [StartupTier]:
 *
 *  - [StartupTier.MINIMAL] covers `Application` + `ContentProvider`.
 *  - [StartupTier.STANDARD] and above additionally cover AndroidX
 *    `Initializer`, Firebase `ComponentRegistrar`, and WorkManager
 *    `Configuration.Provider`.
 *
 * Filtering kinds (rather than methods, or rather than gating per-kind
 * inside the visitor) means a class that ONLY qualifies for one of the
 * STANDARD+ kinds returns an empty candidate set at MINIMAL — no
 * [AppStartupTracingClassVisitor] is allocated and no per-method wrap
 * overhead is paid. Likewise [AsmClassVisitorFactory.isInstrumentable]
 * returns `false` for such classes at MINIMAL, so AGP doesn't even
 * route them through the factory.
 *
 * Extracted as a top-level internal function for the same reason as
 * [shouldInstantiateVisitor] — unit-testable without instantiating the
 * AGP machinery.
 *
 * Regression history: an earlier revision evaluated kinds without tier
 * gating, so MINIMAL silently paid the wrap cost for every Initializer
 * / ComponentRegistrar / Configuration.Provider on the classpath even
 * though the documented contract restricts MINIMAL to Application +
 * ContentProvider. See `AppStartupTracingClassVisitorFactoryGatingTest`.
 */
internal fun kindsActiveAtTier(
    kinds: Set<ClassKind>,
    tier: StartupTier,
): Set<ClassKind> {
    if (kinds.isEmpty()) return kinds
    // STANDARD and above: all kinds are active. Hot path; return the
    // same Set reference so the caller's downstream code (membership
    // tests) hits the immutable per-kind table without an extra copy.
    if (tier >= StartupTier.STANDARD) return kinds
    // MINIMAL (and OFF, though OFF is short-circuited by the caller):
    // keep only the kinds covered by the MINIMAL contract. Iterate
    // once; the kind set has at most 5 elements so the overhead is
    // trivial.
    var minimal: HashSet<ClassKind>? = null
    for (k in kinds) {
        if (k == ClassKind.APPLICATION || k == ClassKind.CONTENT_PROVIDER) {
            if (minimal == null) minimal = HashSet(2)
            minimal.add(k)
        }
    }
    return minimal ?: emptySet()
}
