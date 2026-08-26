package com.bugsee.android.compose.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.defaultValueForType

/**
 * **Kotlin 2.2+ twin of the `src/test` [ComposeDefaultLoweringSimulatorExtension].**
 *
 * Same job — put the byte-exact expression Compose's
 * `ComposerParamTransformer.defaultArgumentFor` leaves in an OMITTED `Modifier`
 * slot into the IR ahead of the Bugsee transform — written against the 2.2
 * `parameters`/`arguments` IR API so it can be loaded into the 2.2 and 2.4
 * compilers alongside the `modernIr` plugin variants (`k22` / `k24`).
 *
 * Why a second copy exists: the `src/test` simulator speaks the pre-2.2 API
 * (`valueParameters`, `getValueArgument`/`putValueArgument`), which is
 * deprecated-as-error in 2.2/2.3 and removed in 2.4 — exactly the split that
 * forces the plugin itself into `legacyIr` + `modernIr` source sets. Without
 * this file the DEFAULT_VALUE-composite repro could only ever be EXECUTED
 * against the k21/legacyIr build, leaving the modernIr guard — the one every
 * Kotlin 2.2–2.4 consumer actually runs — covered by nothing but a structural
 * claim. A guard regression there reproduces the 4.0.5 host-app crash.
 *
 * Modes are selected by the `bugsee.simulator.mode` system property of the
 * compiler process; see [MODE_DEFAULT_VALUE], [MODE_ORIGINLESS] and
 * [MODE_WRAP_EXPLICIT]. Kept in lockstep with the `src/test` simulator.
 */
@OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
class ComposeDefaultLoweringSimulatorExtension : IrGenerationExtension {

    private val mode: String = System.getProperty(MODE_PROPERTY) ?: MODE_DEFAULT_VALUE

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.transform(object : IrElementTransformerVoidWithContext() {
            override fun visitCall(expression: IrCall): IrExpression {
                val result = super.visitCall(expression)
                if (result !is IrCall) return result

                val parameters = result.symbol.owner.parameters
                for (i in parameters.indices) {
                    val param = parameters[i]
                    // `parameters` also carries dispatch/extension/context entries; only a
                    // regular parameter can be the Modifier argument, and `arguments` is
                    // aligned with `parameters`.
                    if (param.kind != IrParameterKind.Regular) continue
                    if (param.type.classFqName?.asString() != MODIFIER_FQN) continue

                    val existing = result.arguments.getOrNull(i)
                    if (existing != null) {
                        // Only MODE_WRAP_EXPLICIT touches a slot the caller filled.
                        if (mode != MODE_WRAP_EXPLICIT) continue
                        // A composite that wraps a REAL modifier expression and carries no
                        // DEFAULT_VALUE origin: not a defaulted slot, so the Bugsee guard
                        // must let it through and chain the tag onto it.
                        result.arguments[i] = IrCompositeImpl(
                            existing.startOffset,
                            existing.endOffset,
                            existing.type,
                            null,
                            listOf(existing),
                        )
                        continue
                    }

                    // Byte-exact replica of ComposerParamTransformer.defaultArgumentFor for a
                    // reference-typed parameter: defaultValueForType yields a null IrConst,
                    // wrapped in a DEFAULT_VALUE composite.
                    val defaultValue =
                        IrConstImpl.defaultValueForType(result.startOffset, result.endOffset, param.type)
                    result.arguments[i] = IrCompositeImpl(
                        defaultValue.startOffset,
                        defaultValue.endOffset,
                        defaultValue.type,
                        // MODE_ORIGINLESS drops the origin the real lowering sets, so only the
                        // composite-of-null fallback can recognise the slot.
                        if (mode == MODE_ORIGINLESS) null else IrStatementOrigin.DEFAULT_VALUE,
                        listOf(defaultValue),
                    )
                }
                return result
            }
        }, null)
    }

    companion object {
        private const val MODIFIER_FQN = "androidx.compose.ui.Modifier"

        /** System property (set on the compiler process) selecting the emitted shape. */
        const val MODE_PROPERTY = "bugsee.simulator.mode"

        /** The production shape: `IrComposite(DEFAULT_VALUE, [null])` in omitted slots. */
        const val MODE_DEFAULT_VALUE = "defaultValue"

        /** `IrComposite(origin = null, [null])` in omitted slots. */
        const val MODE_ORIGINLESS = "originless"

        /** Production shape for omitted slots, plus `IrComposite(null, [expr])` over explicit ones. */
        const val MODE_WRAP_EXPLICIT = "wrapExplicit"
    }
}
