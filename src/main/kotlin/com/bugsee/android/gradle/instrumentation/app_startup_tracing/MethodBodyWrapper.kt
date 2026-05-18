package com.bugsee.android.gradle.instrumentation.app_startup_tracing

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
 * MINIMAL-tier transformation: wraps a method body in a try / catch-any
 * pair around explicit start/end dispatch calls.
 *
 * Generated shape (logical, not bytecode-exact):
 * ```
 *   LDC siteId
 *   INVOKESTATIC dispatcher.onMethodStart(String)V
 *   <try-start label>
 *     // ── original method body ──
 *     //  ▶ each RETURN/IRETURN/LRETURN/FRETURN/DRETURN/ARETURN gets a
 *     //    matching onMethodEnd injected immediately before it
 *     //  ▶ ATHROW from the original body is NOT pre-instrumented; it
 *     //    propagates into the catch-any handler below, which emits
 *     //    onMethodEnd and re-throws
 *   <try-end label>
 *   <handler label>
 *     LDC siteId
 *     INVOKESTATIC dispatcher.onMethodEnd(String)V
 *     ATHROW         // re-throws the exception already on the stack
 * ```
 *
 * The catch-any [TryCatchBlockNode] (`type = null`) is appended to the
 * END of `methodNode.tryCatchBlocks`, NOT prepended. The JVM scans the
 * exception table in order and dispatches to the first matching handler,
 * so anything the user already has at lower indices wins first — exactly
 * what an "outermost catch-any" is supposed to do.
 *
 * Stack / maxs / frames: the only stack-effect changes are the `(String)V`
 * dispatch calls (push 1, pop 1 — net 0) and the catch-any handler entry
 * (the JVM pushes the exception there). Callers must arrange for the
 * `ClassWriter` to recompute frames + maxs (`COMPUTE_FRAMES` flag, which
 * implies `COMPUTE_MAXS`); the wrapper does not touch `methodNode.maxStack`
 * / `methodNode.maxLocals`.
 *
 * <p><b>Dispatcher contract:</b> the per-return `onMethodEnd` calls are
 * structurally INSIDE the outer catch-any region. If a dispatcher call
 * itself threw, the catch-any would intercept it, call `onMethodEnd`
 * AGAIN, and ATHROW the dispatcher's exception in place of the method's
 * normal return value. To avoid this duplicate-END + lost-return
 * scenario, the SDK's `BugseeAppStartupDispatcher` guarantees its six
 * static entry points are no-throw: each delegates to `emit(...)` which
 * wraps both the live-consumer path and the buffer-write path in
 * `try { ... } catch (Throwable) { safeLog(...) }`. The `safeLog` helper
 * itself catches any throw from the underlying logger. With that
 * contract, the protected region is safe as written.
 *
 * If a future change to the SDK breaks this contract, this wrapper will
 * need a structural revision — multiple try-block segments split around
 * each return's `onMethodEnd`, or the catch-any narrowed to exclude
 * dispatch call ranges.
 *
 * No-op cases (early return, no mutation):
 *  - methodNode.instructions is empty (abstract / native method;
 *    shouldn't reach the wrapper given upstream filters, but a defensive
 *    early-return costs nothing).
 */
internal object MethodBodyWrapper {

    private const val DISPATCH_DESCRIPTOR = "(Ljava/lang/String;)V"

    fun wrap(
        methodNode: MethodNode,
        siteId: String,
        dispatcherInternalName: String,
    ) {
        val instructions = methodNode.instructions
        if (instructions.size() == 0) {
            return
        }

        val startLabel = LabelNode()
        val endLabel = LabelNode()
        val handlerLabel = LabelNode()

        // 1. Collect return-family opcodes BEFORE mutating the list, so
        //    we don't have to reason about iterator validity under
        //    concurrent insertion. ATHROW is deliberately excluded — it
        //    propagates into the catch-any handler which emits onMethodEnd.
        val returnSites = ArrayList<AbstractInsnNode>()
        run {
            var node = instructions.first
            while (node != null) {
                if (isReturnOpcode(node.opcode)) {
                    returnSites.add(node)
                }
                node = node.next
            }
        }

        // 2. Inject onMethodEnd before each return.
        for (returnNode in returnSites) {
            instructions.insertBefore(
                returnNode,
                buildDispatchCall(siteId, dispatcherInternalName, "onMethodEnd")
            )
        }

        // 3. Prefix: onMethodStart + try-block start label.
        val prefix = InsnList().apply {
            add(LdcInsnNode(siteId))
            add(MethodInsnNode(
                Opcodes.INVOKESTATIC,
                dispatcherInternalName,
                "onMethodStart",
                DISPATCH_DESCRIPTOR,
                false,
            ))
            add(startLabel)
        }
        instructions.insert(prefix) // prepend to the InsnList

        // 4. Suffix: try-block end label, handler label, onMethodEnd, ATHROW.
        val suffix = InsnList().apply {
            add(endLabel)
            add(handlerLabel)
            add(LdcInsnNode(siteId))
            add(MethodInsnNode(
                Opcodes.INVOKESTATIC,
                dispatcherInternalName,
                "onMethodEnd",
                DISPATCH_DESCRIPTOR,
                false,
            ))
            add(InsnNode(Opcodes.ATHROW))
        }
        instructions.add(suffix) // append to the InsnList

        // 5. Catch-any try-catch block — appended LAST so any pre-existing
        //    user handlers (and per-call wraps from [TopLevelCallWrapper]
        //    at index 0, per-loop wraps from [LoopWrapper] appended at
        //    intermediate positions) keep their priority.
        //
        //    Invariant: this wrapper MUST be the last layer to add a
        //    try-catch entry. Layer-run order is enforced by
        //    [AppStartupTracingClassVisitor.visitMethod]'s `visitEnd`
        //    (calls → loops → method-body). If a future tier inserts
        //    after this wrapper, JVM exception dispatch would route the
        //    outer catch-any BEFORE the new layer's narrower handler —
        //    silently breaking per-call / per-loop END emission on the
        //    exception path.
        methodNode.tryCatchBlocks.add(
            TryCatchBlockNode(startLabel, endLabel, handlerLabel, /* type = */ null)
        )
    }

    private fun isReturnOpcode(opcode: Int): Boolean = when (opcode) {
        Opcodes.RETURN,
        Opcodes.IRETURN,
        Opcodes.LRETURN,
        Opcodes.FRETURN,
        Opcodes.DRETURN,
        Opcodes.ARETURN -> true
        else -> false
    }

    private fun buildDispatchCall(
        siteId: String,
        dispatcherInternalName: String,
        methodName: String,
    ): InsnList = InsnList().apply {
        add(LdcInsnNode(siteId))
        add(MethodInsnNode(
            Opcodes.INVOKESTATIC,
            dispatcherInternalName,
            methodName,
            DISPATCH_DESCRIPTOR,
            false,
        ))
    }
}
