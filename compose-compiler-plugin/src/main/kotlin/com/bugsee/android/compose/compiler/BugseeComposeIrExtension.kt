package com.bugsee.android.compose.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isVararg
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * IR generation extension that injects `Modifier.bugseeTag("<ComposableName>")`
 * into every composable function call that accepts a `Modifier` parameter.
 *
 * This allows the Bugsee runtime to identify which composable function created
 * a given layout/semantics node in the view hierarchy.
 *
 * The approach mirrors Sentry's `JetpackComposeTracingIrExtension` — injecting
 * a semantic tag at compile time so the runtime tree walker can read it.
 */
class BugseeComposeIrExtension : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        // Resolve the bugseeTag extension function — if not on classpath, skip silently
        val bugseeTagFunction = pluginContext.referenceFunctions(
            CallableId(
                FqName("com.bugsee.library.compose"),
                Name.identifier("bugseeTag")
            )
        ).firstOrNull()

        if (bugseeTagFunction == null) {
            // bugsee-compose runtime not on classpath — nothing to inject
            return
        }

        val modifierClassId = ClassId(
            FqName("androidx.compose.ui"),
            Name.identifier("Modifier")
        )
        val modifierClass = pluginContext.referenceClass(modifierClassId) ?: return

        // Resolve the Modifier companion object — used as the extension receiver
        // when no modifier argument is supplied by the user.
        // Modifier is an interface; its companion object (Modifier.Companion) implements
        // Modifier and serves as the identity element in modifier chains.
        @OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
        val modifierCompanion = (modifierClass.owner as? IrClass)?.companionObject()

        if (modifierCompanion == null) {
            // Cannot resolve Modifier.Companion — skip instrumentation
            return
        }

        val composableAnnotation = FqName("androidx.compose.runtime.Composable")

        moduleFragment.transform(
            BugseeComposeTransformer(
                pluginContext,
                bugseeTagFunction,
                modifierClass,
                modifierCompanion.symbol,
                composableAnnotation
            ),
            null
        )
    }
}

@OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
private class BugseeComposeTransformer(
    private val pluginContext: IrPluginContext,
    private val bugseeTagFunction: org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol,
    private val modifierClass: org.jetbrains.kotlin.ir.symbols.IrClassSymbol,
    private val modifierCompanionSymbol: org.jetbrains.kotlin.ir.symbols.IrClassSymbol,
    private val composableAnnotation: FqName
) : IrElementTransformerVoidWithContext() {

    // Stack of enclosing composable function names
    private val composableNameStack = ArrayDeque<String>()

    override fun visitFunctionNew(declaration: IrFunction): IrStatement {
        val isComposable = declaration.hasAnnotation(composableAnnotation)
        val name = declaration.name.asString()

        if (isComposable && !name.startsWith("<")) {
            composableNameStack.addLast(name)
            val result = super.visitFunctionNew(declaration)
            composableNameStack.removeLast()
            return result
        }

        return super.visitFunctionNew(declaration)
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val result = super.visitCall(expression)

        // Safe cast — super.visitCall may return a different expression type
        if (result !is IrCall) {
            return result
        }

        if (composableNameStack.isEmpty()) {
            return result
        }

        val callee = result.symbol.owner
        val calleeFqName = callee.fqNameWhenAvailable?.asString() ?: return result

        // Skip framework and Bugsee packages — only instrument user code
        if (shouldSkipPackage(calleeFqName)) {
            return result
        }

        // Find the Modifier parameter index
        val modifierParamIndex = findModifierParameterIndex(callee)
        if (modifierParamIndex < 0) {
            return result
        }

        val currentTag = composableNameStack.last()

        // Get the current modifier argument (may be null if using default)
        val currentModifierArg = result.getValueArgument(modifierParamIndex)

        val builder = DeclarationIrBuilder(pluginContext, result.symbol, result.startOffset, result.endOffset)

        val taggedModifier: IrExpression = if (currentModifierArg == null) {
            // No modifier supplied — inject: Modifier.bugseeTag(tag)
            // In IR: bugseeTag(extensionReceiver = Modifier.Companion, tag)
            builder.irCall(bugseeTagFunction).apply {
                extensionReceiver = builder.irGetObject(modifierCompanionSymbol)
                putValueArgument(0, builder.irString(currentTag))
            }
        } else {
            // Modifier already supplied — append tag: existingModifier.bugseeTag(tag)
            builder.irCall(bugseeTagFunction).apply {
                extensionReceiver = currentModifierArg
                putValueArgument(0, builder.irString(currentTag))
            }
        }

        result.putValueArgument(modifierParamIndex, taggedModifier)

        return result
    }

    private fun findModifierParameterIndex(function: IrFunction): Int {
        for (i in 0 until function.valueParameters.size) {
            val param = function.valueParameters[i]
            if (param.isVararg) continue

            val type = param.type
            if (type.classFqName?.asString() == "androidx.compose.ui.Modifier") {
                return i
            }
            // Also check if the type is a subtype of Modifier
            if (type.isSubtypeOfClass(modifierClass)) {
                return i
            }
        }
        return -1
    }

    private fun shouldSkipPackage(fqName: String): Boolean {
        return fqName.startsWith("androidx.") ||
                fqName.startsWith("com.bugsee.") ||
                fqName.startsWith("kotlinx.") ||
                fqName.startsWith("kotlin.") ||
                fqName.startsWith("com.google.android.")
    }
}
