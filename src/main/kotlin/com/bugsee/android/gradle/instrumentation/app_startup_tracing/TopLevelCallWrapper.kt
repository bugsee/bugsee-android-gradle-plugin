package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TryCatchBlockNode

/**
 * STANDARD-tier addition: wraps every "top-level" `INVOKE*` instruction
 * inside an instrumented method with `dispatcher.onCallStart` /
 * `dispatcher.onCallEnd` calls, guarded by a per-call try / catch-any
 * region so the END event fires even when the call throws.
 *
 * **"Top-level"** = the call is not inside any [TryCatchBlockNode]
 * range present in `methodNode.tryCatchBlocks` at the time this wrapper
 * runs. The user's existing handlers protect their own contents
 * already; double-wrapping their contents would (a) inflate span counts
 * and (b) muddy the semantics ("did this call throw or did its
 * surrounding user handler swallow it?"). The transform stays out of
 * those regions entirely.
 *
 * Run **BEFORE** [MethodBodyWrapper] in the same `visitEnd` pass — if
 * the MINIMAL-tier outer catch-any has already been appended, EVERY
 * call lies inside that region and this wrapper would refuse to touch
 * any of them.
 *
 * Generated shape per wrapped call:
 * ```
 *   <args on stack as before>
 *   LDC siteId
 *   INVOKESTATIC dispatcher.onCallStart(String)V
 *   <try-start label>
 *   <original INVOKE>
 *   <try-end label>
 *   LDC siteId
 *   INVOKESTATIC dispatcher.onCallEnd(String)V
 *   GOTO <after-call label>
 *   <handler label>            ← stack: [throwable]
 *   LDC siteId
 *   INVOKESTATIC dispatcher.onCallEnd(String)V
 *   ATHROW                     ← re-throws the original exception
 *   <after-call label>
 * ```
 *
 * Stack effect of the `(String)V` calls is `push 1, pop 1` (net zero),
 * so existing args sitting on the stack before the INVOKE remain in
 * place for the INVOKE to consume — confirmed by `SimpleVerifier`
 * during the [com.bugsee.android.gradle.instrumentation.fixtures.AsmTestHarness.verify]
 * pass in the transform tests.
 *
 * **Skip rules:**
 *  - Non-`MethodInsnNode` opcodes (`INVOKEDYNAMIC` is an
 *    [org.objectweb.asm.tree.InvokeDynamicInsnNode], deliberately not
 *    treated as an INVOKE for our purposes — lambda metafactory
 *    bootstraps don't benefit from being timed).
 *  - Calls whose owner is the dispatcher class itself (self-recursion
 *    guard — protects against double-wrap if a previous transform
 *    pass already injected dispatcher calls). The match is owner-only,
 *    not owner+name+desc, so any future helper method added to
 *    `BugseeAppStartupDispatcher` would also be skipped if user code
 *    happened to call it. Today the dispatcher only exposes the six
 *    `on*Start` / `on*End` entries, so this is conservative-on-the-safe
 *    side rather than restrictive.
 *  - Calls inside any existing `TryCatchBlockNode` range.
 *
 * **Dispatcher contract dependency:** the per-call `onCallStart` /
 * `onCallEnd` invocations rely on the same SDK no-throw contract that
 * [MethodBodyWrapper] documents — if `BugseeAppStartupDispatcher.emit`
 * ever throws to its caller, the per-call try/catch's `onCallEnd` (the
 * one inside the handler block) could be skipped: the handler block
 * lives between `tryEnd` and `afterCall` and is therefore OUTSIDE the
 * per-call try region but INSIDE the MINIMAL-tier outer catch-any.
 * A throw from the handler's `onCallEnd` would short-circuit straight
 * to the outer handler, yielding `CALL_START` + `METHOD_END` + propagation
 * with the `CALL_END` missing. The SDK's existing `try { ... } catch
 * (Throwable) { safeLog(...) }` in `emit` and `safeLog`'s own
 * Throwable-swallowing inner catch keep this from happening in
 * practice.
 *
 * **Ordering inside `tryCatchBlocks`:** each per-call entry is added at
 * index 0 of the list, so when multiple calls are wrapped, the most
 * recently wrapped is at index 0. JVM exception dispatch scans the
 * table in order; per-call wraps are narrow (covering just the INVOKE),
 * so they don't conflict with each other regardless of order. The
 * important invariant is that they all sit BEFORE the MINIMAL-tier
 * outer catch-any (which is added LATER, by [MethodBodyWrapper]'s
 * `.add(...)` at the END of the list).
 */
internal object TopLevelCallWrapper {

    private const val DISPATCH_DESCRIPTOR = "(Ljava/lang/String;)V"

    fun wrap(
        methodNode: MethodNode,
        dispatcherInternalName: String,
    ) {
        val instructions = methodNode.instructions
        if (instructions.size() == 0) return

        // Snapshot the protected instruction-index ranges. We index by
        // position in the linked list, not by raw offset, because
        // InsnList nodes don't carry offsets until visited by a writer.
        val protectedRanges = collectProtectedRanges(methodNode)

        // Walk once, collecting candidate INVOKE sites. Mutating the
        // instruction list while iterating is fragile; materializing the
        // sites first sidesteps the question.
        val callSites = ArrayList<MethodInsnNode>()
        var node: AbstractInsnNode? = instructions.first
        var idx = 0
        while (node != null) {
            if (node is MethodInsnNode
                && isInvokeMethodOpcode(node.opcode)
                && node.owner != dispatcherInternalName
                && !isProtected(idx, protectedRanges)
            ) {
                callSites.add(node)
            }
            node = node.next
            idx++
        }

        for (callNode in callSites) {
            wrapSingleCall(methodNode, callNode, dispatcherInternalName)
        }
    }

    private fun collectProtectedRanges(methodNode: MethodNode): List<IntRange> {
        if (methodNode.tryCatchBlocks.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>(methodNode.tryCatchBlocks.size)
        for (tcb in methodNode.tryCatchBlocks) {
            // tcb.start / tcb.end are LabelNodes — non-instruction
            // pseudo-nodes in the InsnList. Their indices land ON the
            // labels themselves, not on the first real instruction
            // after each label, so the range is one step wider than the
            // strict JVM "PC range" semantics. That's benign here: the
            // candidate filter below only matches MethodInsnNode (opcode
            // != -1), and labels / line-number nodes / frame nodes are
            // rejected by that filter regardless. The over-protection
            // therefore only ever affects pseudo-nodes that we'd skip
            // anyway.
            val startIdx = methodNode.instructions.indexOf(tcb.start)
            val endIdx = methodNode.instructions.indexOf(tcb.end)
            if (startIdx < 0 || endIdx < 0 || startIdx >= endIdx) continue
            out.add(startIdx until endIdx)
        }
        return out
    }

    private fun isProtected(idx: Int, ranges: List<IntRange>): Boolean {
        for (r in ranges) {
            if (idx in r) return true
        }
        return false
    }

    private fun wrapSingleCall(
        methodNode: MethodNode,
        callNode: MethodInsnNode,
        dispatcherInternalName: String,
    ) {
        val siteId = SiteIdGenerator.forCall(callNode.owner, callNode.name)
        val tryStart = LabelNode()
        val tryEnd = LabelNode()
        val handler = LabelNode()
        val afterCall = LabelNode()

        val before = InsnList().apply {
            add(LdcInsnNode(siteId))
            add(MethodInsnNode(
                Opcodes.INVOKESTATIC,
                dispatcherInternalName,
                "onCallStart",
                DISPATCH_DESCRIPTOR,
                false,
            ))
            add(tryStart)
        }
        methodNode.instructions.insertBefore(callNode, before)

        val after = InsnList().apply {
            add(tryEnd)
            add(LdcInsnNode(siteId))
            add(MethodInsnNode(
                Opcodes.INVOKESTATIC,
                dispatcherInternalName,
                "onCallEnd",
                DISPATCH_DESCRIPTOR,
                false,
            ))
            add(JumpInsnNode(Opcodes.GOTO, afterCall))
            add(handler)
            add(LdcInsnNode(siteId))
            add(MethodInsnNode(
                Opcodes.INVOKESTATIC,
                dispatcherInternalName,
                "onCallEnd",
                DISPATCH_DESCRIPTOR,
                false,
            ))
            add(InsnNode(Opcodes.ATHROW))
            add(afterCall)
        }
        methodNode.instructions.insert(callNode, after)

        // Add the per-call try-catch at index 0. Multiple per-call wraps
        // accumulate at the front of the list; their ranges are disjoint
        // (one INVOKE each, with the LDC + INVOKESTATIC instrumentation
        // pairs strictly outside the range), so their order relative to
        // each other does not affect JVM exception dispatch. The
        // load-bearing invariant is that ALL per-call entries precede
        // the later-added outer catch-any from MethodBodyWrapper (which
        // uses `.add(...)` to append at the end). This preserves the
        // innermost-first table ordering: per-call narrow handlers,
        // then any pre-existing user handlers, then our outer catch-any.
        methodNode.tryCatchBlocks.add(
            0,
            TryCatchBlockNode(tryStart, tryEnd, handler, /* type = */ null)
        )
    }

    private fun isInvokeMethodOpcode(opcode: Int): Boolean = when (opcode) {
        Opcodes.INVOKEVIRTUAL,
        Opcodes.INVOKESPECIAL,
        Opcodes.INVOKESTATIC,
        Opcodes.INVOKEINTERFACE -> true
        else -> false
    }
}
