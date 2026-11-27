package de.minipc.appopsshizuku.shizuku

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Ein laufender Prozess mit Shell-Rechten; [RemoteShellProcess] ist die Shizuku-Anbindung, Tests nutzen Fakes. */
interface ShellProcess {
    val stdin: OutputStream?
    val stdout: InputStream?
    val stderr: InputStream?

    /** true = beendet, false = Zeit abgelaufen. */
    fun waitFor(timeoutSeconds: Long): Boolean
    fun exitValue(): Int
    fun destroy()
}

/**
 * Fuehrt einen [ShellProcess] aus: stdin schreiben (oder schliessen), stdout/stderr GLEICHZEITIG lesen, auf das
 * Ende warten. Android-frei und damit unit-testbar.
 *
 * Wichtig: stdout und stderr muessen parallel gelesen werden - fuellt einer der beiden Puffer, blockiert der
 * Prozess, und das Lesen des anderen Stroms endet nie. Frueher teilten sich ALLE Aufrufe einen Pool aus zwei
 * Threads: parallele Aufrufe (z. B. 4 Worker der Berechtigungs-Uebersicht) liefen faktisch nacheinander. Jetzt
 * bekommt jeder Aufruf eigene (billige, wiederverwendete Daemon-)Threads.
 */
object ShellRunner {

    private const val READER_GRACE_SECONDS = 5L

    private val ioPool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "shell-io").apply { isDaemon = true }
    }

    fun run(
        proc: ShellProcess,
        timeoutSeconds: Long,
        stdin: ByteArray? = null,
        io: ExecutorService = ioPool
    ): ShellResult {
        // stdin: Bytes schreiben und schliessen; ohne Daten sofort schliessen (manche Befehle warten sonst auf Eingabe)
        try {
            proc.stdin?.use { os ->
                if (stdin != null) { os.write(stdin); os.flush() }
            }
        } catch (_: Exception) {
            // Schreiben fehlgeschlagen -> am Exit-Code erkennbar
        }

        val out = io.submit<String> { proc.stdout?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty() }
        val err = io.submit<String> { proc.stderr?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty() }

        val finished = try { proc.waitFor(timeoutSeconds) } catch (_: Throwable) { false }
        if (!finished) {
            try { proc.destroy() } catch (_: Throwable) { /* egal */ }
            out.cancel(true); err.cancel(true)
            return ShellResult(124, "", "timeout")
        }
        val exit = try { proc.exitValue() } catch (_: Throwable) { -1 }
        return ShellResult(exit, collect(out), collect(err))
    }

    /** Ergebnis eines Lesers; haengt ein Enkelprozess am Strom, wartet niemand ewig darauf. */
    private fun collect(f: Future<String>): String = try {
        f.get(READER_GRACE_SECONDS, TimeUnit.SECONDS)
    } catch (_: TimeoutException) {
        f.cancel(true); ""
    } catch (_: ExecutionException) {
        ""
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt(); ""
    }
}
