package de.minipc.appopsshizuku.shizuku

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Shell-Befehle mit Shizuku-Privilegien (adb, kein Root).
 * API 13.1.5 (live per javap verifiziert): Shizuku.pingBinder() statt ping(),
 * Prozess via IShizukuService.newProcess(cmd, null, null), waitForTimeout(long, String).
 * Argumente werden IMMER als String-Array uebergeben (keine Injection).
 */
class ShizukuBackend : ShellBackend {

    companion object {
        /** Gemeinsamer IO-Pool fuer stdout/stderr (statt einem neuen Pool pro Aufruf). */
        private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    }

    /** Gemeinsamen IO-Pool herunterfahren (z.B. beim Beenden der App). */
    fun close() {
        ioExecutor.shutdown()
    }

    override fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    override fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    override fun exec(vararg args: String): ShellResult = execTimeout(15, *args)

    /**
     * App einfrieren: Prozess wird sofort beendet, Starten ist blockiert,
     * App bleibt aber mit Daten installiert (pm suspend, kein Root noetig).
     */
    override fun suspendPackage(packageName: String): ShellResult =
        exec("pm", "suspend", "--user", "0", packageName)

    /** Eingefrorene App wieder auftauen (pm unsuspend). */
    override fun unsuspendPackage(packageName: String): ShellResult =
        exec("pm", "unsuspend", "--user", "0", packageName)

    /**
     * Binaerdaten ueber den Prozess-Stdin in die Shell schreiben (z.B.
     * `sh -c "cat > /data/local/tmp/x.apk"`). Stdin bleibt dafuer offen,
     * die Bytes werden geschrieben und der Stream geschlossen.
     */
    override fun execWithStdin(stdin: ByteArray, vararg args: String): ShellResult {
        if (!hasPermission()) {
            return ShellResult(1, "", "keine Shizuku-Berechtigung")
        }
        val binder = try {
            Shizuku.getBinder()
        } catch (e: Throwable) {
            return ShellResult(1, "", e.message ?: "Shizuku-Binder nicht verfuegbar")
        } ?: return ShellResult(1, "", "Shizuku-Binder ist null")

        val service = IShizukuService.Stub.asInterface(binder)
        val remote: IRemoteProcess = try {
            service.newProcess(args.copyOf(), null, null)
        } catch (e: Throwable) {
            return ShellResult(1, "", e.message ?: "Prozess konnte nicht gestartet werden")
        }

        // stdin schreiben (Bytes), dann schliessen
        try {
            remote.outputStream?.let {
                ParcelFileDescriptor.AutoCloseOutputStream(it).use { os ->
                    os.write(stdin)
                    os.flush()
                }
            }
        } catch (e: Exception) {
            // schreiben fehlgeschlagen -> weiter unten am exitCode erkennbar
        }

        val outStream = remote.inputStream?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        val errStream = remote.errorStream?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }

        val stdoutFuture = FutureTask<String> {
            if (outStream != null) {
                BufferedReader(InputStreamReader(outStream, Charsets.UTF_8)).use { it.readText() }
            } else ""
        }
        val stderrFuture = FutureTask<String> {
            if (errStream != null) {
                BufferedReader(InputStreamReader(errStream, Charsets.UTF_8)).use { it.readText() }
            } else ""
        }
        ioExecutor.execute(stdoutFuture)
        ioExecutor.execute(stderrFuture)

        val finished = try {
            remote.waitForTimeout(60, TimeUnit.SECONDS.name)
        } catch (e: Throwable) {
            false
        }
        if (!finished) {
            try { remote.destroy() } catch (e: Throwable) { /* egal */ }
            return ShellResult(124, "", "timeout")
        }

        val exitCode = try { remote.exitValue() } catch (e: Throwable) { -1 }
        val stdout = try { stdoutFuture.get() } catch (e: Exception) { "" }
        val stderr = try { stderrFuture.get() } catch (e: Exception) { "" }
        return ShellResult(exitCode, stdout, stderr)
    }

    /** Wie [exec], aber mit konfigurierbarem Timeout (fuer grosse Base64-Outputs). */
    override fun execTimeout(timeoutSeconds: Long, vararg args: String): ShellResult {
        if (!hasPermission()) {
            return ShellResult(1, "", "keine Shizuku-Berechtigung")
        }
        val binder = try {
            Shizuku.getBinder()
        } catch (e: Throwable) {
            return ShellResult(1, "", e.message ?: "Shizuku-Binder nicht verfuegbar")
        } ?: return ShellResult(1, "", "Shizuku-Binder ist null")

        val service = IShizukuService.Stub.asInterface(binder)
        val remote: IRemoteProcess = try {
            service.newProcess(args.copyOf(), null, null)
        } catch (e: Throwable) {
            return ShellResult(1, "", e.message ?: "Prozess konnte nicht gestartet werden")
        }

        // stdin schliessen (Prozess haengt sonst bei manchen Befehlen)
        try {
            remote.outputStream?.let {
                ParcelFileDescriptor.AutoCloseOutputStream(it).close()
            }
        } catch (e: Exception) {
            // ignorieren
        }

        val outStream = remote.inputStream?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        val errStream = remote.errorStream?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }

        val stdoutFuture = FutureTask<String> {
            if (outStream != null) {
                BufferedReader(InputStreamReader(outStream, Charsets.UTF_8)).use { it.readText() }
            } else ""
        }
        val stderrFuture = FutureTask<String> {
            if (errStream != null) {
                BufferedReader(InputStreamReader(errStream, Charsets.UTF_8)).use { it.readText() }
            } else ""
        }
        ioExecutor.execute(stdoutFuture)
        ioExecutor.execute(stderrFuture)

        val finished = try {
            remote.waitForTimeout(timeoutSeconds, TimeUnit.SECONDS.name)
        } catch (e: Throwable) {
            false
        }
        if (!finished) {
            try { remote.destroy() } catch (e: Throwable) { /* egal */ }
            return ShellResult(124, "", "timeout")
        }

        val exitCode = try { remote.exitValue() } catch (e: Throwable) { -1 }
        val stdout = try { stdoutFuture.get() } catch (e: Exception) { "" }
        val stderr = try { stderrFuture.get() } catch (e: Exception) { "" }
        return ShellResult(exitCode, stdout, stderr)
    }
}
