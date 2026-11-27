package de.minipc.appopsshizuku.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ShellRunnerTest {

    private class FakeProc(
        out: ByteArray = ByteArray(0),
        err: ByteArray = ByteArray(0),
        private val exit: Int = 0,
        private val finishes: Boolean = true,
        outStream: InputStream? = null
    ) : ShellProcess {
        val stdinBytes = ByteArrayOutputStream()
        @Volatile var stdinClosed = false
        @Volatile var destroyed = false
        override val stdin: OutputStream = object : OutputStream() {
            override fun write(b: Int) = stdinBytes.write(b)
            override fun close() { stdinClosed = true }
        }
        override val stdout: InputStream = outStream ?: ByteArrayInputStream(out)
        override val stderr: InputStream = ByteArrayInputStream(err)
        override fun waitFor(timeoutSeconds: Long) = finishes
        override fun exitValue() = exit
        override fun destroy() { destroyed = true }
    }

    @Test fun sammeltAusgabeUndExitCode() {
        val r = ShellRunner.run(FakeProc("hallo ä".toByteArray(), "warn".toByteArray(), exit = 3), 5)
        assertEquals(ShellResult(3, "hallo ä", "warn"), r)
    }

    @Test fun ohneStdinWirdDerStromGeschlossen() {
        val p = FakeProc()
        ShellRunner.run(p, 5)
        assertTrue(p.stdinClosed)
        assertEquals(0, p.stdinBytes.size())
    }

    @Test fun stdinBytesWerdenGeschriebenUndDerStromGeschlossen() {
        val p = FakeProc()
        val data = ByteArray(1000) { it.toByte() }
        ShellRunner.run(p, 5, stdin = data)
        assertTrue(p.stdinClosed)
        assertTrue(data.contentEquals(p.stdinBytes.toByteArray()))
    }

    @Test fun zeitueberschreitungBeendetDenProzess() {
        val p = FakeProc(finishes = false)
        val r = ShellRunner.run(p, 1)
        assertEquals(124, r.exitCode)
        assertEquals("timeout", r.stderr)
        assertTrue(p.destroyed)
    }

    @Test fun grosseAusgabeAufBeidenStroemenBlockiertNicht() {
        // je 2 MB auf stdout UND stderr: nur mit gleichzeitigem Lesen ohne Haenger moeglich
        val big = ByteArray(2 * 1024 * 1024) { 'x'.code.toByte() }
        val r = ShellRunner.run(FakeProc(big, big), 10)
        assertEquals(big.size, r.stdout.length)
        assertEquals(big.size, r.stderr.length)
    }

    @Test fun parallelAufrufeLaufenWirklichGleichzeitig() {
        // 8 Prozesse, deren stdout erst frei gegeben wird, wenn ALLE gleichzeitig lesen. Mit dem alten
        // 2-Thread-Pool waere das nie erreicht worden (Zeitueberschreitung).
        val n = 8
        val allReading = CountDownLatch(n)
        val reading = AtomicInteger()
        val results = java.util.Collections.synchronizedList(mutableListOf<ShellResult>())
        val workers = (1..n).map {
            Thread {
                val blocking = object : InputStream() {
                    var done = false
                    override fun read(): Int {
                        if (!done) {
                            done = true
                            reading.incrementAndGet()
                            allReading.countDown()
                            allReading.await(10, TimeUnit.SECONDS)
                        }
                        return -1
                    }
                }
                results.add(ShellRunner.run(FakeProc(outStream = blocking), 15))
            }.also { it.start() }
        }
        workers.forEach { it.join(20_000) }
        assertEquals(n, results.size)
        assertEquals(n, reading.get())
        assertEquals(0, allReading.count)
    }
}
