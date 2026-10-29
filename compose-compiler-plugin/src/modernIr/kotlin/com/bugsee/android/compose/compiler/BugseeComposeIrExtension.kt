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
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
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
 * **Kotlin 2.2+ variant.** Behaviourally identical to the `legacyIr` source set; it differs only in
 * which IR API it speaks.
 *
 * Kotlin 2.2 unified receivers and value parameters into a single `IrFunction.parameters` list with
 * an [IrParameterKind] per entry, and a matching `IrMemberAccessExpression.arguments` list indexed
 * the same way. The pre-2.2 spelling (`valueParameters`, `getValueArgument`/`putValueArgument`,
 * `extensionReceiver`) is deprecated-as-error in 2.2/2.3 and removed outright in 2.4, so it cannot
 * be kept. Conversely the new API does not exist before 2.2, so the two spellings cannot live in one
 * file — hence the duplicate source set rather than a shared one.
 *
 * The important consequence for readers: **indices here are indices into `parameters`**, which
 * include dispatch/extension/context entries, not the old value-argument numbering. Anything
 * positional is resolved by [IrParameterKind] rather than by a hardcoded offset.
 *
 * IR generation extension that runs two independent passes over every
 * `@Composable` function call, each gated by its own configuration flag:
 *
 * 1. **Tag injection** ([tagInjectionEnabled]) — wraps the `Modifier`
 *    parameter of every composable call inside a user `@Composable`
 *    function with `.bugseeTag("<EnclosingComposableName>")`, but only when the
 *    CALLEE is itself `@Composable`: plain helpers that take a `Modifier` are
 *    left alone. Lets the runtime view-hierarchy walker map LayoutNodes back
 *    to source composable names.
 *
 *    When the call OMITS `modifier`, the slot is rewritten only if the callee's declared default is
 *    statically visible and is the bare `Modifier` companion (issues #138, #146): the injected
 *    `Modifier.bugseeTag(..)` would otherwise make the slot a supplied argument and silently drop the
 *    callee's own default (`Modifier.fillMaxWidth()`) when this transform runs ahead of Compose's
 *    default-argument lowering. Trade-off: omitted-`modifier` calls to a callee in another module
 *    (default not visible) are untagged. Chaining onto the callee's default was rejected: a library
 *    default is not available to the plugin and a same-module one may read other parameters.
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
            wrapModifierArg(result, modifierParamIndex, bugseeSecureFunction, defaultKnownBare = true) { builder, _ ->
                // bugseeSecure() takes no value arguments — only the
                // Modifier extension receiver.
            }
        }

        // ----- Pass 2: tag injection -----
        // Skip framework and Bugsee packages — only tag user composables. The CALLEE must itself be
        // @Composable: tagging exists to name layout nodes a composable emits, and a plain helper
        // that merely takes a `Modifier` (`fun describe(m: Modifier)`) would otherwise have its
        // argument, or its own default when omitted, rewritten (issue #147). Calls THROUGH a
        // `@Composable` function type were never tagged and still are not: `invoke` is declared on
        // `FunctionN` (a `kotlin.*` callee with a generic, not `Modifier`-typed, parameter). Library
        // callees are resolved through lazy IR whose annotations are read from the dependency's
        // class files; that lookup is verified on every supported compiler line by the variant tests.
        if (bugseeTagFunction != null && !shouldSkipForTag(calleeFqName) &&
            callee.hasAnnotation(composableAnnotation)
        ) {
            val tag = composableNameStack.last()
            wrapModifierArg(result, modifierParamIndex, bugseeTagFunction) { builder, call ->
                val tagIndex = call.symbol.owner.parameters
                    .indexOfFirst { it.kind == IrParameterKind.Regular }
                if (tagIndex >= 0) {
                    call.arguments[tagIndex] = builder.irString(tag)
                }
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
     * An omitted slot is rewritten only when the callee's default is the bare `Modifier`, which is
     * either visible in IR or asserted by the caller through [defaultKnownBare] (the secure pass,
     * whose allowlisted androidx text fields all declare `modifier: Modifier = Modifier`, so replacing the
     * default loses nothing even though a library callee's declaration is not visible).
     *
     * The optional [configureCall] block runs after the new IrCall is
     * built and is used to populate the extension function's value
     * arguments (e.g. the tag string for `bugseeTag`).
     */
    private fun wrapModifierArg(
        call: IrCall,
        modifierParamIndex: Int,
        extensionFunction: IrSimpleFunctionSymbol,
        defaultKnownBare: Boolean = false,
        configureCall: (DeclarationIrBuilder, IrCall) -> Unit
    ) {
        val currentModifierArg = call.arguments.getOrNull(modifierParamIndex)
        // A null-at-runtime argument here is NOT a user-supplied Modifier. It is what
        // Compose's default-argument lowering leaves behind for an omitted `modifier`
        // — an IrComposite(origin = DEFAULT_VALUE) wrapping a null constant (see
        // isDefaultedOrNullArgument for the verified shape per compiler version) —
        // paired with a $default bit telling the callee to substitute the default. If
        // that lowering ran before this transform, chaining onto it emitted
        // `null.bugseeTag(...)`, and the non-null receiver's intrinsic check crashed the
        // host app at runtime with
        //   "Parameter specified as non-null is null: ... parameter <this>"
        // — reported against 4.0.5 after 154 sites were injected into one app.
        //
        // Skip the site rather than substituting the companion: the callee's $default
        // fixup overwrites whatever we pass, so an injected chain there is dead code
        // that cannot tag anything. Sites where the argument is genuinely ABSENT (this
        // transform running first) are unaffected and still get the companion below.
        if (currentModifierArg != null && currentModifierArg.isDefaultedOrNullArgument()) {
            return
        }

        // Omitted `modifier`: the injected chain would replace the callee's own default, so only
        // proceed when that default is known to be the bare `Modifier` (issue #146; same guard as the
        // Kotlin 1.9 path, #138). When this transform runs ahead of Compose's default-argument
        // lowering, `Modifier.bugseeTag(..)` turns the omitted slot into a SUPPLIED argument and the
        // callee's `Modifier.fillMaxWidth()` (or any non-bare default) is never evaluated. A default
        // that is not statically visible (callee from another module: lazy IR has no default body)
        // or is not the bare companion is skipped: an untagged site is recoverable, a silently
        // dropped default modifier is not.
        if (currentModifierArg == null && !defaultKnownBare &&
            !call.symbol.owner.hasBareModifierDefault(modifierParamIndex)
        ) {
            return
        }

        val builder = DeclarationIrBuilder(
            pluginContext, call.symbol, call.startOffset, call.endOffset
        )

        // Kotlin 2.2 folded receivers into the single `parameters`/`arguments` list, so the
        // extension receiver is written at the index of the ExtensionReceiver parameter rather
        // than through a dedicated property.
        val receiverIndex = extensionFunction.owner.parameters
            .indexOfFirst { it.kind == IrParameterKind.ExtensionReceiver }
        if (receiverIndex < 0) {
            // Not an extension function — the runtime helper is not the shape we inject against.
            return
        }

        val wrapped: IrExpression = builder.irCall(extensionFunction).apply {
            // No modifier supplied — start the chain from the Modifier companion; otherwise
            // chain on top of what the caller passed.
            arguments[receiverIndex] =
                currentModifierArg ?: builder.irGetObject(modifierCompanionSymbol)
            configureCall(builder, this)
        }

        call.arguments[modifierParamIndex] = wrapped
    }

    /** Whether the parameter at [index] is declared with the bare `Modifier` companion as its default. */
    private fun IrFunction.hasBareModifierDefault(index: Int): Boolean {
        val default = parameters[index].defaultValue?.expression
        return default is IrGetObjectValue && default.symbol == modifierCompanionSymbol
    }

    /**
     * Returns the index of the first non-vararg `Modifier` parameter, or
     * -1 if the callee does not accept a Modifier.
     */
    private fun findModifierParameterIndex(function: IrFunction): Int {
        val parameters = function.parameters
        for (i in parameters.indices) {
            val param = parameters[i]
            // `parameters` also carries dispatch/extension/context entries; only a regular
            // parameter can be the Modifier argument. The index is kept as-is because
            // `arguments` is aligned with `parameters`.
            if (param.kind != IrParameterKind.Regular) continue
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
     * [calleeFqName] with the file-facade class removed. Kotlin 1.9's lazy IR reports a library
     * top-level function WITH its facade (`androidx.compose.material.TextFieldKt.TextField`, issues
     * #149, #151); the 2.x lines this variant serves are expected to report the bare package-qualified
     * name, so this should be a no-op here and is harmless if not; it mirrors the legacyIr variant.
     * The strip is
     * structural: only a top-level `*Kt` class that owns a receiver-less function qualifies, and the
     * allowlist is matched on the full package, so a non-androidx `...Kt.TextField` never matches.
     * Limitation: a facade renamed with `@file:JvmName` that lacks the `Kt` suffix is not stripped.
     */
    private fun withoutFileFacade(callee: IrFunction, calleeFqName: String): String {
        val owner = callee.parent as? IrClass ?: return calleeFqName
        if (owner.parent !is IrPackageFragment ||
            !owner.name.asString().endsWith("Kt") ||
            callee.parameters.any { it.kind == IrParameterKind.DispatchReceiver }
        ) {
            return calleeFqName
        }
        val suffix = "." + owner.name.asString() + "." + callee.name.asString()
        return if (calleeFqName.endsWith(suffix)) {
            calleeFqName.removeSuffix(suffix) + "." + callee.name.asString()
        } else {
            calleeFqName
        }
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
        if (withoutFileFacade(callee, calleeFqName) !in SENSITIVE_TEXT_FIELD_CALLEES) {
            return false
        }

        // Find the index of the visualTransformation parameter.
        var visualTransformationIndex = -1
        val parameters = callee.parameters
        for (i in parameters.indices) {
            val param = parameters[i]
            if (param.kind != IrParameterKind.Regular) continue
            if (param.name.asString() == "visualTransformation") {
                visualTransformationIndex = i
                break
            }
        }
        if (visualTransformationIndex < 0) {
            return false
        }

        val arg = call.arguments.getOrNull(visualTransformationIndex) ?: return false
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
            "androidx.compose.foundation.text.BasicTextField"
        )
        // Deliberately absent (issue #155, verified against real artifacts): there has never been an
        // `androidx.compose.foundation.text2.BasicTextField`. The experimental text2 API shipped in
        // foundation 1.6.0 .. 1.7.0-alpha0x as `text2.BasicTextField2` / `text2.BasicSecureTextField`, then
        // moved into `androidx.compose.foundation.text` (TextFieldState-based `BasicTextField`, 1.7.0+;
        // `BasicSecureTextField`). None of those take a `VisualTransformation`, so the `visualTransformation`
        // parameter lookup in the callee check rejects them, and the secure pass does not apply to them.
    }
}

/**
 * True when [this] argument slot carries no real user-supplied Modifier at
 * runtime. Two shapes qualify:
 *
 *  1. A literal `null` constant ([IrConst] with value `null`) — a source-level
 *     `null` for a nullable parameter, or a lowering that strips the wrapper.
 *  2. The shape Compose's default-argument lowering
 *     (`ComposerParamTransformer.defaultArgumentFor`) synthesizes for an
 *     OMITTED parameter: an [IrComposite] with origin
 *     [IrStatementOrigin.DEFAULT_VALUE] wrapping the type's default value —
 *     which for a reference type like `Modifier` is a `null` constant.
 *     Verified against kotlin-compose-compiler-plugin-embeddable 2.1.0,
 *     2.2.10, 2.3.0 and 2.4.0: all four build exactly
 *     `IrCompositeImpl(offsets, type, DEFAULT_VALUE, listOf(defaultValue))`.
 *     A bare `IrConst`-only check does NOT match this shape — that gap is
 *     what kept the 4.0.5 `null.bugseeTag(...)` crash alive after the first
 *     fix attempt.
 *
 * The origin-less composite-of-null branch is defensive against future origin
 * changes: for a `Modifier` slot anything null at runtime is a defaulted slot,
 * and wrongly skipping costs a missed tag while wrongly chaining costs an NPE
 * in the host app.
 */
private fun IrExpression.isDefaultedOrNullArgument(): Boolean {
    if (this is IrConst && this.value == null) return true
    if (this is IrComposite) {
        if (this.origin == IrStatementOrigin.DEFAULT_VALUE) return true
        val single = this.statements.singleOrNull()
        if (single is IrConst && single.value == null) return true
    }
    return false
}
