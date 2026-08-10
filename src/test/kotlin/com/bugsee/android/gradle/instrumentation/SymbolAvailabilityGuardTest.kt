package com.bugsee.android.gradle.instrumentation

import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationContext
import com.bugsee.android.gradle.instrumentation.log.LogClassVisitorFactory
import com.bugsee.android.gradle.instrumentation.okhttp.OkHttpClassVisitorFactory
import com.bugsee.android.gradle.instrumentation.okhttp.OkHttpInstrumentationParameters
import org.gradle.api.provider.Property
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every lane must stand down when the SDK on the classpath lacks the symbol it injects.
 *
 * This is the last link in the chain: [SdkClassProbe] decides presence and
 * [SdkSymbolAvailability] carries it into the transform, but none of that matters unless the
 * factories actually honour the flag. A regression here is silent — instrumentation would
 * resume against an SDK that cannot link it, and the failure surfaces in the CONSUMER's app
 * (NoClassDefFoundError at runtime, or an R8 "Missing class" build failure), never in ours.
 */
class SymbolAvailabilityGuardTest {

    private val objects = ProjectBuilder.builder().build().objects

    private fun classData(name: String) = object : ClassData {
        override val className = name
        override val classAnnotations = emptyList<String>()
        override val interfaces = emptyList<String>()
        override val superClasses = emptyList<String>()
    }

    private fun <T : BugseeInstrumentationParameters> params(
        type: Class<T>,
        available: Boolean,
    ): Property<T> {
        val p = objects.newInstance(type)
        p.excludes.set(emptySet<String>())
        p.symbolAvailable.set(available)
        return objects.property(type).value(p)
    }

    private class TestLog(
        override val parameters: Property<BugseeInstrumentationParameters>,
    ) : LogClassVisitorFactory() {
        override val instrumentationContext: InstrumentationContext
            get() = throw UnsupportedOperationException("not used by isInstrumentable")
    }

    private class TestOkHttp(
        override val parameters: Property<OkHttpInstrumentationParameters>,
    ) : OkHttpClassVisitorFactory() {
        override val instrumentationContext: InstrumentationContext
            get() = throw UnsupportedOperationException("not used by isInstrumentable")
    }

    // A class that would ordinarily be instrumented: outside com.bugsee.*, outside okhttp3.*.
    private val ordinary = "com.thirdparty.Client"

    @Test
    fun `log lane instruments when its adapter is present`() {
        val factory = TestLog(params(BugseeInstrumentationParameters::class.java, available = true))
        assertTrue(factory.isInstrumentable(classData(ordinary)))
    }

    @Test
    fun `log lane stands down when its adapter is absent`() {
        val factory = TestLog(params(BugseeInstrumentationParameters::class.java, available = false))
        assertFalse(
            factory.isInstrumentable(classData(ordinary)),
            "instrumenting against an SDK without BugseeLogAdapter would inject an unlinkable " +
                "call at every android.util.Log site — the app dies on its first log statement",
        )
    }

    @Test
    fun `okhttp lane instruments when its interceptor is present`() {
        val factory = TestOkHttp(params(OkHttpInstrumentationParameters::class.java, available = true))
        assertTrue(factory.isInstrumentable(classData(ordinary)))
    }

    @Test
    fun `okhttp lane stands down when its interceptor is absent`() {
        val factory = TestOkHttp(params(OkHttpInstrumentationParameters::class.java, available = false))
        assertFalse(factory.isInstrumentable(classData(ordinary)))
    }

    /**
     * An unset flag must behave exactly as before this mechanism existed. Any lane that has not
     * opted in — or a parameters instance built by older cached state — must keep instrumenting
     * rather than silently disabling itself.
     */
    @Test
    fun `an unset flag is permissive`() {
        val p = objects.newInstance(BugseeInstrumentationParameters::class.java)
        p.excludes.set(emptySet<String>())
        // symbolAvailable deliberately left unset
        val factory = TestLog(objects.property(BugseeInstrumentationParameters::class.java).value(p))
        assertTrue(factory.isInstrumentable(classData(ordinary)))
    }
}
