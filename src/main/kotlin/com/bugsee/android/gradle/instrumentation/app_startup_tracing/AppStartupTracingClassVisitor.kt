package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.MethodNode

/**
 * Class-level visitor for the MINIMAL tier of app-startup tracing.
 *
 * Receives — from the factory — the set of [MethodKey]s this particular
 * class qualifies for ([StartupMethodFilter.candidateMethodsFor] applied
 * to the class's [ClassKind] set). For each matching method visited:
 *
 *  1. Buffer the method body into an ASM [MethodNode];
 *  2. On the method's `visitEnd`, run [MethodBodyWrapper.wrap] to inject
 *     the dispatch calls + outer catch-any handler;
 *  3. Emit the buffered, transformed MethodNode through to the next
 *     visitor (the ClassWriter).
 *
 * Non-matching methods are streamed straight through to the next visitor
 * with no buffering / no transformation, so the throughput cost is paid
 * only for the handful of init methods this transform actually targets.
 *
 * Suspend and abstract / native methods are skipped even if their (name,
 * descriptor) is in the candidate set — they have no body that the
 * MINIMAL-tier wrap can sensibly act on.
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

        // Buffer-and-transform path. At FULL tier with a non-kind
        // candidate, the buffered MethodNode is only wrapped if its
        // invisibleAnnotations contains @BugseeTrace; otherwise it
        // accepts straight to the writer.
        val siteId = SiteIdGenerator.forMethod(ownerInternalName, name)
        val dispatcher = dispatcherInternalName
        val ownerNameForLoops = ownerInternalName
        val api = this.api
        val currentTier = tier
        val kindCandidate = isKindCandidate
        return object : MethodNode(api, access, name, descriptor, signature, exceptions) {
            override fun visitEnd() {
                super.visitEnd()

                // Decide whether THIS specific method gets wrapped.
                val wrap = kindCandidate ||
                        (currentTier.picksUpAnnotated() && hasBugseeTraceAnnotation(this))
                if (!wrap) {
                    accept(downstream)
                    return
                }

                if (kindCandidate) {
                    // The kind-based path: apply the full layer stack
                    // permitted by the current tier (calls, loops, then
                    // whole-method body). Layers run in increasing scope
                    // order: per-call wraps first (smallest scope), then
                    // per-loop wraps, then the whole-method wrap. Each
                    // layer inspects mn.tryCatchBlocks at the time it
                    // runs to decide what's "already protected" —
                    // running them in this order keeps each layer's view
                    // of the existing try-catch table consistent with
                    // its own intent.
                    if (currentTier.wrapsCalls()) {
                        TopLevelCallWrapper.wrap(this, dispatcher)
                    }
                    if (currentTier.wrapsLoops()) {
                        // LoopWrapper runs AFTER TopLevelCallWrapper so
                        // the calls inside loop bodies are already
                        // wrapped. The loop's own try-catch is then
                        // added on top — it shows up as an outer event
                        // around the per-call events on the dashboard.
                        LoopWrapper.wrap(this, ownerNameForLoops, dispatcher)
                    }
                    MethodBodyWrapper.wrap(this, siteId, dispatcher)
                } else {
                    // FULL-tier annotation pickup: method-body wrap
                    // ONLY (no call wraps, no loop wraps) regardless
                    // of tier. The annotation is an opt-in marker for
                    // an individual method; if the user wants finer
                    // granularity they can pick STANDARD/DETAILED at
                    // the tier level for kind-based classes. This
                    // keeps the cost of opt-in tracing predictable.
                    MethodBodyWrapper.wrap(this, siteId, dispatcher)
                }
                accept(downstream)
            }
        }
    }

    /**
     * Looks for the {@code @BugseeTrace} annotation among a method's
     * annotations. We match by descriptor string rather than by
     * classpath-resolved type so the plugin's runtime never needs to
     * load the SDK's annotation class.
     *
     * Both `invisibleAnnotations` (CLASS-retention — the current
     * setting in the SDK's `BugseeTrace.java`) and `visibleAnnotations`
     * (RUNTIME-retention) are checked. This is defense-in-depth: if a
     * future SDK change ever promotes the annotation to RUNTIME
     * retention (e.g. for reflection-based introspection), we still
     * pick it up here without a paired plugin release. Cost is
     * trivial — both lists are usually `null`.
     */
    private fun hasBugseeTraceAnnotation(methodNode: MethodNode): Boolean {
        if (containsBugseeTrace(methodNode.invisibleAnnotations)) return true
        if (containsBugseeTrace(methodNode.visibleAnnotations)) return true
        return false
    }

    private fun containsBugseeTrace(annotations: List<AnnotationNode>?): Boolean {
        if (annotations == null) return false
        for (a in annotations) {
            if (a.desc == BUGSEE_TRACE_DESCRIPTOR) return true
        }
        return false
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
    }
}
