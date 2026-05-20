package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.bugsee.android.gradle.StartupTier
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.Attribute
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.TypePath
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.MethodNode

/**
 * Class-level visitor for app-startup tracing. Tier-aware: dispatches
 * each candidate method through the layer stack permitted by the
 * resolved [StartupTier].
 *
 * Receives — from the factory — the set of kind-based [MethodKey]s this
 * class qualifies for ([StartupMethodFilter.candidateMethodsFor] applied
 * to the class's [ClassKind] set), the resolved tier, and the
 * dispatcher's internal name. Per-method dispatch:
 *
 *  - **Kind-candidate (Application / ContentProvider / Initializer /
 *    ComponentRegistrar / Configuration.Provider init methods)** —
 *    buffer the entire body into an ASM [MethodNode] via
 *    [buildKindCandidateBufferingVisitor]. At `visitEnd` apply the
 *    layer stack permitted by the tier, in increasing scope order:
 *      * STANDARD+: [TopLevelCallWrapper.wrap] — per-`INVOKE*` start/end
 *        wraps with per-call try-catch.
 *      * DETAILED+: [LoopWrapper.wrap] — per-top-level-loop start/end
 *        wraps with per-loop try-catch.
 *      * MINIMAL+: [MethodBodyWrapper.wrap] — whole-body start/end
 *        wrap with the outermost catch-any (must run LAST; this is the
 *        invariant enforced by `MethodBodyWrapper`'s layer-ordering
 *        guard).
 *    Then `accept(downstream)` replays the rewritten method.
 *
 *  - **FULL-tier `@BugseeTrace`-annotated method (non-kind)** —
 *    `buildAnnotationPeekVisitor` buffers pre-body events as small
 *    lambdas (with [AnnotationNode] capturing annotation values for
 *    fidelity-preserving replay) until `visitCode` arrives. At that
 *    point, if `@BugseeTrace` was observed during the peek, a full
 *    [MethodNode] is spun up and buffered events replay into it; the
 *    body buffers, gets [MethodBodyWrapper.wrap] applied at `visitEnd`,
 *    and `accept(downstream)`. If no annotation, the peek visitor
 *    flushes its buffer to `downstream` directly and the body streams
 *    through unwrapped.
 *
 *  - **Non-candidate at lower tier (`!isKindCandidate &amp;&amp;
 *    !tier.picksUpAnnotated()`)** — pure pass-through to the next
 *    visitor. Zero buffering, zero allocations.
 *
 * Abstract / native / suspend / synthetic / bridge methods always
 * stream through unmodified — they either have no body to wrap or
 * (for synthetic / bridge) are not the right target even if their
 * `(name, descriptor)` collides with a kind candidate.
 */
internal class AppStartupTracingClassVisitor(
    apiVersion: Int,
    nextClassVisitor: ClassVisitor,
    private val candidateMethods: Set<MethodKey>,
    private val dispatcherInternalName: String,
    private val tier: StartupTier,
) : ClassVisitor(apiVersion, nextClassVisitor) {

    private var ownerInternalName: String = ""

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        ownerInternalName = name ?: ""
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        val downstream = super.visitMethod(access, name, descriptor, signature, exceptions)
            ?: return null

        val key = MethodKey(name, descriptor)
        val isKindCandidate = key in candidateMethods
        // Skip mask: abstract / native have no body; suspend has a body
        // but its descriptor reshapes the method into a continuation-
        // passing form that doesn't match the kind-based candidate set
        // anyway. Synthetic / bridge methods are also skipped — the
        // kind-based candidate set targets concrete user-authored
        // overrides (Application.onCreate, ContentProvider.attachInfo,
        // etc.); a generic-erasure bridge that happens to share a
        // (name, descriptor) with a candidate is the wrong target.
        // FULL-tier @BugseeTrace-annotated methods bypass the candidate
        // set but still go through this skip mask: a user explicitly
        // annotating a synthetic method is unusual enough that we
        // prefer the conservative "skip" over surprise instrumentation.
        val skipBodySharedReasons = StartupMethodFilter.isSuspendDescriptor(descriptor)
                || (access and (Opcodes.ACC_ABSTRACT
                                or Opcodes.ACC_NATIVE
                                or Opcodes.ACC_SYNTHETIC
                                or Opcodes.ACC_BRIDGE)) != 0

        // Fast-path: not a candidate AND FULL-tier annotation pickup
        // is disabled (lower tier) → pure streaming pass-through, no
        // buffering, no annotation scan.
        if (!isKindCandidate && !tier.picksUpAnnotated()) {
            return downstream
        }
        // `skipBodySharedReasons` takes precedence over kind-candidate
        // membership. A method that matches a candidate name+descriptor
        // but is abstract / native / suspend has no body we can wrap,
        // so it streams through. The kind-based candidate set is
        // ordinarily concrete, but a `(name, descriptor)` collision
        // on an abstract base class is still possible — concrete
        // subclasses get wrapped normally.
        if (skipBodySharedReasons) {
            return downstream
        }

        val siteId = SiteIdGenerator.forMethod(ownerInternalName, name)
        val dispatcher = dispatcherInternalName
        val ownerNameForLoops = ownerInternalName
        val apiVersion = this.api
        val currentTier = tier

        // Kind-candidate: we already know we'll wrap regardless of
        // annotations. Buffer the entire method to a MethodNode and run
        // the tier-appropriate layer stack at visitEnd. Pick the
        // kind-specific dispatcher entry points so the resulting span
        // folds into the right `app.startup.<kind>` operation on the
        // SDK side (issue 2 — distinct dashboard row labels per source).
        if (isKindCandidate) {
            val classKind = StartupMethodFilter.kindForMethodKey(key)
            val (startName, endName) = dispatchMethodNamesFor(classKind)
            return buildKindCandidateBufferingVisitor(
                downstream = downstream,
                apiVersion = apiVersion,
                access = access,
                methodName = name,
                descriptor = descriptor,
                signature = signature,
                exceptions = exceptions,
                siteId = siteId,
                dispatcher = dispatcher,
                ownerNameForLoops = ownerNameForLoops,
                currentTier = currentTier,
                startMethodName = startName,
                endMethodName = endName,
            )
        }

        // FULL-tier annotation pickup for a non-kind method: peek for
        // `@BugseeTrace` BEFORE buffering the method body. The peek
        // visitor records pre-body events as small lambdas (and
        // annotation values via [AnnotationNode]) until visitCode
        // arrives. At that point it knows whether the method is annotated
        // and either:
        //   * spins up a full MethodNode and replays the buffered
        //     events into it (annotation present) — body is then
        //     buffered for the wrap; OR
        //   * replays the pre-body events to `downstream` and sets `mv
        //     = downstream` (no annotation) — body streams through.
        //
        // For a 10K-class app with ~100 methods/class average and a
        // sparse `@BugseeTrace` usage pattern, this cuts FULL-tier
        // peak heap pressure from ~5-10 GB (MethodNode per method) to
        // ~50-100 MB (small annotation lambda buffer per method).
        // The optimization is class-internal — no plugin-level constant-
        // pool pre-scan is feasible because AGP's instrumentation API
        // does not expose raw class bytes to a `ClassVisitorFactory`.
        return buildAnnotationPeekVisitor(
            downstream = downstream,
            apiVersion = apiVersion,
            access = access,
            methodName = name,
            descriptor = descriptor,
            signature = signature,
            exceptions = exceptions,
            siteId = siteId,
            dispatcher = dispatcher,
        )
    }

    /**
     * Builds the buffering visitor for kind-candidate methods (Application
     * etc.). Always buffers the full body into a [MethodNode] and runs
     * the tier-appropriate layer stack at [MethodNode.visitEnd].
     */
    private fun buildKindCandidateBufferingVisitor(
        downstream: MethodVisitor,
        apiVersion: Int,
        access: Int,
        methodName: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
        siteId: String,
        dispatcher: String,
        ownerNameForLoops: String,
        currentTier: StartupTier,
        startMethodName: String,
        endMethodName: String,
    ): MethodVisitor {
        return object : MethodNode(apiVersion, access, methodName, descriptor, signature, exceptions) {
            override fun visitEnd() {
                super.visitEnd()
                // Layers run in increasing scope order: per-call wraps
                // first (smallest scope), then per-loop wraps, then the
                // whole-method wrap. Each layer inspects
                // mn.tryCatchBlocks at the time it runs to decide what's
                // "already protected" — running them in this order keeps
                // each layer's view of the existing try-catch table
                // consistent with its own intent.
                if (currentTier.wrapsCalls()) {
                    TopLevelCallWrapper.wrap(this, dispatcher)
                }
                if (currentTier.wrapsLoops()) {
                    LoopWrapper.wrap(this, ownerNameForLoops, dispatcher)
                }
                MethodBodyWrapper.wrap(
                    this, siteId, dispatcher,
                    startMethodName, endMethodName,
                )
                accept(downstream)
            }
        }
    }

    /**
     * Builds the peek visitor for FULL-tier annotation pickup on non-kind
     * methods. Buffers pre-body events as deferred lambdas, resolves at
     * the first visitCode (or visitEnd for body-less methods), and either
     * upgrades to a full MethodNode buffer (annotation found, will be
     * wrapped) or passes everything through to `downstream` (no
     * annotation, no wrap).
     *
     * Pre-body events buffered (in ASM's visit order):
     *  - visitParameter
     *  - visitAnnotationDefault
     *  - visitAnnotation (the one we peek at)
     *  - visitTypeAnnotation
     *  - visitAnnotableParameterCount
     *  - visitParameterAnnotation
     *  - visitAttribute
     *
     * Each event captures its arguments + an optional [AnnotationNode]
     * (for the `AnnotationVisitor`-returning ones) so values are
     * preserved on replay. Memory cost per method: ~50-200 bytes plus
     * the annotation arg list, vs ~5-10 KB for a buffered MethodNode.
     */
    private fun buildAnnotationPeekVisitor(
        downstream: MethodVisitor,
        apiVersion: Int,
        access: Int,
        methodName: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
        siteId: String,
        dispatcher: String,
    ): MethodVisitor {
        return object : MethodVisitor(apiVersion) {
            private val pendingEvents = ArrayList<(MethodVisitor) -> Unit>(2)
            private var hasTraceAnnotation = false
            private var bufferingNode: MethodNode? = null
            private var resolved = false

            override fun visitParameter(parameterName: String?, parameterAccess: Int) {
                pendingEvents.add { it.visitParameter(parameterName, parameterAccess) }
            }

            override fun visitAnnotationDefault(): AnnotationVisitor {
                val node = AnnotationNode(apiVersion, "")
                pendingEvents.add { target ->
                    val sink = target.visitAnnotationDefault()
                    if (sink != null) node.accept(sink)
                }
                return node
            }

            override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor {
                if (desc == BUGSEE_TRACE_DESCRIPTOR) {
                    hasTraceAnnotation = true
                }
                val node = AnnotationNode(apiVersion, desc)
                pendingEvents.add { target ->
                    val sink = target.visitAnnotation(desc, visible)
                    if (sink != null) node.accept(sink)
                }
                return node
            }

            override fun visitTypeAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                desc: String,
                visible: Boolean
            ): AnnotationVisitor {
                val node = AnnotationNode(apiVersion, desc)
                pendingEvents.add { target ->
                    val sink = target.visitTypeAnnotation(typeRef, typePath, desc, visible)
                    if (sink != null) node.accept(sink)
                }
                return node
            }

            override fun visitAnnotableParameterCount(parameterCount: Int, visible: Boolean) {
                pendingEvents.add { it.visitAnnotableParameterCount(parameterCount, visible) }
            }

            override fun visitParameterAnnotation(
                parameter: Int,
                desc: String,
                visible: Boolean
            ): AnnotationVisitor {
                val node = AnnotationNode(apiVersion, desc)
                pendingEvents.add { target ->
                    val sink = target.visitParameterAnnotation(parameter, desc, visible)
                    if (sink != null) node.accept(sink)
                }
                return node
            }

            override fun visitAttribute(attribute: Attribute) {
                pendingEvents.add { it.visitAttribute(attribute) }
            }

            private fun resolve() {
                if (resolved) return
                resolved = true

                if (hasTraceAnnotation) {
                    val node = MethodNode(
                        apiVersion, access, methodName, descriptor, signature, exceptions
                    )
                    bufferingNode = node
                    for (event in pendingEvents) event(node)
                    mv = node
                } else {
                    mv = downstream
                    for (event in pendingEvents) event(downstream)
                }
                // Free the buffer — body events go directly to mv from
                // here on, and visitEnd just routes through the resolved
                // delegate.
                pendingEvents.clear()
                pendingEvents.trimToSize()
            }

            override fun visitCode() {
                resolve()
                super.visitCode()
            }

            override fun visitEnd() {
                // Ensure resolve fires even for body-less paths. In
                // practice every method that reaches this visitor has a
                // body (abstract / native / suspend / synthetic / bridge
                // were filtered upstream), but defensive resolve() here
                // keeps the contract simple: this visitor always emits a
                // complete method to `downstream` on visitEnd, no matter
                // what intermediate events it saw.
                resolve()

                // Two paths converge here:
                //
                // 1. **Pass-through (no annotation)** — `mv` is already
                //    `downstream`. All buffered pre-body events and the
                //    body itself were forwarded directly. We forward
                //    visitEnd through `super.visitEnd()` to complete the
                //    downstream method visit. Single visitEnd, correct.
                //
                // 2. **Buffering (annotation found)** — `mv` is the
                //    buffering `MethodNode`. We must NOT call
                //    `super.visitEnd()` here: that would call
                //    `node.visitEnd()` (fine on its own) but then the
                //    `node.accept(downstream)` below replays the FULL
                //    buffered method to downstream — including
                //    `downstream.visitEnd()` at the tail of `accept`.
                //    Downstream would receive visitEnd twice. ClassWriter
                //    tolerates this in current ASM; chained visitors
                //    (e.g. CheckClassAdapter) do not.
                //
                // For the buffering branch we explicitly finalize the
                // node (its own visitEnd is a no-op AbstractInsnVisitor-
                // inherited terminator but kept for ASM-contract clarity),
                // apply the body wrap, and then accept-replay to
                // downstream. Downstream's visitEnd fires exactly once,
                // from inside accept.
                val node = bufferingNode
                if (node == null) {
                    super.visitEnd()
                } else {
                    node.visitEnd()
                    // FULL-tier @BugseeTrace pickup uses the dedicated
                    // onAnnotatedStart/End dispatcher pair so the
                    // resulting span folds into `app.startup.annotated`
                    // on the SDK side — distinct from the kind-based
                    // wraps' app.startup.{provider,application,...}.
                    MethodBodyWrapper.wrap(
                        node, siteId, dispatcher,
                        ANNOTATED_START_METHOD, ANNOTATED_END_METHOD,
                    )
                    node.accept(downstream)
                }
            }
        }
    }

    private companion object {
        /**
         * JVM descriptor form of
         * `com.bugsee.library.contracts.performance.BugseeTrace`.
         *
         * **CROSS-REPO COUPLING.** This constant must match the FQN of
         * the SDK file
         * `library/src/main/java/com/bugsee/library/contracts/performance/BugseeTrace.java`.
         * Renaming or moving the annotation in the SDK requires
         * updating this constant in the same paired release. The
         * coupling is descriptor-based (string match against bytecode),
         * not classpath-based, so the plugin's runtime never has to
         * load the SDK annotation class.
         *
         * Also see `gradle_plugin_coupling.md` in the SDK's memory
         * directory — this constant is part of the canonical
         * cross-repo-coupling list alongside the dispatcher class FQN.
         */
        private const val BUGSEE_TRACE_DESCRIPTOR =
            "Lcom/bugsee/library/contracts/performance/BugseeTrace;"

        /**
         * Dispatcher entry-point method names emitted as the static-call
         * target of every plugin-injected INVOKESTATIC.
         *
         * <p>**CROSS-REPO COUPLING.** Every name below must match an
         * `@Keep`-annotated static method on
         * `library/src/main/java/com/bugsee/library/adapters/BugseeAppStartupDispatcher.java`
         * with the exact signature {@code (Ljava/lang/String;)V}. Renaming
         * or removing any of those methods on the SDK side requires a
         * paired plugin release that updates these constants. The
         * coupling is descriptor-based (string match against bytecode),
         * not classpath-based — the plugin's runtime never loads the SDK
         * dispatcher class.
         *
         * <p>{@code APPLICATION_*} / {@code PROVIDER_*} fan out to the
         * kind-specific {@code app.startup.application} /
         * {@code app.startup.provider} operations on the SDK side
         * (issue 2 — distinct dashboard rows per source category).
         * {@code ANNOTATED_*} is used by FULL-tier {@code @BugseeTrace}
         * pickup → {@code app.startup.annotated}.
         * {@code METHOD_*} is the generic fallback for kinds without a
         * dedicated variant (Initializer / ComponentRegistrar /
         * Configuration.Provider) and for the defensive {@code null}
         * branch in {@link #dispatchMethodNamesFor} → {@code app.startup.method}.
         *
         * <p>{@code Activity} dispatcher entry points exist on the SDK
         * side ({@code onActivityStart} / {@code onActivityEnd}) but are
         * NOT emitted by the plugin — the SDK's {@code StartupLifecycleTracker}
         * self-emits them directly via the dispatcher API at runtime.
         */
        private const val APPLICATION_START_METHOD = "onApplicationStart"
        private const val APPLICATION_END_METHOD = "onApplicationEnd"
        private const val PROVIDER_START_METHOD = "onProviderStart"
        private const val PROVIDER_END_METHOD = "onProviderEnd"
        private const val ANNOTATED_START_METHOD = "onAnnotatedStart"
        private const val ANNOTATED_END_METHOD = "onAnnotatedEnd"
        private const val METHOD_START_METHOD = "onMethodStart"
        private const val METHOD_END_METHOD = "onMethodEnd"

        /**
         * Maps a kind-candidate's {@link ClassKind} to its specific
         * dispatcher entry-point pair, so the bytecode wrap routes
         * each Application/ContentProvider/etc. to a distinct
         * {@code app.startup.<kind>} operation name on the SDK side
         * — giving the dashboard waterfall distinct row labels per
         * source category (issue 2).
         *
         * <p>Kinds without a dedicated variant ({@code INITIALIZER},
         * {@code COMPONENT_REGISTRAR}, {@code CONFIGURATION_PROVIDER})
         * fall back to {@code onMethodStart/End} → {@code app.startup.method}
         * (preserves the pre-issue-2 behavior for those kinds).
         *
         * <p>The {@code null} branch is defensive — in production the
         * candidate set passed to the visitor is built from the per-kind
         * tables in {@link StartupMethodFilter}, so every candidate
         * {@link MethodKey} resolves to a {@link ClassKind}. The
         * {@code null} fallthrough exists so a future bug that feeds an
         * arbitrary {@code MethodKey} (e.g. from a test) routes through
         * the generic pair rather than NPE-ing here.
         */
        fun dispatchMethodNamesFor(kind: ClassKind?): Pair<String, String> {
            return when (kind) {
                ClassKind.APPLICATION ->
                    APPLICATION_START_METHOD to APPLICATION_END_METHOD
                ClassKind.CONTENT_PROVIDER ->
                    PROVIDER_START_METHOD to PROVIDER_END_METHOD
                // Three real fallback kinds plus the defensive null arm
                // (kindForMethodKey returned null for a candidate — should
                // not happen in production, but we'd rather route through
                // the generic pair than crash).
                ClassKind.INITIALIZER,
                ClassKind.COMPONENT_REGISTRAR,
                ClassKind.CONFIGURATION_PROVIDER,
                null ->
                    METHOD_START_METHOD to METHOD_END_METHOD
            }
        }
    }
}
