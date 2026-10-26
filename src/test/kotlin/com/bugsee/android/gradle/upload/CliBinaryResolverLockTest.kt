package com.bugsee.android.gradle.upload

import org.gradle.api.Action
import org.gradle.api.logging.Logging
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #44: `FileChannel.lock` throws OverlappingFileLockException for a
 * second lock in the same JVM, and the resolver used to treat that as "run
 * the body unlocked" — so mapping + native upload tasks in one daemon could
 * download/extract the CLI concurrently.
 */
class CliBinaryResolverLockTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test(timeout = 10_000) fun `concurrent callers in one JVM never overlap`() {
        val lockFile = File(tmp.newFolder("cache"), "triple.lock")
        val threads = 8
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val entered = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map {
                pool.submit {
                    start.await()
                    CliBinaryResolver.withTripleLock(lockFile) {
                        entered.incrementAndGet()
                        maxRunning.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                        Thread.sleep(100)
                        running.decrementAndGet()
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        println("I44 maxConcurrentBodies=${maxRunning.get()} entered=${entered.get()}")
        assertEquals(threads, entered.get())
        assertEquals(1, maxRunning.get(), "bodies must be serialized")
    }

    @Test(timeout = 10_000) fun `waits for a file lock held elsewhere in the JVM instead of running unlocked`() {
        val lockFile = File(tmp.newFolder("cache2"), "triple.lock")
        // Simulates another holder the in-JVM mutex does not know about
        // (e.g. this class loaded by a second plugin classloader).
        val raf = RandomAccessFile(lockFile, "rw")
        val foreign = raf.channel.lock()
        val bodyRan = AtomicInteger()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val f = pool.submit { CliBinaryResolver.withTripleLock(lockFile) { bodyRan.incrementAndGet() } }
            Thread.sleep(300)
            assertEquals(0, bodyRan.get(), "body must not run while the file lock is held")
            foreign.release()
            raf.close()
            f.get(10, TimeUnit.SECONDS)
            assertEquals(1, bodyRan.get())
        } finally {
            pool.shutdownNow()
            if (raf.channel.isOpen) raf.close()
        }
    }

    @Test(timeout = 10_000) fun `lock is reentrant and returns the body value`() {
        val lockFile = File(tmp.newFolder("cache3"), "triple.lock")
        var lockedDuringNested = false
        val v = CliBinaryResolver.withTripleLock(lockFile) {
            CliBinaryResolver.withTripleLock(lockFile) {
                // The outer file lock is still held: a fresh channel in this JVM cannot take it.
                lockedDuringNested = RandomAccessFile(lockFile, "rw").use { raf ->
                    try {
                        raf.channel.tryLock()?.release()
                        false
                    } catch (e: java.nio.channels.OverlappingFileLockException) {
                        true
                    }
                }
                42
            }
        }
        assertEquals(42, v)
        assertTrue(lockedDuringNested)
    }

    @Test(timeout = 10_000) fun `interrupt while waiting for the file lock never runs the body`() {
        val lockFile = File(tmp.newFolder("cache4"), "triple.lock")
        val raf = RandomAccessFile(lockFile, "rw")
        val foreign = raf.channel.lock()
        val bodyRan = AtomicInteger()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val t = Thread {
            try {
                CliBinaryResolver.withTripleLock(lockFile) { bodyRan.incrementAndGet() }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        try {
            t.start()
            Thread.sleep(200)
            t.interrupt()
            t.join(5_000)
            assertFalse(t.isAlive)
            assertEquals(0, bodyRan.get())
            assertTrue(failure.get() is InterruptedException, "got ${failure.get()}")
        } finally {
            foreign.release()
            raf.close()
        }
    }

    @Test(timeout = 20_000) fun `interrupt while another process holds the lock never runs the body`() {
        val lockFile = File(tmp.newFolder("cache5"), "triple.lock")
        lockFile.createNewFile()
        val holder = try {
            ProcessBuilder(
                "python3", "-c",
                "import fcntl,time,sys;f=open(sys.argv[1],'r+');fcntl.lockf(f,fcntl.LOCK_EX);" +
                    "print('locked',flush=True);time.sleep(60)",
                lockFile.absolutePath,
            ).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            assumeTrue("python3 not available", false)
            return
        }
        try {
            assumeTrue("holder did not lock", holder.inputStream.bufferedReader().readLine() == "locked")
            val bodyRan = AtomicInteger()
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val t = Thread {
                try {
                    CliBinaryResolver.withTripleLock(lockFile) { bodyRan.incrementAndGet() }
                } catch (e: Throwable) {
                    failure.set(e)
                }
            }
            t.start()
            Thread.sleep(500)
            assertEquals(0, bodyRan.get(), "body must wait for the other process")
            t.interrupt()
            t.join(5_000)
            assertFalse(t.isAlive)
            assertEquals(0, bodyRan.get(), "interrupt must not downgrade to running unlocked")
            assertTrue(failure.get() is InterruptedException, "got ${failure.get()}")
        } finally {
            holder.destroyForcibly()
        }
    }

    // ── downloadAndExtract: atomic install + concurrency (issue #44) ─────

    private val logger = Logging.getLogger("CliBinaryResolverLockTest")

    /** Fake `tar`: honours `-C <dir>` and drops a `bugsee-cli` file there. */
    private fun fakeTar(exit: Int = 0, throwAfterWrite: Boolean = false): (Action<in ExecSpec>) -> ExecResult = { action ->
        var args: List<String> = emptyList()
        val spec = java.lang.reflect.Proxy.newProxyInstance(
            ExecSpec::class.java.classLoader,
            arrayOf(ExecSpec::class.java),
        ) { proxy, method, a ->
            if (method.name == "setArgs") args = (a!![0] as Iterable<*>).map { it.toString() }
            if (method.returnType == ExecSpec::class.java) proxy else null
        } as ExecSpec
        action.execute(spec)
        val dir = File(args[args.indexOf("-C") + 1])
        File(dir, "bugsee-cli").writeText("#!/bin/sh\n")
        File(dir, "bugsee-cli").setExecutable(true)
        if (throwAfterWrite) throw IllegalStateException("tar killed")
        object : ExecResult {
            override fun getExitValue() = exit
            override fun assertNormalExitValue(): ExecResult = this
            override fun rethrowFailure(): ExecResult = this
        }
    }

    private fun fetchFile(counter: AtomicInteger): (String, File) -> Unit = { _, dest ->
        counter.incrementAndGet()
        dest.writeText("tarball")
    }

    private val sha = java.security.MessageDigest.getInstance("SHA-256")
        .digest("tarball".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun extract(cacheRoot: File, tar: (Action<in ExecSpec>) -> ExecResult, downloads: AtomicInteger): File =
        CliBinaryResolver.downloadAndExtract(
            "9.9.9", "aarch64-apple-darwin", cacheRoot, "bugsee-cli", logger, false, tar,
            fetchFile(downloads), { "$sha  bugsee-cli.tar.xz" },
        )

    @Test(timeout = 10_000) fun `failed tar leaves no usable binary and no temp dir`() {
        val cacheRoot = File(tmp.newFolder("c6"), "t")
        assertFailsWith<java.io.IOException> { extract(cacheRoot, fakeTar(exit = 1), AtomicInteger()) }
        assertFalse(File(cacheRoot, "bugsee-cli").exists())
        assertEquals(emptyList(), cacheRoot.list()!!.toList())
    }

    @Test(timeout = 10_000) fun `killed tar leaves no usable binary and next resolve recovers`() {
        val cacheRoot = File(tmp.newFolder("c7"), "t")
        assertFailsWith<IllegalStateException> { extract(cacheRoot, fakeTar(throwAfterWrite = true), AtomicInteger()) }
        assertFalse(File(cacheRoot, "bugsee-cli").exists())
        // A hard kill would skip the finally: simulate its leftover, then recover.
        File(cacheRoot, ".tmp-dead/extract").mkdirs()
        File(cacheRoot, ".tmp-dead/extract/bugsee-cli").writeText("partial")
        val bin = extract(cacheRoot, fakeTar(), AtomicInteger())
        assertTrue(bin.isFile && bin.canExecute())
        assertEquals(listOf("bugsee-cli"), cacheRoot.list()!!.toList())
    }

    @Test(timeout = 30_000) fun `concurrent resolves download and extract exactly once`() {
        val cacheRoot = File(tmp.newFolder("c8"), "t")
        val lockFile = File(cacheRoot.parentFile, "t.lock")
        val downloads = AtomicInteger()
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val results = (1..threads).map {
                pool.submit<File> {
                    start.await()
                    CliBinaryResolver.withTripleLock(lockFile) {
                        extract(cacheRoot, fakeTar(), downloads)
                    }
                }
            }
            start.countDown()
            results.forEach { assertTrue(it.get(20, TimeUnit.SECONDS).let { f -> f.isFile && f.canExecute() }) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, downloads.get())
    }
}
