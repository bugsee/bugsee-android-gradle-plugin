package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.bugsee.android.gradle.instrumentation.app_startup_tracing.cfg.Dominators
import com.bugsee.android.gradle.instrumentation.app_startup_tracing.cfg.MethodCfg
import com.bugsee.android.gradle.instrumentation.app_startup_tracing.cfg.NaturalLoop
import com.bugsee.android.gradle.instrumentation.app_startup_tracing.cfg.NaturalLoops
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TryCatchBlockNode

/**
 * DETAILED-tier addition: wraps every top-level natural loop in
 * [methodNode] with a single `onLoopStart` / `onLoopEnd` pair (per loop,
 * not per iteration).
 *
 * **Detection** delegates to [MethodCfg] / [Dominators] / [NaturalLoops]
 * — exception edges are deliberately excluded from the CFG (see
 * [MethodCfg]'s class doc for why; the short version is that
 * `synchronized` and try-catch handlers would otherwise manufacture
 * fake back-edges).
 *
 * **Filtering** keeps only top-level loops ([NaturalLoops.filterTopLevel])
 * and skips:
 *  - Methods whose descriptor matches a Kotlin `suspend` shape (the
 *    state-machine dispatcher produces back-edges that aren't user
 *    loops; instrumenting them yields nonsense events). The descriptor
 *    check is the SAME one [StartupMethodFilter] uses upstream — this
 *    is a belt-and-suspenders check.
 *  - Loops whose `[minIndex, maxIndex]` range is **non-contiguous** —
 *    i.e. there's some instruction in that range that isn't in the
 *    loop body. The hammock-region wrap below relies on the body
 *    occupying a contiguous slice; non-contiguous loops (rare in
 *    init-time code; can occur with unusual control flow like
 *    `break` to a target before the loop) get skipped with no event
 *    wrap. The method itself is still wrapped by the MINIMAL/STANDARD
 *    layers.
 *
 * **Wrap shape** per loop:
 * ```
 *   <code before loop>
 *   LDC siteId
 *   INVOKESTATIC dispatcher.onLoopStart(String)V
 *   <try-start label>
 *   <loop body — instructions [minIndex..maxIndex]>
 *   <try-end label>
 *   <... rest of method body, including the exit-target instructions ...>
 *
 *   ;; At each unique exit target (instruction outside the loop body
 *   ;; that's targeted by an edge from inside the body):
 *   <exit-target label>
 *   LDC siteId
 *   INVOKESTATIC dispatcher.onLoopEnd(String)V
 *   <original instruction at exit target>
 *
 *   ;; Appended once at the END of the method body (unreachable from
 *   ;; normal flow; only reached via the catch-any handler dispatch):
 *   <handler label>
 *   LDC siteId
 *   INVOKESTATIC dispatcher.onLoopEnd(String)V
 *   ATHROW
 * ```
 *
 * The `onLoopEnd` insertions BEFORE each exit-target instruction work
 * because jump targets are LabelNodes (pseudo-instructions); inserting
 * a `LDC + INVOKESTATIC` pair before the target instruction places
 * them AFTER any preceding label but BEFORE the original target
 * instruction. Jumps to that label now fall into our event emission,
 * then continue into the original target instruction.
 *
 * **Ordering in `mn.tryCatchBlocks`:** each loop's catch-any entry is
 * appended via `.add(...)` (not `.add(0, ...)`). At the time
 * `LoopWrapper.wrap` runs (between [TopLevelCallWrapper] at STANDARD+
 * and [MethodBodyWrapper] at MINIMAL+), the table already contains
 * per-call wraps from STANDARD at low indices and any user-original
 * handlers. Appending places loop entries AFTER user handlers — a
 * known limitation: if user code wraps the loop in a try-catch
 * `try { for (…) { … } } catch (…) { … }`, an exception in the body
 * dispatches to the user's handler first; the loop's
 * exception-path `onLoopEnd` never fires for that path. The SDK's
 * folder finalizes the orphan as `CANCELLED` with `orphaned=true`
 * at method end, so the dashboard still sees the loop, just marked
 * as orphaned. Normal-path exit events (via the per-exit-target
 * insertions) are unaffected.
 *
 * **Dispatcher contract dependency:** same as [TopLevelCallWrapper] and
 * [MethodBodyWrapper] — assumes `BugseeAppStartupDispatcher`'s six
 * static entry points are no-throw per the SDK's `emit + safeLog`
 * guarantee.
 */
internal object LoopWrapper {

    private const val DISPATCH_DESCRIPTOR = "(Ljava/lang/String;)V"

    fun wrap(
        methodNode: MethodNode,
        ownerInternalName: String,
        dispatcherInternalName: String,
    ) {
        if (methodNode.instructions.size() == 0) return
        if (StartupMethodFilter.isSuspendDescriptor(methodNode.desc)) return

        val cfg = MethodCfg.buildOrNull(ownerInternalName, methodNode) ?: return
        val dom = Dominators.compute(cfg)
        val allLoops = NaturalLoops.findAll(cfg, dom)
        if (allLoops.isEmpty()) return
        val topLevel = NaturalLoops.filterTopLevel(allLoops)
        if (topLevel.isEmpty()) return

        // Process loops in a stable order (by header index ascending)
        // so per-method ordinal assignment is deterministic.
        val ordered = topLevel.sortedBy { it.header }

        // **Two-phase wrap to support sibling top-level loops.**
        //
        // Phase 1 (read-only): for each loop, validate the skip
        // conditions and resolve the persistent node references we'll
        // need to mutate around — all `instructions.get(idx)` lookups
        // happen here against the pre-mutation InsnList. The CFG /
        // dominators / body BitSets are also pre-mutation indices,
        // matching the node refs we capture in this phase.
        //
        // Phase 2 (mutating): for each plan, perform the InsnList
        // mutations using the captured node references. ASM keeps node
        // objects stable across `insertBefore` / `insert` / `add` calls,
        // so a later loop's anchors remain valid even after earlier
        // loops have mutated the list around them.
        //
        // Pre-fix this was a single loop that did both phases together;
        // for the second sibling top-level loop, `instructions.get(idx)`
        // would return a node FROM THE FIRST WRAP (since the index
        // semantics shifted), producing structurally broken bytecode.
        // No existing test surfaced this because every multi-loop
        // fixture either uses a single loop or nested loops (where the
        // top-level filter keeps only the outer, again giving a single
        // wrap). See `DetailedTierTransformTest` — the
        // sibling-top-level-loops fixture pins the fix.
        // **Ordinal semantics:** `ordinal++` runs for EVERY loop in
        // `ordered`, including loops that `planSingleLoopWrap` ultimately
        // rejects. A skipped loop therefore "burns" its ordinal — e.g.
        // a method with three sibling top-level loops where the second
        // is non-contiguous would emit `loop_1` and `loop_3` site ids,
        // skipping `loop_2`. This is intentional: ordinals are stable
        // across builds (loop discovery order is sorted by header
        // index) and distinct within a method, but NOT guaranteed to
        // be contiguous. Stability beats density for telemetry
        // continuity — a downstream dashboard binding to
        // `MyApp#onCreate#loop_3` keeps tracking the same loop even
        // if a future code change adds or removes a sibling.
        val plans = ArrayList<WrapPlan>(ordered.size)
        var ordinal = 0
        for (loop in ordered) {
            ordinal++
            val plan = planSingleLoopWrap(
                methodNode = methodNode,
                loop = loop,
                ordinal = ordinal,
                ownerInternalName = ownerInternalName,
                cfg = cfg,
            )
            if (plan != null) {
                plans.add(plan)
            }
            // Skipped loops are silently dropped — no observable side
            // effect other than the absence of LOOP_START/LOOP_END
            // events for that loop. The relevant skip paths are:
            //   * non-contiguous body (rare in init code);
            //   * no exit targets (infinite loop — start-without-end
            //     spans would clutter dashboards);
            //   * exit target ALSO reachable from outside the loop
            //     body (false-positive end events would otherwise fire
            //     when control bypassed the loop).
            // See the inline comments in planSingleLoopWrap for each
            // condition.
        }

        for (plan in plans) {
            applyWrapPlan(methodNode, plan, dispatcherInternalName)
        }
    }

    /**
     * The persistent state needed to apply a single loop's wrap. All
     * fields hold either pure data (siteId) or stable ASM node references
     * (the body anchors and exit anchors). Node references survive
     * arbitrary `InsnList.insertBefore` / `insert` / `add` mutations
     * elsewhere in the InsnList — only outright `remove` invalidates
     * them, and we never remove nodes during wrap.
     */
    private data class WrapPlan(
        val siteId: String,
        val firstBodyInsn: AbstractInsnNode,
        val lastBodyInsn: AbstractInsnNode,
        val exitTargetAnchors: List<AbstractInsnNode>,
        val tryStart: LabelNode,
        val tryEnd: LabelNode,
        val handler: LabelNode,
    )

    /**
     * Read-only planning phase. Validates skip conditions and captures
     * persistent ASM node references against the pre-mutation InsnList.
     * Returns a [WrapPlan] if the loop should be wrapped, `null` if it
     * should be skipped. Does NOT mutate [methodNode] or any of its
     * substructures.
     */
    private fun planSingleLoopWrap(
        methodNode: MethodNode,
        loop: NaturalLoop,
        ordinal: Int,
        ownerInternalName: String,
        cfg: MethodCfg,
    ): WrapPlan? {
        val body = loop.body
        val minIdx = body.nextSetBit(0)
        if (minIdx < 0) return null
        val maxIdx = body.previousSetBit(cfg.instructionCount - 1)

        // Contiguity check — every index in [minIdx, maxIdx] must be
        // either in the body, or be a normal-CFG island (no incoming
        // or outgoing normal-CFG edges). The island case covers the
        // per-call exception-handler blocks that `TopLevelCallWrapper`
        // inlines BEFORE this wrapper runs at STANDARD+: each handler
        // is reachable only via an exception edge from inside the
        // wrapped call's try region, and `MethodCfg` deliberately
        // excludes exception edges from the CFG, so the Analyzer never
        // visits the handler block — leaving its predecessor and
        // successor lists empty. Such an island never executes on the
        // loop's normal control-flow path, so the hammock-region wrap
        // remains safe. Genuinely non-contiguous loops (rare; the body
        // straddles unrelated code reachable via a normal jump) still
        // get rejected because the interloping code has non-empty
        // predecessors via that jump.
        //
        // Note: `cfg.successors[i]` is the raw field on [MethodCfg]
        // intentionally — there is no `successorsOf(i)` helper because
        // successor lookup is array-indexed and already O(1). If a
        // future refactor introduces lazy storage, the call sites here
        // and below (exit-target collection) must be updated together.
        for (i in minIdx..maxIdx) {
            if (body.get(i)) continue
            if (cfg.successors[i].isNotEmpty()
                || cfg.predecessorsOf(i).isNotEmpty()
            ) {
                return null
            }
        }

        // Find exit targets: instruction indices outside body that are
        // reachable by a normal-CFG edge from somewhere inside body.
        // Restricted to actual body indices (skipping CFG-island gap
        // instructions accepted by the contiguity check above) — an
        // island has empty `successors` today and so contributes
        // nothing, but iterating only the body indices guards against
        // a future CFG change that recorded edges out of these islands.
        val exitTargets = HashSet<Int>()
        for (u in minIdx..maxIdx) {
            if (!body.get(u)) continue
            for (v in cfg.successors[u]) {
                if (v !in 0 until cfg.instructionCount) continue
                if (!body.get(v)) exitTargets.add(v)
            }
        }
        // No exit (infinite loop) — onLoopEnd would never fire on the
        // normal path, leaving a permanently-orphan LOOP_START. The
        // exception path's handler-fire would still emit an END, but
        // an event with start-without-end is more useful than the
        // reverse, so skip these entirely.
        if (exitTargets.isEmpty()) return null

        // **External-predecessor check.** If any exit target is ALSO
        // reachable from outside the loop body (e.g. an `if` above the
        // loop jumping past it to the same label that's also the loop's
        // exit), inserting `onLoopEnd` before that target would fire
        // the END event even when control never entered the loop —
        // a false-positive end with no matching start. For Phase 7 v1
        // we skip the whole loop in that case rather than emit partial
        // events. A future revision could rewrite jump targets to a
        // dedicated redirect block per exit edge, isolating in-loop
        // dispatch from external jumps.
        for (targetIdx in exitTargets) {
            val preds = cfg.predecessorsOf(targetIdx)
            for (pred in preds) {
                if (pred in 0 until cfg.instructionCount && !body.get(pred)) {
                    return null
                }
            }
        }

        // **Critical: capture every node reference we'll mutate around
        // BEFORE any insertion happens.** ASM's InsnList caches indices
        // per-node and invalidates the cache on every mutation; after
        // an `insertBefore`, the original `get(i)` would return a
        // different node (the one now at position i in the new layout),
        // not the original instruction at that pre-mutation index.
        //
        // **Critical for sibling top-level loops**: the planning phase
        // captures node references against the pre-mutation InsnList.
        // Subsequent loops' planning passes also see the pre-mutation
        // InsnList (no mutations happen during phase 1 of the outer
        // wrap loop), so each plan's anchors are valid pre-mutation
        // references. ASM keeps node objects stable across phase-2
        // mutations elsewhere in the list — `insertBefore(anchor, list)`
        // works against the anchor's current linked-list neighbors,
        // wherever those happen to be.
        val siteId = SiteIdGenerator.forLoop(ownerInternalName, methodNode.name, ordinal)
        val firstBodyInsn = methodNode.instructions.get(minIdx)
        val lastBodyInsn = methodNode.instructions.get(maxIdx)
        val exitTargetNodes: List<AbstractInsnNode> = exitTargets.map { idx ->
            methodNode.instructions.get(idx)
        }

        return WrapPlan(
            siteId = siteId,
            firstBodyInsn = firstBodyInsn,
            lastBodyInsn = lastBodyInsn,
            exitTargetAnchors = exitTargetNodes,
            tryStart = LabelNode(),
            tryEnd = LabelNode(),
            handler = LabelNode(),
        )
    }

    /**
     * Applies a [WrapPlan] to [methodNode]. Mutates the InsnList and
     * tryCatchBlocks. Safe to call sequentially for multiple plans
     * built against the same pre-mutation InsnList — each plan's
     * anchors stay valid across other plans' mutations because ASM
     * preserves node identity.
     */
    private fun applyWrapPlan(
        methodNode: MethodNode,
        plan: WrapPlan,
        dispatcherInternalName: String,
    ) {
        // Insert onLoopStart prefix BEFORE the first body instruction.
        // Order: LDC + INVOKESTATIC + tryStart label, then original
        // first-body-instruction.
        val prefix = InsnList().apply {
            add(LdcInsnNode(plan.siteId))
            add(MethodInsnNode(
                Opcodes.INVOKESTATIC,
                dispatcherInternalName,
                "onLoopStart",
                DISPATCH_DESCRIPTOR,
                false,
            ))
            add(plan.tryStart)
        }
        methodNode.instructions.insertBefore(plan.firstBodyInsn, prefix)

        // Insert tryEnd label AFTER the last body instruction.
        methodNode.instructions.insert(plan.lastBodyInsn, plan.tryEnd)

        // For each unique exit target, insert onLoopEnd BEFORE the first
        // real-opcode anchor at-or-after the target. This single rule
        // covers all anchor shapes uniformly:
        //
        //   * **Real-opcode target** (fall-through exit): `targetInsn`
        //     is already real, so `firstRealOpcodeAtOrAfter` returns
        //     it. `insertBefore(targetInsn, onEnd)` places the event
        //     ahead of the original instruction — sequential flow
        //     hits the event first. Inserting AFTER would run the
        //     original first (and if it's a RETURN, the event would
        //     never fire).
        //
        //   * **[LabelNode] target** (jump exit): walk past the label
        //     plus any intervening pseudo-nodes ([FrameNode],
        //     [LineNumberNode]) to the first real opcode, and insert
        //     BEFORE it. The label's resolved bytecode offset is the
        //     offset of that first real opcode — so a jump to the
        //     label lands on our injected `LDC` first, runs the
        //     dispatch, and falls through into the original target
        //     instruction. The preserved leading `Label → Frame → Line`
        //     pseudo-chain keeps frame directives at their original
        //     jump-target boundary, which matters for non-
        //     `COMPUTE_FRAMES` ClassWriter configurations.
        //
        //   * **[FrameNode] / [LineNumberNode] target**: walk past the
        //     pseudo chain similarly. Same rationale as the LabelNode
        //     case.
        //
        // `firstRealOpcodeAtOrAfter` returns `null` only if no real
        // opcode exists between the target and the end of the InsnList
        // — never expected in well-formed bytecode (the JVM requires a
        // real terminator). The `continue` defensively skips this
        // pathological case.
        for (targetInsn in plan.exitTargetAnchors) {
            val anchor = firstRealOpcodeAtOrAfter(targetInsn) ?: continue
            val onEnd = InsnList().apply {
                add(LdcInsnNode(plan.siteId))
                add(MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    dispatcherInternalName,
                    "onLoopEnd",
                    DISPATCH_DESCRIPTOR,
                    false,
                ))
            }
            methodNode.instructions.insertBefore(anchor, onEnd)
        }

        // Append the catch-any handler at the END of the method body.
        // Unreachable from normal flow at this point (the original
        // last instruction is always a RETURN / ATHROW / GOTO per
        // JVM well-formedness). When MethodBodyWrapper runs later
        // and appends its own outer-handler suffix, that lands AFTER
        // this loop handler — and this loop handler ends in ATHROW,
        // so no fall-through into MethodBodyWrapper's suffix either.
        // For sibling top-level loops, each loop's handler suffix is
        // appended in turn; they sit at the tail of the InsnList in
        // plan-application order, each terminating in ATHROW so no
        // fall-through between them either.
        val handlerSuffix = InsnList().apply {
            add(plan.handler)
            add(LdcInsnNode(plan.siteId))
            add(MethodInsnNode(
                Opcodes.INVOKESTATIC,
                dispatcherInternalName,
                "onLoopEnd",
                DISPATCH_DESCRIPTOR,
                false,
            ))
            add(InsnNode(Opcodes.ATHROW))
        }
        methodNode.instructions.add(handlerSuffix)

        // Loop try-catch — appended LAST so per-call wraps (added by
        // STANDARD at index 0) and user handlers stay at lower indices.
        // Known limitation re: user-try-around-loop documented in the
        // class KDoc.
        methodNode.tryCatchBlocks.add(
            TryCatchBlockNode(plan.tryStart, plan.tryEnd, plan.handler, /* type = */ null)
        )
    }

    /**
     * An "opcode-bearing" instruction node — has a real JVM opcode
     * (opcode `>= 0`). [LabelNode], [org.objectweb.asm.tree.FrameNode],
     * and [org.objectweb.asm.tree.LineNumberNode] return `-1` for
     * `getOpcode()` and are NOT real opcodes for our purposes.
     */
    private fun isRealOpcode(node: AbstractInsnNode): Boolean = node.opcode >= 0

    /**
     * Walks forward from [start] (inclusive) through any pseudo-nodes
     * ([LabelNode], [org.objectweb.asm.tree.FrameNode],
     * [org.objectweb.asm.tree.LineNumberNode]) until a real opcode is
     * found. Returns `null` if no real opcode exists between [start]
     * and the end of the InsnList — should not happen in well-formed
     * bytecode, but the caller defensively `continue`s on `null`.
     */
    private fun firstRealOpcodeAtOrAfter(start: AbstractInsnNode): AbstractInsnNode? {
        var cursor: AbstractInsnNode? = start
        while (cursor != null) {
            if (isRealOpcode(cursor)) return cursor
            cursor = cursor.next
        }
        return null
    }
}
