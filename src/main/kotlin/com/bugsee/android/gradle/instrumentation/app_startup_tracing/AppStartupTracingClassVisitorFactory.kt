package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.bugsee.android.gradle.StartupTier
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import org.objectweb.asm.ClassVisitor
import java.util.concurrent.ConcurrentHashMap

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
 *  - **[createClassVisitor]** is the SDK-side fine filter: refuses to
 *    emit bytecode that references `BugseeAppStartupDispatcher` if
 *    that class is missing from the runtime classpath visible to the
 *    current AGP transform context. Misses are silent — they happen
 *    legitimately for transitive third-party classes processed via
 *    AGP's artifact-transform isolation boundary where the consumer's
 *    `:library` dep isn't on the classpath. After this gate the
 *    factory re-classifies kinds, picks the candidate method set,
 *    and instantiates the tier-aware [AppStartupTracingClassVisitor].
 *    The bare class-name check via [ClassContext.loadClassData] is
 *    intentionally simple — no version parsing, just "is the type
 *    resolvable?".
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

        // SDK presence probe — refuse to inject calls against a class
        // that is not on the runtime classpath. Without this, an older
        // SDK paired with this plugin would produce NoClassDefFoundError
        // at every wrapped method's first call.
        //
        // The probe is a per-class no-op once we've seen the SDK at
        // least once on this dispatcher FQN (cached in the static
        // [sSdkPresenceConfirmed]); see [isSdkPresent]'s KDoc for the
        // PRESENT-only caching rationale.
        //
        // Misses are silent. They legitimately happen for transitive
        // third-party JAR classes (`androidx.viewbinding.*`,
        // `androidx.databinding.*`, `org.intellij.lang.annotations.*`,
        // etc.) at FULL tier when AGP processes them via the artifact-
        // transform isolation boundary — the dispatcher class lives in
        // the consumer's `:library` dep which is not on the artifact
        // transform's classpath. Skipping those is correct (we never
        // want to instrument arbitrary `androidx.*` code anyway) but
        // they're indistinguishable at probe time from a genuine
        // SDK-absent build. If the SDK is truly missing the plugin
        // simply emits no instrumentation calls and the user notices
        // the empty waterfall — a `NoClassDefFoundError` would have
        // been louder but is not needed for any current consumer
        // scenario (the SDK + plugin are version-coupled and shipped
        // together).
        if (!isSdkPresent(classContext, dispatcherClassFqn)) {
            return nextClassVisitor
        }

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
        // — its `onCreate` / `attachInfo` would match the
        // CONTENT_PROVIDER kind set. But that same method calls each
        // user-defined `Initializer.create()` which is independently
        // instrumented at STANDARD+ via the INITIALIZER kind. Wrapping
        // both yields a double-counted parent span for the entire
        // AndroidX-startup pass. Skip the AndroidX provider itself by
        // exact FQN so user-defined `Initializer` instrumentation stays
        // intact and the startup waterfall remains clean.
        if (classData.className == "androidx.startup.InitializationProvider") {
            return emptySet()
        }
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

    /**
     * Returns whether the dispatcher class is on the runtime classpath
     * visible to the current AGP transform context. Probes via
     * {@link ClassContext#loadClassData(String)} on each call until a
     * PRESENT result is observed; thereafter, the result is cached in
     * the static [sSdkPresenceConfirmed] and the probe is skipped.
     *
     * <p><b>Why we only cache PRESENT, not MISSING.</b> AGP processes
     * project classes and external-JAR classes via DIFFERENT classpath
     * contexts. The dispatcher class lives in the consumer's
     * `:library` dep, which is visible from the {@code :app} module's
     * own class-transform context but NOT from the artifact transforms
     * applied to transitive third-party JARs (e.g. {@code androidx.*}
     * classes). At FULL tier, [isInstrumentable] passes any non-
     * denylisted class through to {@link #createClassVisitor} so that
     * the visitor can scan for {@code @BugseeTrace} method
     * annotations — but the probe will legitimately return {@code null}
     * for the third-party path. Skipping those classes is the right
     * call (we never want to instrument arbitrary external code), so
     * a MISSING cache would be wrong: it would lock in the third-party
     * answer and skip project classes too.
     *
     * <p>Caching PRESENT is still useful: once we see the SDK from any
     * context (typically the {@code :app} module's own transform), all
     * subsequent probes for the same FQN short-circuit on the fast
     * path. The cost on a real SDK-absent build is one
     * {@link ClassContext#loadClassData} call per visited class —
     * bounded and not catastrophic, and the eventual symptom on the
     * runtime side is a clearly diagnosable "no startup spans in the
     * waterfall".
     *
     * <p><b>Why the cache is static, not per-instance.</b> AGP/Gradle
     * serializes [AsmClassVisitorFactory] instances across the
     * artifact-transform isolation boundary; an instance field with
     * non-Gradle-managed state breaks the serialization with
     * "Could not isolate parameters AsmClassesTransform$Parameters" —
     * the same bug class as the historical `by lazy` regression (see
     * [resolveTier]'s KDoc). Static fields are exempt.
     *
     * <p>Concurrent-safe: AGP can invoke {@link #createClassVisitor}
     * across threads when processing project classes in parallel.
     * Reading {@link Map#containsKey} on a {@link ConcurrentHashMap} is
     * lock-free; on first PRESENT, multiple threads may all write the
     * same {@code true} value, which is harmless.
     */
    private fun isSdkPresent(
        classContext: ClassContext,
        dispatcherClassFqn: String,
    ): Boolean {
        if (sSdkPresenceConfirmed.containsKey(dispatcherClassFqn)) {
            return true
        }
        val present = classContext.loadClassData(dispatcherClassFqn) != null
        if (present) {
            sSdkPresenceConfirmed[dispatcherClassFqn] = true
        }
        return present
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
         * Set of dispatcher FQNs confirmed PRESENT on the runtime
         * classpath. Used as the "stop probing" signal in
         * [isSdkPresent]; only PRESENT results are cached so that a
         * transient AGP class-data race on the first class probed
         * doesn't poison the entire build (see [isSdkPresent]'s KDoc
         * for the full rationale).
         *
         * Modeled as {@code ConcurrentHashMap<String, Boolean>} rather
         * than a {@link java.util.concurrent.ConcurrentHashMap#newKeySet
         * set} for Java 8 compatibility — newKeySet only became
         * available as a default-Set type in newer JDKs and the project
         * targets older Gradle daemons too.
         *
         * Static rather than per-instance: AGP creates a new factory
         * instance per variant + per artifact-transform isolation
         * boundary, and a mutable instance field would (a) re-probe per
         * variant unnecessarily and (b) crash Gradle's parameter-
         * isolation serialization with NotSerializableException — the
         * same bug class as the historical `by lazy` regression.
         */
        private val sSdkPresenceConfirmed: ConcurrentHashMap<String, Boolean> = ConcurrentHashMap()

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
