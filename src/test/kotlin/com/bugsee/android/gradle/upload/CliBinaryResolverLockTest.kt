package com.bugsee.android.gradle.upload

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

/**
 * Issue #44: `FileChannel.lock` throws OverlappingFileLockException for a
 * second lock in the same JVM, and the resolver used to treat that as "run
 * the body unlocked" — so mapping + native upload tasks in one daemon could
 * download/extract the CLI concurrently.
 */
class CliBinaryResolverLockTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `concurrent callers in one JVM never overlap`() {
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

    @Test fun `waits for a file lock held elsewhere in the JVM instead of running unlocked`() {
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

    @Test fun `lock is reentrant and returns the body value`() {
        val lockFile = File(tmp.newFolder("cache3"), "triple.lock")
        val v = CliBinaryResolver.withTripleLock(lockFile) {
            CliBinaryResolver.withTripleLock(lockFile) { 42 }
        }
        assertEquals(42, v)
    }
}
