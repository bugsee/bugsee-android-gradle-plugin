package com.bugsee.android.gradle.instrumentation.compose_input

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.bugsee.android.gradle.instrumentation.BugseeInstrumentationParameters
import com.bugsee.android.gradle.instrumentation.util.InstrumentationExcludes
import org.objectweb.asm.ClassVisitor

/**
 * ASM class visitor factory for Compose touch input instrumentation.
 *
 * Instruments `AndroidComposeView.dispatchTouchEvent` to call
 * `BugseeComposeInputAdapter.onComposeTouch` at method entry.
 *
 * Note: [isInstrumentable] accepts all `androidx.compose.ui.platform` classes
 * because AGP may not pass every class through the filter individually (observed
 * with large classes like `AndroidComposeView`). The exact class name check
 * happens inside [createClassVisitor] instead.
 *
 * SDK presence is verified ONCE at configuration time via
 * [ComposeInputInstrumentation.shouldApply]'s
 * `DependencyDetector.hasBugseeDependency("bugsee-android")` check (the adapter
 * class ships in the core SDK). [createClassVisitor] deliberately does NOT
 * re-probe per-class via [ClassContext.loadClassData] — see the note there.
 */
abstract class ComposeInputClassVisitorFactory :
    AsmClassVisitorFactory<BugseeInstrumentationParameters> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor
    ): ClassVisitor {
        // Do NOT re-probe per-class via [ClassContext.loadClassData] for the
        // adapter class here. AGP processes project classes and external-JAR
        // classes through DIFFERENT artifact-transform isolation boundaries,
        // each with its own classpath. `AndroidComposeView` ONLY ever exists
        // in the third-party `androidx.compose.ui:ui` JAR, whose transform
        // boundary does NOT see the consumer's `:library` / `bugsee-android`
        // dependency — so a `loadClassData(adapter)` probe ALWAYS returned
        // `null` for the one class this instrumentation targets and silently
        // skipped it entirely (touch capture never fired). SDK presence is
        // instead gated once at apply() time via
        // [ComposeInputInstrumentation.shouldApply]. SDK version skew would
        // surface as a `NoClassDefFoundError` at runtime pointing at the
        // adapter FQN, which is sufficient. (Same failure mode fixed in
        // AppStartupTracingClassVisitorFactory.)
        val className = classContext.currentClassData.className
        if (className != ANDROID_COMPOSE_VIEW_CLASS) {
            return nextClassVisitor
        }
        return ComposeInputClassVisitor(nextClassVisitor, className)
    }

    override fun isInstrumentable(classData: ClassData): Boolean {
        // Graceful degradation: the SDK on this classpath may predate the symbol this lane
        // injects. Emitting the call anyway would put an unlinkable INVOKESTATIC into the host
        // app's own bytecode — NoClassDefFoundError at runtime, or an R8 "Missing class" failure.
        // Resolved once from the actual artifacts (see SdkSymbolAvailability), not per class.
        if (!parameters.get().symbolAvailable.getOrElse(true)) {
            return false
        }
        if (InstrumentationExcludes.isExcluded(classData.className, parameters.get().excludes.get())) {
            return false
        }
        return classData.className.startsWith(COMPOSE_PLATFORM_PACKAGE)
    }

    companion object {
        private const val ANDROID_COMPOSE_VIEW_CLASS =
            "androidx.compose.ui.platform.AndroidComposeView"
        private const val COMPOSE_PLATFORM_PACKAGE =
            "androidx.compose.ui.platform."
    }
}
