package com.bugsee.android.gradle.instrumentation.app_startup_tracing

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import org.objectweb.asm.ClassVisitor

/**
 * ASM class visitor factory for app-startup tracing.
 *
 * **MINIMAL tier (Phase 5):** wraps the body of pre-declared init-time
 * methods on `Application` / `ContentProvider` subclasses, AndroidX
 * `Initializer`s, Firebase `ComponentRegistrar`s, and WorkManager
 * `Configuration.Provider`s with calls to
 * `BugseeAppStartupDispatcher.onMethodStart/End`. Higher tiers (Phase 6
 * for `STANDARD`, Phase 7 for `DETAILED`, Phase 8 for `FULL`) will layer
 * on top of this filter.
 *
 * Two gates:
 *  - **[isInstrumentable]** does the AGP-side class filtering — package
 *    prefix denylist + superclass / interface checks.
 *  - **[createClassVisitor]** does the SDK-side probe — refuses to emit
 *    bytecode that references `BugseeAppStartupDispatcher` if that class
 *    is missing from the runtime classpath (older SDKs without the
 *    Phase 1 dispatcher). The bare class-name check via
 *    [ClassContext.loadClassData] is intentionally simple — no version
 *    parsing, just "is the type resolvable?".
 */
abstract class AppStartupTracingClassVisitorFactory :
    AsmClassVisitorFactory<AppStartupTracingParameters> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        val params = parameters.get()
        val dispatcherClassFqn = params.targetClass.get()

        val tier = resolvedTier
        if (tier == StartupTier.OFF) {
            return nextClassVisitor
        }

        // SDK presence probe — refuse to inject calls against a class that
        // is not on the runtime classpath. Without this, an older SDK
        // paired with this plugin would produce NoClassDefFoundError at
        // every wrapped method's first call.
        if (classContext.loadClassData(dispatcherClassFqn) == null) {
            return nextClassVisitor
        }

        // Recompute class kinds for this specific class (isInstrumentable
        // was a coarse opt-in; we still need the kind set here to pick
        // the right candidate methods).
        val current = classContext.currentClassData
        val kinds = classifyKinds(current)
        if (kinds.isEmpty()) {
            return nextClassVisitor
        }
        val candidates = StartupMethodFilter.candidateMethodsFor(kinds)
        if (candidates.isEmpty()) {
            return nextClassVisitor
        }

        return AppStartupTracingClassVisitor(
            apiVersion = instrumentationContext.apiVersion.get(),
            nextClassVisitor = nextClassVisitor,
            candidateMethods = candidates,
            dispatcherInternalName = dispatcherClassFqn.replace('.', '/'),
            tier = tier,
        )
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        if (isInDenylist(classData.className)) {
            return false
        }
        if (classifyKinds(classData).isNotEmpty()) {
            return true
        }
        // FULL tier extends instrumentability to ANY non-denylisted
        // class so the visitor can scan for @BugseeTrace-annotated
        // methods. AGP's ClassData exposes class-level annotations
        // cheaply but NOT method-level ones, and @BugseeTrace is a
        // method/constructor target, so we can't pre-filter here.
        // The visitor will buffer methods at FULL tier and discard
        // the buffer for any method that turns out NOT to be
        // annotated. Build-time cost is bounded per-method (MethodNode
        // is GC'd as soon as visitEnd completes) but real in
        // aggregate. **Phase 9** plans to add a constant-pool
        // pre-scan optimization (skip classes whose raw bytecode
        // doesn't contain the literal string "BugseeTrace") to cut
        // this cost by 10-100× depending on annotation density.
        return resolvedTier.picksUpAnnotated()
    }

    private fun classifyKinds(classData: ClassData): Set<ClassKind> {
        val kinds = HashSet<ClassKind>(2)
        val supers = classData.superClasses
        // superClasses is the transitive chain (immediate parent first,
        // through java.lang.Object). After Hilt's class transform runs
        // ahead of us, generated subclasses (Hilt_MyApp) still have
        // android.app.Application transitively, so both the user class
        // and the Hilt-generated parent are picked up — and they nest
        // naturally in the resulting span tree.
        if ("android.app.Application" in supers) {
            kinds += ClassKind.APPLICATION
        }
        if ("android.content.ContentProvider" in supers) {
            kinds += ClassKind.CONTENT_PROVIDER
        }
        val ifaces = classData.interfaces
        if ("androidx.startup.Initializer" in ifaces) {
            kinds += ClassKind.INITIALIZER
        }
        if ("com.google.firebase.components.ComponentRegistrar" in ifaces) {
            kinds += ClassKind.COMPONENT_REGISTRAR
        }
        if ("androidx.work.Configuration\$Provider" in ifaces) {
            kinds += ClassKind.CONFIGURATION_PROVIDER
        }
        return kinds
    }

    private fun isInDenylist(className: String): Boolean {
        for (prefix in PACKAGE_DENYLIST) {
            if (className.startsWith(prefix)) {
                return true
            }
        }
        return false
    }

    /**
     * Cached tier value: AGP invokes `isInstrumentable` and
     * `createClassVisitor` once per class in scope (thousands of times
     * per build at FULL tier). Re-parsing the `Property<String>` each
     * call would do thousands of `StartupTier.parse` calls per build
     * for no useful reason — the tier is build-time-constant once
     * `apply(variant)` has set it. Falls back to
     * [StartupTier.DEFAULT] defensively (the resolver already
     * validated the value upstream).
     */
    private val resolvedTier: StartupTier by lazy {
        StartupTier.parse(parameters.get().tier.orNull) ?: StartupTier.DEFAULT
    }

    private companion object {
        /**
         * Prefix denylist short-circuits [isInstrumentable] before
         * touching the superclass / interface lists, keeping build-time
         * cost low for the vast majority of classes (JDK, Kotlin runtime,
         * Android framework stubs, our own SDK code).
         */
        private val PACKAGE_DENYLIST = arrayOf(
            "com.bugsee.",
            "android.",
            "kotlin.",
            "kotlinx.",
            "java.",
            "androidx.compose.runtime.",
        )
    }
}
