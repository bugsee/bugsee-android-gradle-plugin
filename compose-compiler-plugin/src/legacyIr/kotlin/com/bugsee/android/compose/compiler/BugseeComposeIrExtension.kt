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
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
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
 * IR generation extension that runs two independent passes over every
 * `@Composable` function call, each gated by its own configuration flag:
 *
 * 1. **Tag injection** ([tagInjectionEnabled]) — wraps the `Modifier`
 *    parameter of every composable call inside a user `@Composable`
 *    function with `.bugseeTag("<EnclosingComposableName>")`. Lets the
 *    runtime view-hierarchy walker map LayoutNodes back to source
 *    composable names.
 *
 * 2. **Secure-field injection** ([secureInjectionEnabled]) — detects calls
 *    to `TextField` / `OutlinedTextField` / `BasicTextField` from the
 *    `androidx.compose.material*` and `androidx.compose.foundation.text*`
 *    packages whose `visualTransformation` argument is statically
 *    detectable as `PasswordVisualTransformation`, and wraps their
 *    `Modifier` with `.bugseeSecure()`. The runtime secure-area scanner
 *    in `:compose` reads the resulting `BugseeSecureKey` semantics
 *    property and reports the bounds for redaction.
 *
 * Both passes share the same `Modifier`-parameter rewriting machinery and
 * the same skip rules for non-user code, but apply different selection
 * criteria — see `visitCall` for the precise interleaving.
 *
 * ### Limitation: anonymous composable scopes
 *
 * Both passes only fire when the call site is INSIDE a named
 * `@Composable` function. The transformer maintains a
 * `composableNameStack` populated by `visitFunctionNew` for declarations
 * whose name does not start with `<` (i.e. is not anonymous). Calls made
 * directly inside `setContent { ... }` or other inline composable lambdas
 * therefore receive NO IR injection — neither tag nor secure.
 *
 * This is a deliberate trade-off: anonymous lambdas have no stable name
 * to use as a `bugseeTag`, and stack-empty checks keep the transformer
 * from interfering with composables defined in non-Bugsee tooling
 * pipelines that use anonymous composable lambdas as boundaries.
 *
 * Workaround for sensitive fields in anonymous scopes: extract the call
 * to a named composable, OR rely on the runtime fallback path. The
 * `BugseeComposeSecureScanner` runtime walker reads Compose's own
 * `SemanticsProperties.Password`, which is set automatically by Compose
 * for `TextField` instances using `PasswordVisualTransformation` —
 * regardless of whether the IR injection ran. So password fields
 * declared inline in `setContent` are still protected via the runtime
 * path; only the IR-injected `BugseeSecureKey` marker would be missing.
 *
 * The approach mirrors Sentry's `JetpackComposeTracingIrExtension` for
 * tag injection and adapts it for the secure-field path.
 */
class BugseeComposeIrExtension(
    private val tagInjectionEnabled: Boolean,
    private val secureInjectionEnabled: Boolean
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        if (!tagInjectionEnabled && !secureInjectionEnabled) {
            return
        }

        val modifierClassId = ClassId(
            FqName("androidx.compose.ui"),
            Name.identifier("Modifier")
        )
        val modifierClass = pluginContext.referenceClass(modifierClassId) ?: return

        @OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
        val modifierCompanion = (modifierClass.owner as? IrClass)?.companionObject()
            ?: return

        // Resolve the bugseeTag extension function. Required for tag
        // injection; if absent and tag injection is enabled, that pass
        // silently no-ops.
        val bugseeTagFunction = if (tagInjectionEnabled) {
            pluginContext.referenceFunctions(
                CallableId(
                    FqName("com.bugsee.library.compose"),
                    Name.identifier("bugseeTag")
                )
            ).firstOrNull()
        } else {
            null
        }

        // Resolve the bugseeSecure extension function. Required for
        // secure injection; if absent and secure injection is enabled,
        // that pass silently no-ops.
        val bugseeSecureFunction = if (secureInjectionEnabled) {
            pluginContext.referenceFunctions(
                CallableId(
                    FqName("com.bugsee.library.compose"),
                    Name.identifier("bugseeSecure")
                )
            ).firstOrNull()
        } else {
            null
        }

        if (bugseeTagFunction == null && bugseeSecureFunction == null) {
            // Neither runtime extension is on the classpath — nothing to do.
            return
        }

        moduleFragment.transform(
            BugseeComposeTransformer(
                pluginContext = pluginContext,
                bugseeTagFunction = bugseeTagFunction,
                bugseeSecureFunction = bugseeSecureFunction,
                modifierClass = modifierClass,
                modifierCompanionSymbol = modifierCompanion.symbol,
                composableAnnotation = COMPOSABLE_ANNOTATION
            ),
            null
        )
    }

    private companion object {
        val COMPOSABLE_ANNOTATION = FqName("androidx.compose.runtime.Composable")
    }
}

@OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
private class BugseeComposeTransformer(
    private val pluginContext: IrPluginContext,
    private val bugseeTagFunction: IrSimpleFunctionSymbol?,
    private val bugseeSecureFunction: IrSimpleFunctionSymbol?,
    private val modifierClass: IrClassSymbol,
    private val modifierCompanionSymbol: IrClassSymbol,
    private val composableAnnotation: FqName
) : IrElementTransformerVoidWithContext() {

    /** Stack of enclosing composable function names. */
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

        // Safe cast — super.visitCall may return a different expression type.
        if (result !is IrCall) {
            return result
        }

        // Both passes only apply inside user @Composable functions.
        if (composableNameStack.isEmpty()) {
            return result
        }

        val callee = result.symbol.owner
        val calleeFqName = callee.fqNameWhenAvailable?.asString() ?: return result

        // The Modifier parameter index is shared between both passes — find
        // it once. If the callee has no Modifier parameter, neither pass
        // can do anything.
        val modifierParamIndex = findModifierParameterIndex(callee)
        if (modifierParamIndex < 0) {
            return result
        }

        // ----- Pass 1: secure-field injection -----
        // Run BEFORE tag injection so the final modifier chain is
        // `userMod.bugseeSecure().bugseeTag(name)`.
        //
        // Unlike tag injection, this pass deliberately does NOT skip
        // androidx.* callees — instrumenting calls TO androidx TextField
        // is the entire point. We DO still skip kotlin/com.bugsee/google
        // packages to avoid feedback loops on our own helpers.
        if (bugseeSecureFunction != null
                && !shouldSkipForSecure(calleeFqName)
                && isSensitiveTextFieldCall(calleeFqName, callee, result)) {
            wrapModifierArg(result, modifierParamIndex, bugseeSecureFunction) { builder, _ ->
                // bugseeSecure() takes no value arguments — only the
                // Modifier extension receiver.
            }
        }

        // ----- Pass 2: tag injection -----
        // Skip framework and Bugsee packages — only tag user composables.
        if (bugseeTagFunction != null && !shouldSkipForTag(calleeFqName)) {
            val tag = composableNameStack.last()
            wrapModifierArg(result, modifierParamIndex, bugseeTagFunction) { builder, call ->
                call.putValueArgument(0, builder.irString(tag))
            }
        }

        return result
    }

    /**
     * Wraps the `Modifier` value argument at [modifierParamIndex] of [call]
     * with a chained call to [extensionFunction]. If no Modifier argument
     * was supplied (i.e. the user is relying on the default), inserts
     * `Modifier.<extensionFunction>(...)` as the new argument.
     *
     * The optional [configureCall] block runs after the new IrCall is
     * built and is used to populate the extension function's value
     * arguments (e.g. the tag string for `bugseeTag`).
     */
    private fun wrapModifierArg(
        call: IrCall,
        modifierParamIndex: Int,
        extensionFunction: IrSimpleFunctionSymbol,
        configureCall: (DeclarationIrBuilder, IrCall) -> Unit
    ) {
        val currentModifierArg = call.getValueArgument(modifierParamIndex)
        val builder = DeclarationIrBuilder(
            pluginContext, call.symbol, call.startOffset, call.endOffset
        )

        val wrapped: IrExpression = if (currentModifierArg == null) {
            // No modifier supplied — inject Modifier.<ext>(...) using the
            // companion object as the extension receiver.
            builder.irCall(extensionFunction).apply {
                extensionReceiver = builder.irGetObject(modifierCompanionSymbol)
                configureCall(builder, this)
            }
        } else {
            // Modifier already supplied — chain on top of it.
            builder.irCall(extensionFunction).apply {
                extensionReceiver = currentModifierArg
                configureCall(builder, this)
            }
        }

        call.putValueArgument(modifierParamIndex, wrapped)
    }

    /**
     * Returns the index of the first non-vararg `Modifier` parameter, or
     * -1 if the callee does not accept a Modifier.
     */
    private fun findModifierParameterIndex(function: IrFunction): Int {
        for (i in 0 until function.valueParameters.size) {
            val param = function.valueParameters[i]
            if (param.isVararg) continue

            val type = param.type
            if (type.classFqName?.asString() == "androidx.compose.ui.Modifier") {
                return i
            }
            if (type.isSubtypeOfClass(modifierClass)) {
                return i
            }
        }
        return -1
    }

    /**
     * Returns `true` if the callee at [calleeFqName] is a known sensitive
     * text-field composable AND its arguments contain a statically
     * detectable [PasswordVisualTransformation]. The check is conservative:
     * dynamic / computed transformations are NOT detected, and the runtime
     * scanner picks them up via Compose's own `SemanticsProperties.Password`
     * (set automatically by `PasswordVisualTransformation`).
     */
    private fun isSensitiveTextFieldCall(
        calleeFqName: String,
        callee: IrFunction,
        call: IrCall
    ): Boolean {
        if (calleeFqName !in SENSITIVE_TEXT_FIELD_CALLEES) {
            return false
        }

        // Find the index of the visualTransformation parameter.
        var visualTransformationIndex = -1
        for (i in 0 until callee.valueParameters.size) {
            if (callee.valueParameters[i].name.asString() == "visualTransformation") {
                visualTransformationIndex = i
                break
            }
        }
        if (visualTransformationIndex < 0) {
            return false
        }

        val arg = call.getValueArgument(visualTransformationIndex) ?: return false
        return isPasswordVisualTransformation(arg)
    }

    /**
     * Recognizes a `PasswordVisualTransformation` argument by checking
     * the static type of the expression against
     * `androidx.compose.ui.text.input.PasswordVisualTransformation`.
     * <p>
     * This single check handles both common shapes uniformly:
     * - Direct constructor calls: an `IrConstructorCall` whose return
     *   type is the constructed class.
     * - Variable references after the Compose compiler plugin hoists the
     *   constructor call into a temporary local: an `IrGetValue` whose
     *   declared type is the concrete transformation class. This is the
     *   prevalent shape in compiled `@Composable` call sites.
     *
     * Conservative: dynamic / conditional values whose static type is
     * the `VisualTransformation` interface (not the concrete class) are
     * NOT detected — those rely on the runtime fallback path via
     * Compose's own `SemanticsProperties.Password`, which is set
     * automatically by Compose for password text fields regardless of
     * the source-level expression shape.
     */
    private fun isPasswordVisualTransformation(expr: IrExpression): Boolean {
        return expr.type.classFqName?.asString() == PASSWORD_VISUAL_TRANSFORMATION_FQN
    }

    /**
     * Skip rule for the secure-injection pass: do NOT process calls into
     * the Kotlin runtime, our own helpers, Google services, or recursion
     * into the bugsee compose runtime itself. Compose framework calls
     * (`androidx.compose.*`) are explicitly NOT skipped — instrumenting
     * those is the point of this pass.
     */
    private fun shouldSkipForSecure(fqName: String): Boolean {
        return fqName.startsWith("kotlin.") ||
                fqName.startsWith("kotlinx.") ||
                fqName.startsWith("com.bugsee.")
    }

    /**
     * Skip rule for the tag-injection pass: only tag user composables, so
     * skip everything in framework / runtime / our own packages.
     */
    private fun shouldSkipForTag(fqName: String): Boolean {
        return fqName.startsWith("androidx.") ||
                fqName.startsWith("com.bugsee.") ||
                fqName.startsWith("kotlinx.") ||
                fqName.startsWith("kotlin.") ||
                fqName.startsWith("com.google.android.")
    }

    private companion object {
        const val PASSWORD_VISUAL_TRANSFORMATION_FQN =
            "androidx.compose.ui.text.input.PasswordVisualTransformation"

        /**
         * Allowlist of `TextField`-family composables we attempt to mark
         * as secure when their `visualTransformation` is a
         * [PasswordVisualTransformation]. Covers Material 1, Material 3,
         * and the Foundation BasicTextField variants.
         */
        val SENSITIVE_TEXT_FIELD_CALLEES = setOf(
            "androidx.compose.material.TextField",
            "androidx.compose.material.OutlinedTextField",
            "androidx.compose.material3.TextField",
            "androidx.compose.material3.OutlinedTextField",
            "androidx.compose.foundation.text.BasicTextField",
            "androidx.compose.foundation.text2.BasicTextField"
        )
    }
}
