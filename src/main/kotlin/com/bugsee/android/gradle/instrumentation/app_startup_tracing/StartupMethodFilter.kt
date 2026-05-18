package com.bugsee.android.gradle.instrumentation.app_startup_tracing

/**
 * `(name, descriptor)` pair identifying a JVM method. Matches via
 * structural equality so it slots straight into `Set` membership tests
 * inside [StartupMethodFilter].
 */
internal data class MethodKey(
    val name: String,
    val descriptor: String,
)

/**
 * Categories of classes the app-startup tracing transform considers.
 *
 * A class can belong to more than one kind (e.g. an `Application`
 * subclass that also implements `androidx.startup.Initializer` for some
 * reason) — the filter unions the per-kind method sets.
 */
internal enum class ClassKind {
    APPLICATION,
    CONTENT_PROVIDER,
    INITIALIZER,
    COMPONENT_REGISTRAR,
    CONFIGURATION_PROVIDER,
}

/**
 * Per-kind table of methods to instrument at MINIMAL tier and above.
 *
 * Method descriptors use JVM internal format (`L<internal/name>;`). When
 * a class qualifies for multiple kinds, every method in any of the
 * qualifying kinds' sets is a candidate.
 *
 * A method is also skipped — regardless of kind membership — if its
 * descriptor matches Kotlin's `suspend` shape (last parameter
 * {@code Lkotlin/coroutines/Continuation;} and return type
 * {@code Ljava/lang/Object;}). The bytecode generated for {@code suspend}
 * functions is a state-machine dispatcher whose linear semantics don't
 * survive naive try/finally injection; instrumenting them is out of
 * scope.
 */
internal object StartupMethodFilter {

    private val APPLICATION_METHODS: Set<MethodKey> = setOf(
        MethodKey("attachBaseContext", "(Landroid/content/Context;)V"),
        MethodKey("onCreate", "()V"),
    )

    private val CONTENT_PROVIDER_METHODS: Set<MethodKey> = setOf(
        MethodKey("attachInfo", "(Landroid/content/Context;Landroid/content/pm/ProviderInfo;)V"),
        MethodKey("onCreate", "()Z"),
    )

    private val INITIALIZER_METHODS: Set<MethodKey> = setOf(
        MethodKey("create", "(Landroid/content/Context;)Ljava/lang/Object;"),
    )

    private val COMPONENT_REGISTRAR_METHODS: Set<MethodKey> = setOf(
        MethodKey("getComponents", "()Ljava/util/List;"),
    )

    private val CONFIGURATION_PROVIDER_METHODS: Set<MethodKey> = setOf(
        MethodKey("getWorkManagerConfiguration", "()Landroidx/work/Configuration;"),
    )

    /** Union of methods to instrument given the set of kinds the class qualifies for. */
    fun candidateMethodsFor(kinds: Set<ClassKind>): Set<MethodKey> {
        if (kinds.isEmpty()) return emptySet()
        val result = LinkedHashSet<MethodKey>()
        for (kind in kinds) {
            result += methodsForKind(kind)
        }
        return result
    }

    private fun methodsForKind(kind: ClassKind): Set<MethodKey> = when (kind) {
        ClassKind.APPLICATION -> APPLICATION_METHODS
        ClassKind.CONTENT_PROVIDER -> CONTENT_PROVIDER_METHODS
        ClassKind.INITIALIZER -> INITIALIZER_METHODS
        ClassKind.COMPONENT_REGISTRAR -> COMPONENT_REGISTRAR_METHODS
        ClassKind.CONFIGURATION_PROVIDER -> CONFIGURATION_PROVIDER_METHODS
    }

    /**
     * True if [descriptor] is a Kotlin `suspend` function's JVM signature
     * — last parameter type is {@code Lkotlin/coroutines/Continuation;}
     * and return type is {@code Ljava/lang/Object;}. Both conditions
     * must hold; either alone is not enough (lots of methods take a
     * Continuation as a regular parameter, lots of methods return
     * Object).
     */
    fun isSuspendDescriptor(descriptor: String): Boolean {
        val returnTypeIdx = descriptor.lastIndexOf(')')
        if (returnTypeIdx < 0) return false
        if (descriptor.substring(returnTypeIdx + 1) != "Ljava/lang/Object;") return false
        return descriptor.substring(0, returnTypeIdx)
            .endsWith("Lkotlin/coroutines/Continuation;")
    }
}
