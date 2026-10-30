package com.bugsee.android.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.build.event.BuildEventsListenerRegistry
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import javax.inject.Inject

/**
 * Plugin entry point (`implementation-class`): deliberately thin and free of Android Gradle Plugin
 * (AGP) types.
 *
 * Gradle instantiates a plugin through a generated, decorated subclass, which it builds by reflecting
 * over this class's methods and so loading every type they mention. AGP is `compileOnly` (the plugin
 * must not pin the consumer's AGP version), so one AGP type here would fail applying the plugin in a
 * module with no AGP on its classpath (e.g. a plain `kotlin("jvm")` project) with a raw
 * `NoClassDefFoundError` before any Bugsee code runs (#161). So this class only checks that AGP is
 * loadable and, when it is, delegates to [BugseePluginImpl], which holds all the AGP-aware code.
 *
 * It still implements [KotlinCompilerPluginSupportPlugin], because the Kotlin Gradle plugin discovers
 * compiler plugins by that interface on the applied plugin instance; Kotlin types are safe here since
 * a Kotlin plugin is what makes this a Kotlin project at all.
 */
abstract class BugseePlugin : Plugin<Project>, KotlinCompilerPluginSupportPlugin {

    // Null until apply() finds AGP; while null every compiler-plugin hook stands down.
    private var impl: BugseePluginImpl? = null

    // Injected by Gradle; handed to the implementation (hooks the BuildTimingService into the
    // task-completion event stream).
    @get:Inject
    abstract val listenerRegistry: BuildEventsListenerRegistry

    /** Test seam: the real plugin, or null when AGP was not on the classpath. */
    internal fun implementation(): BugseePluginImpl? = impl

    override fun apply(project: Project) {
        if (!isAgpLoadable()) {
            // The settings extension (and with it any opt-out) is part of the AGP-aware plugin, so
            // this cannot be silenced; it is one line per module.
            project.logger.warn(noAgpMessage(project.path))
            // AGP exists in the module but is not visible from here: Bugsee was loaded in a parent
            // classloader scope (e.g. `id("com.bugsee.android.gradle") apply false` in the root, AGP
            // only in :app). Warning and carrying on would ship an app with no manifest UUID, no
            // symbol upload and no instrumentation, so this one case fails, with a message.
            project.pluginManager.withPlugin("com.android.application") {
                throw GradleException(agpNotVisibleMessage(project.path))
            }
            return
        }
        impl = BugseePluginImpl(listenerRegistry).also { it.apply(project) }
    }

    // --- KotlinCompilerPluginSupportPlugin ---

    override fun getCompilerPluginId(): String = PluginMetadata.COMPILER_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact =
        impl?.getPluginArtifact()
            // Never reached without an implementation (isApplicable refuses first); keeps the method total.
            ?: SubpluginArtifact("com.bugsee", ComposeKotlinCompatibility.ARTIFACT_BASE, PluginMetadata.VERSION)

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        impl?.isApplicable(kotlinCompilation) ?: false

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>
    ): Provider<List<SubpluginOption>> =
        impl?.applyToCompilation(kotlinCompilation)
            ?: kotlinCompilation.target.project.provider { emptyList() }

    internal companion object {
        // The DSL entry point of the variant API the plugin needs; absent exactly when AGP is.
        private const val AGP_PROBE_CLASS = "com.android.build.api.variant.AndroidComponentsExtension"

        /** True when AGP is visible to this plugin's classloader. Loads the probe class without initializing it. */
        internal fun isAgpLoadable(): Boolean = try {
            Class.forName(AGP_PROBE_CLASS, false, BugseePlugin::class.java.classLoader)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }

        internal fun noAgpMessage(projectPath: String): String =
            "Bugsee: the Android Gradle Plugin is not on the plugin classpath of project $projectPath, " +
                "so the Bugsee plugin does nothing here. Apply 'com.bugsee.android.gradle' only to Android " +
                "application modules, in a build where AGP is on the same plugin classpath as Bugsee (or a parent's)."

        internal fun agpNotVisibleMessage(projectPath: String): String =
            "Bugsee: 'com.android.application' is applied in project $projectPath, but the Android Gradle " +
                "Plugin is not visible to the Bugsee plugin, so nothing could be wired. Declare both plugins " +
                "in the same plugins {} block, or declare AGP in the root project's plugins {} block " +
                "(`id(\"com.android.application\") version \"...\" apply false`) alongside Bugsee."
    }
}
