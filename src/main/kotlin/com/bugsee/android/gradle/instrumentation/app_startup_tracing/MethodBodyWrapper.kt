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

    /**
     * Wraps {@code methodNode}'s body in the standard start/finally/end
     * pattern.
     *
     * <p>{@code startMethodName} / {@code endMethodName} parameterize
     * which dispatcher entry points are invoked. The default is
     * {@code onMethodStart} / {@code onMethodEnd} (the generic kind-
     * agnostic pair that emits {@code app.startup.method} spans on the
     * SDK side). Callers select kind-specific variants for distinct
     * dashboard labels:
     * <ul>
     *   <li>{@code onApplicationStart} / {@code onApplicationEnd} for
     *       Application kind → {@code app.startup.application}</li>
     *   <li>{@code onProviderStart} / {@code onProviderEnd} for
     *       ContentProvider kind → {@code app.startup.provider}</li>
     *   <li>{@code onAnnotatedStart} / {@code onAnnotatedEnd} for
     *       FULL-tier {@code @BugseeTrace}-annotated methods →
     *       {@code app.startup.annotated}</li>
     * </ul>
     *
     * <p>Note: {@code onActivityStart/End} entry points also exist on
     * the SDK dispatcher and fold to {@code app.startup.activity}, but
     * the plugin DOES NOT emit them — Activity lifecycle events are
     * self-emitted at runtime by
     * {@code StartupPerformanceProvider.StartupLifecycleTracker} on the
     * SDK side, not via bytecode injection.
     *
     * <p>Both names must point at static methods with descriptor
     * {@code (Ljava/lang/String;)V} on {@code dispatcherInternalName}
     * (the locked dispatcher contract; the kind-specific entry points
     * follow the same signature as the originals so the emit shape
     * stays uniform).
     */
    fun wrap(
        methodNode: MethodNode,
        siteId: String,
        dispatcherInternalName: String,
        startMethodName: String = "onMethodStart",
        endMethodName: String = "onMethodEnd",
    ) {
        val instructions = methodNode.instructions
        if (instructions.size() == 0) {
            return
        }

        // Defensive invariant snapshot — see the post-write assertion at
        // the bottom of this method and the layer-ordering rationale on
        // the catch-any [TryCatchBlockNode] append.
        val preWrapSize = methodNode.tryCatchBlocks.size

        val startLabel = LabelNode()
        val endLabel = LabelNode()
        val handlerLabel = LabelNode()

        // 1. Collect return-family opcodes BEFORE mutating the list, so
        //    we don't have to reason about iterator validity under
        //    concurrent insertion. ATHROW is deliberately excluded — it
        //    propagates into the catch-any handler which emits the end
        //    dispatch.
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

        // 2. Inject the end dispatch before each return.
        for (returnNode in returnSites) {
            instructions.insertBefore(
                returnNode,
                buildDispatchCall(siteId, dispatcherInternalName, endMethodName)
            )
        }

        // 3. Prefix: start dispatch + try-block start label.
        val prefix = InsnList().apply {
            add(LdcInsnNode(siteId))
            add(MethodInsnNode(
                Opcodes.INVOKESTATIC,
                dispatcherInternalName,
                startMethodName,
                DISPATCH_DESCRIPTOR,
                false,
            ))
            add(startLabel)
        }
        instructions.insert(prefix) // prepend to the InsnList

        // 4. Suffix: try-block end label, handler label, end dispatch, ATHROW.
        val suffix = InsnList().apply {
            add(endLabel)
            add(handlerLabel)
            add(LdcInsnNode(siteId))
            add(MethodInsnNode(
                Opcodes.INVOKESTATIC,
                dispatcherInternalName,
                endMethodName,
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
        val ourEntry = TryCatchBlockNode(startLabel, endLabel, handlerLabel, /* type = */ null)
        methodNode.tryCatchBlocks.add(ourEntry)

        // Enforce the layer-ordering invariant declared in the KDoc above:
        // MethodBodyWrapper MUST be the last layer to add a try-catch
        // entry on this method, and its catch-any entry MUST sit at the
        // tail of `tryCatchBlocks` so the JVM's exception table walk
        // gives prior (narrower) layers' handlers priority. Any future
        // wrapper that runs after this one would silently demote the
        // narrower handlers — fail loudly instead.
        val postWrapSize = methodNode.tryCatchBlocks.size
        check(postWrapSize == preWrapSize + 1) {
            "MethodBodyWrapper layer-ordering invariant violated: " +
                "tryCatchBlocks grew from $preWrapSize to $postWrapSize " +
                "(expected exactly +1) during wrap of method " +
                "${methodNode.name}${methodNode.desc}. Another layer ran " +
                "inside MethodBodyWrapper.wrap; this is not supported."
        }
        check(methodNode.tryCatchBlocks.last() === ourEntry) {
            "MethodBodyWrapper layer-ordering invariant violated: " +
                "the catch-any entry appended for method " +
                "${methodNode.name}${methodNode.desc} is not at the tail " +
                "of tryCatchBlocks. Another layer reordered or appended " +
                "after MethodBodyWrapper; this would demote narrower " +
                "handlers in the JVM exception table."
        }
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
