package com.bugsee.android.gradle.config

import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

/**
 * Hand-rolled recording Gradle [Logger]. Captures `warn(...)` and
 * `info(...)` calls so tests can assert both presence and absence of
 * specific log lines. Mockito is not on this module's test classpath
 * (see `ChunkedBundleUploaderHttpTest.SilentLogger` for the same
 * Mockito-free shape elsewhere).
 *
 * Tests that don't need to inspect logs can keep using the real
 * `project.logger` via `ProjectBuilder`. This class is for tests that
 * pin log-level + message-content contracts.
 */
internal class RecordingLogger : Logger {
    val warnings: MutableList<String> = mutableListOf()
    val infos: MutableList<String> = mutableListOf()
    private val delegate = Logging.getLogger("BugseeRecordingLogger")

    override fun warn(msg: String) {
        warnings.add(msg)
    }

    override fun info(msg: String?) {
        msg?.let { infos.add(it) }
    }

    // ── Boilerplate delegation ──────────────────────────────────────
    override fun getName(): String = delegate.name
    override fun isLifecycleEnabled(): Boolean = false
    override fun lifecycle(message: String?) {}
    override fun lifecycle(message: String?, vararg objects: Any?) {}
    override fun lifecycle(message: String?, throwable: Throwable?) {}
    override fun isQuietEnabled(): Boolean = false
    override fun quiet(message: String?) {}
    override fun quiet(message: String?, vararg objects: Any?) {}
    override fun quiet(message: String?, throwable: Throwable?) {}
    override fun isEnabled(level: org.gradle.api.logging.LogLevel?): Boolean = false
    override fun log(level: org.gradle.api.logging.LogLevel?, message: String?) {}
    override fun log(level: org.gradle.api.logging.LogLevel?, message: String?, vararg objects: Any?) {}
    override fun log(level: org.gradle.api.logging.LogLevel?, message: String?, throwable: Throwable?) {}
    override fun isTraceEnabled(): Boolean = false
    override fun isTraceEnabled(p: org.slf4j.Marker?): Boolean = false
    override fun trace(msg: String?) {}
    override fun trace(format: String?, arg: Any?) {}
    override fun trace(format: String?, arg1: Any?, arg2: Any?) {}
    override fun trace(format: String?, vararg arguments: Any?) {}
    override fun trace(msg: String?, t: Throwable?) {}
    override fun trace(marker: org.slf4j.Marker?, msg: String?) {}
    override fun trace(marker: org.slf4j.Marker?, format: String?, arg: Any?) {}
    override fun trace(marker: org.slf4j.Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun trace(marker: org.slf4j.Marker?, format: String?, vararg argArray: Any?) {}
    override fun trace(marker: org.slf4j.Marker?, msg: String?, t: Throwable?) {}
    override fun isDebugEnabled(): Boolean = false
    override fun isDebugEnabled(p: org.slf4j.Marker?): Boolean = false
    override fun debug(msg: String?) {}
    override fun debug(format: String?, arg: Any?) {}
    override fun debug(format: String?, arg1: Any?, arg2: Any?) {}
    override fun debug(format: String?, vararg arguments: Any?) {}
    override fun debug(msg: String?, t: Throwable?) {}
    override fun debug(marker: org.slf4j.Marker?, msg: String?) {}
    override fun debug(marker: org.slf4j.Marker?, format: String?, arg: Any?) {}
    override fun debug(marker: org.slf4j.Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun debug(marker: org.slf4j.Marker?, format: String?, vararg arguments: Any?) {}
    override fun debug(marker: org.slf4j.Marker?, msg: String?, t: Throwable?) {}
    override fun isInfoEnabled(): Boolean = true
    override fun isInfoEnabled(p: org.slf4j.Marker?): Boolean = true
    override fun info(format: String?, arg: Any?) { format?.let { infos.add(it) } }
    override fun info(format: String?, arg1: Any?, arg2: Any?) { format?.let { infos.add(it) } }
    override fun info(format: String?, vararg arguments: Any?) { format?.let { infos.add(it) } }
    override fun info(msg: String?, t: Throwable?) { msg?.let { infos.add(it) } }
    override fun info(marker: org.slf4j.Marker?, msg: String?) { msg?.let { infos.add(it) } }
    override fun info(marker: org.slf4j.Marker?, format: String?, arg: Any?) { format?.let { infos.add(it) } }
    override fun info(marker: org.slf4j.Marker?, format: String?, arg1: Any?, arg2: Any?) { format?.let { infos.add(it) } }
    override fun info(marker: org.slf4j.Marker?, format: String?, vararg arguments: Any?) { format?.let { infos.add(it) } }
    override fun info(marker: org.slf4j.Marker?, msg: String?, t: Throwable?) { msg?.let { infos.add(it) } }
    override fun isWarnEnabled(): Boolean = true
    override fun isWarnEnabled(p: org.slf4j.Marker?): Boolean = true
    override fun warn(format: String?, arg: Any?) { format?.let { warnings.add(it) } }
    override fun warn(format: String?, arg1: Any?, arg2: Any?) { format?.let { warnings.add(it) } }
    override fun warn(format: String?, vararg arguments: Any?) { format?.let { warnings.add(it) } }
    override fun warn(msg: String?, t: Throwable?) { msg?.let { warnings.add(it) } }
    override fun warn(marker: org.slf4j.Marker?, msg: String?) { msg?.let { warnings.add(it) } }
    override fun warn(marker: org.slf4j.Marker?, format: String?, arg: Any?) { format?.let { warnings.add(it) } }
    override fun warn(marker: org.slf4j.Marker?, format: String?, arg1: Any?, arg2: Any?) { format?.let { warnings.add(it) } }
    override fun warn(marker: org.slf4j.Marker?, format: String?, vararg arguments: Any?) { format?.let { warnings.add(it) } }
    override fun warn(marker: org.slf4j.Marker?, msg: String?, t: Throwable?) { msg?.let { warnings.add(it) } }
    override fun isErrorEnabled(): Boolean = false
    override fun isErrorEnabled(p: org.slf4j.Marker?): Boolean = false
    override fun error(msg: String?) {}
    override fun error(format: String?, arg: Any?) {}
    override fun error(format: String?, arg1: Any?, arg2: Any?) {}
    override fun error(format: String?, vararg arguments: Any?) {}
    override fun error(msg: String?, t: Throwable?) {}
    override fun error(marker: org.slf4j.Marker?, msg: String?) {}
    override fun error(marker: org.slf4j.Marker?, format: String?, arg: Any?) {}
    override fun error(marker: org.slf4j.Marker?, format: String?, arg1: Any?, arg2: Any?) {}
    override fun error(marker: org.slf4j.Marker?, format: String?, vararg arguments: Any?) {}
    override fun error(marker: org.slf4j.Marker?, msg: String?, t: Throwable?) {}
}
