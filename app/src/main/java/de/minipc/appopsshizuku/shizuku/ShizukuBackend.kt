package de.minipc.appopsshizuku.shizuku

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Shell-Befehle mit Shizuku-Privilegien (adb, kein Root).
 * API 13.1.5 (live per javap verifiziert): Shizuku.pingBinder() statt ping(),
 * Prozess via IShizukuService.newProcess(cmd, null, null), waitForTimeout(long, String).
 * Argumente werden IMMER als String-Array uebergeben (keine Injection).
 */
class ShizukuBackend : ShellBackend {

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
    override fun execWithStdin(stdin: ByteArray, vararg args: String): ShellResult =
        run(60, stdin, args)

    /** Wie [exec], aber mit konfigurierbarem Timeout (fuer grosse Base64-Outputs). */
    override fun execTimeout(timeoutSeconds: Long, vararg args: String): ShellResult =
        run(timeoutSeconds, null, args)

    /** Gemeinsamer Weg fuer alle Aufrufe: Prozess starten, [ShellRunner] fuehrt aus, Fehler landen im Protokoll. */
    private fun run(timeoutSeconds: Long, stdin: ByteArray?, args: Array<out String>): ShellResult =
        start(args).let { started ->
            val result = started.process
                ?.let { ShellRunner.run(it, timeoutSeconds, stdin) }
                ?: started.failure!!
            de.minipc.appopsshizuku.ErrorLog.shell(args, result)
            result
        }

    private class Started(val process: ShellProcess?, val failure: ShellResult?)

    private fun start(args: Array<out String>): Started {
        if (!hasPermission()) return Started(null, ShellResult(1, "", "keine Shizuku-Berechtigung"))
        val binder = try {
            Shizuku.getBinder()
        } catch (e: Throwable) {
            return Started(null, ShellResult(1, "", e.message ?: "Shizuku-Binder nicht verfuegbar"))
        } ?: return Started(null, ShellResult(1, "", "Shizuku-Binder ist null"))

        val service = IShizukuService.Stub.asInterface(binder)
        val remote: IRemoteProcess = try {
            service.newProcess(args.copyOf(), null, null)
        } catch (e: Throwable) {
            return Started(null, ShellResult(1, "", e.message ?: "Prozess konnte nicht gestartet werden"))
        }
        return Started(RemoteShellProcess(remote), null)
    }
}

/** [ShellProcess] ueber einen Shizuku-[IRemoteProcess] (Streams kommen als ParcelFileDescriptor). */
private class RemoteShellProcess(private val remote: IRemoteProcess) : ShellProcess {
    override val stdin: OutputStream? get() = remote.outputStream?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }
    override val stdout: InputStream? get() = remote.inputStream?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
    override val stderr: InputStream? get() = remote.errorStream?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
    override fun waitFor(timeoutSeconds: Long): Boolean = remote.waitForTimeout(timeoutSeconds, TimeUnit.SECONDS.name)
    override fun exitValue(): Int = remote.exitValue()
    override fun destroy() = remote.destroy()
}
