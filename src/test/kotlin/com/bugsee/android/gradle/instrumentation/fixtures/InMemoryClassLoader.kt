package com.bugsee.android.gradle.instrumentation.fixtures

/**
 * Classloader that defines classes from in-memory byte arrays.
 *
 * Used by the ASM test harness to load transformed bytecode without
 * touching disk. Falls through to the parent classloader for any class
 * not in the supplied byte-array map, so test fixtures (incl.
 * [com.bugsee.test.fixtures.RecordingStartupDispatcher]) and the JDK
 * remain resolvable.
 *
 * Construct one per test run — defining the same class twice in a single
 * classloader throws `LinkageError`.
 */
internal class InMemoryClassLoader(
    private val classBytes: Map<String, ByteArray>,
    parent: ClassLoader = Thread.currentThread().contextClassLoader
        ?: InMemoryClassLoader::class.java.classLoader,
) : ClassLoader(parent) {

    override fun findClass(name: String): Class<*> {
        val bytes = classBytes[name]
            ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }
}
