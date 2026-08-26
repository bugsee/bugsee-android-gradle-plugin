package com.bugsee.android.compose.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.defaultValueForType

/**
 * Test-only compiler plugin that replicates — instruction for instruction — the
 * expression Compose's `ComposerParamTransformer.defaultArgumentFor` puts into an
 * OMITTED value-argument slot:
 *
 * ```
 * IrCompositeImpl(
 *     defaultValue.startOffset, defaultValue.endOffset, defaultValue.type,
 *     IrStatementOrigin.DEFAULT_VALUE,
 *     listOf(defaultValue)          // for a reference type: a null IrConst
 * )
 * ```
 *
 * Verified by decompiling `kotlin-compose-compiler-plugin-embeddable`
 * 2.1.0 / 2.2.10 / 2.3.0 / 2.4.0 — all four build exactly this shape.
 *
 * [DefaultValueLoweringInteropTest] loads this plugin AHEAD of the real Bugsee
 * compose plugin in a real `K2JVMCompiler` run, so the Bugsee transform observes
 * the same argument shape it meets in a production build where Compose's
 * lowering has already run. The variant-matrix fixture cannot produce this
 * shape: it compiles against a STUB Compose with no Compose compiler plugin, so
 * no default lowering ever runs there, and a source-level `null` literal (an
 * `IrConst`) is only a proxy.
 *
 * ### Modes
 *
 * The shape emitted is selected by the `bugsee.simulator.mode` system property
 * of the compiler process (the interop test sets it per case). Beyond the
 * production shape, two synthetic shapes exist to pin the two halves of the
 * guard that the production shape alone leaves untested — because with
 * `origin = DEFAULT_VALUE` present the first branch always matches first:
 *
 *  - [MODE_DEFAULT_VALUE] (default) — the real Compose shape:
 *    `IrComposite(origin = DEFAULT_VALUE, [null])` in every OMITTED slot.
 *  - [MODE_ORIGINLESS] — `IrComposite(origin = null, [null])` in every omitted
 *    slot. Pins the guard's origin-agnostic composite-of-null fallback, which
 *    exists as insurance against a compiler that drops or renames the origin.
 *    With that fallback removed the transform chains onto a runtime `null` and
 *    the compiled fixture dies with the 4.0.5 NPE.
 *  - [MODE_WRAP_EXPLICIT] — omitted slots get the real DEFAULT_VALUE shape AND
 *    every EXPLICIT modifier argument is additionally wrapped in
 *    `IrComposite(origin = null, [<the real expression>])`. Pins that the guard
 *    is NARROW: a composite is only "defaulted" when it carries the
 *    DEFAULT_VALUE origin or wraps a null constant. A guard widened to "any
 *    IrComposite" silently drops the tag on that site.
 */
@OptIn(ExperimentalCompilerApi::class)
class ComposeDefaultLoweringSimulatorRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(ComposeDefaultLoweringSimulatorExtension())
    }
}

@OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
class ComposeDefaultLoweringSimulatorExtension : IrGenerationExtension {

    private val mode: String = System.getProperty(MODE_PROPERTY) ?: MODE_DEFAULT_VALUE

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.transform(object : IrElementTransformerVoidWithContext() {
            override fun visitCall(expression: IrCall): IrExpression {
                val result = super.visitCall(expression)
                if (result !is IrCall) return result

                val callee = result.symbol.owner
                for (i in callee.valueParameters.indices) {
                    val param = callee.valueParameters[i]
                    if (param.type.classFqName?.asString() != MODIFIER_FQN) continue

                    val existing = result.getValueArgument(i)
                    if (existing != null) {
                        // Only MODE_WRAP_EXPLICIT touches a slot the caller filled.
                        if (mode != MODE_WRAP_EXPLICIT) continue
                        // A composite that wraps a REAL modifier expression and carries
                        // no DEFAULT_VALUE origin: not a defaulted slot, so the Bugsee
                        // guard must let it through and chain the tag onto it.
                        result.putValueArgument(
                            i,
                            IrCompositeImpl(
                                existing.startOffset,
                                existing.endOffset,
                                existing.type,
                                null,
                                listOf(existing),
                            )
                        )
                        continue
                    }

                    // Byte-exact replica of ComposerParamTransformer.defaultArgumentFor
                    // for a reference-typed parameter: defaultValueForType yields a
                    // null IrConst, wrapped in a DEFAULT_VALUE composite.
                    val defaultValue =
                        IrConstImpl.defaultValueForType(result.startOffset, result.endOffset, param.type)
                    result.putValueArgument(
                        i,
                        IrCompositeImpl(
                            defaultValue.startOffset,
                            defaultValue.endOffset,
                            defaultValue.type,
                            // MODE_ORIGINLESS drops the origin the real lowering sets, so
                            // only the composite-of-null fallback can recognise the slot.
                            if (mode == MODE_ORIGINLESS) null else IrStatementOrigin.DEFAULT_VALUE,
                            listOf(defaultValue),
                        )
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
