package de.minipc.appopsshizuku

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import android.os.Build
import android.util.Log
import de.minipc.appopsshizuku.appops.ErrorLogFormat
import de.minipc.appopsshizuku.shizuku.ShellResult
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lokales Fehlerprotokoll: Abstuerze, fehlgeschlagene Shell-Befehle und interne Fehler.
 *
 * Liegt im privaten App-Speicher (filesDir/error_log.txt), hoechstens ~200 KB, die aeltesten
 * Eintraege fallen heraus. Nichts wird gesendet - die App hat keine Internet-Berechtigung.
 * Geteilt wird nur ueber Diagnose -> Bericht teilen / speichern.
 */
object ErrorLog {

    private const val FILE = "error_log.txt"
    private const val PREFS = "error_log"
    private const val KEY_CRASH = "crash_pending"
    private const val KEY_UNSEEN = "unseen"
    private const val CHANNEL_ID = "error_log"
    private const val NOTIF_ID = 4711

    /** Extra fuer die Diagnose: was die Benachrichtigung ausloesen soll ("share" / "save"). */
    const val EXTRA_ACTION = "errlog_action"
    const val ACTION_SHARE = "share"
    const val ACTION_SAVE = "save"

    @Volatile private var file: File? = null
    @Volatile private var appContext: Context? = null
    private val lock = Any()
    private val lastWritten = HashMap<String, Long>()

    /** Einmal in Application.onCreate: Datei festlegen, Absturz-Handler einhaengen. */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        file = File(app.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                write("ABSTURZ", "Thread: ${thread.name}\n${stack(e)}", dedupe = false, notify = true)
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CRASH, true).commit()
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Wie Log.e, zusaetzlich ins Protokoll. */
    fun e(tag: String, msg: String, tr: Throwable? = null): Int {
        write("FEHLER $tag", if (tr != null) "$msg\n${stack(tr)}" else msg, notify = true)
        return if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
    }

    /** Wie Log.w, zusaetzlich ins Protokoll. */
    fun w(tag: String, msg: String, tr: Throwable? = null): Int {
        write("WARNUNG $tag", if (tr != null) "$msg\n${ErrorLogFormat.clip(stack(tr), 1500)}" else msg)
        return if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
    }

    /** Shell-Fehler in [block] nicht festhalten - fuer Proben, bei denen ein Fehler erwartet ist. */
    private val quiet = ThreadLocal<Boolean>()

    fun <T> quietly(block: () -> T): T {
        val before = quiet.get()
        quiet.set(true)
        try { return block() } finally { quiet.set(before) }
    }

    /** Fehlgeschlagenen Shell-Befehl festhalten (erfolgreiche werden ignoriert). */
    fun shell(args: Array<out String>, r: ShellResult) {
        if (quiet.get() == true) return
        val text = runCatching { ErrorLogFormat.shell(args, r) }.getOrNull() ?: return
        write("SHELL", text)
    }

    fun read(): String = synchronized(lock) {
        runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
    }

    fun clear() = synchronized(lock) {
        lastWritten.clear()
        runCatching { file?.delete() }
        Unit
    }

    /** true genau einmal nach einem Absturz (danach zurueckgesetzt). */
    fun consumeCrashFlag(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getBoolean(KEY_CRASH, false)
        if (pending) prefs.edit().remove(KEY_CRASH).apply()
        return pending
    }

    /** Kopf fuer den Bericht: Version, Geraet, Android - keine App-Liste, keine persoenlichen Daten. */
    fun header(context: Context): String {
        val version = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()})"
        }.getOrDefault("?")
        return "App Ops Shizuku $version · ${Build.MANUFACTURER} ${Build.MODEL} · " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.DISPLAY}"
    }

    private fun write(kind: String, text: String, dedupe: Boolean = true, notify: Boolean = false) {
        val f = file ?: return
        synchronized(lock) {
            runCatching {
                val now = System.currentTimeMillis()
                if (dedupe) {
                    val key = ErrorLogFormat.key(kind, text)
                    if (!ErrorLogFormat.shouldWrite(now, lastWritten[key])) return
                    lastWritten[key] = now
                }
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(now))
                f.appendText(ErrorLogFormat.entry(stamp, kind, text))
                if (notify) notifyError(text.lineSequence().firstOrNull().orEmpty())
                // Byte-Laenge >= Zeichenzahl: erst bei Verdacht wirklich einlesen
                if (f.length() > ErrorLogFormat.MAX_CHARS) {
                    val t = f.readText()
                    if (t.length > ErrorLogFormat.MAX_CHARS) f.writeText(ErrorLogFormat.trim(t))
                }
            }
        }
    }

    /**
     * Bei Absturz oder echtem Fehler (nicht bei Shell-Fehlern und Warnungen): EINE Benachrichtigung,
     * die bei jedem weiteren Fehler aktualisiert wird, mit "Teilen" und "Speichern". Beides passiert
     * nur auf dem Geraet (Teilen-Dialog bzw. Speichern-Dialog) - keine Internet-Berechtigung noetig.
     * Ohne Benachrichtigungs-Erlaubnis passiert einfach nichts; abschaltbar ueber den Kanal "Fehler".
     */
    private fun notifyError(firstLine: String) {
        val ctx = appContext ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val unseen = prefs.getInt(KEY_UNSEEN, 0) + 1
            prefs.edit().putInt(KEY_UNSEEN, unseen).commit()
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, ctx.getString(R.string.errlog_channel), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = ctx.getString(R.string.errlog_channel_desc) }
            )
            fun open(code: Int, action: String?) = PendingIntent.getActivity(
                ctx, code,
                Intent(ctx, DiagnosticsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .apply { if (action != null) putExtra(EXTRA_ACTION, action) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val text = ctx.getString(R.string.errlog_notif_text, unseen, ErrorLogFormat.clip(firstLine, 120))
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(ctx.getString(R.string.errlog_notif_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open(40, null))
                .addAction(0, ctx.getString(R.string.errlog_action_share), open(41, ACTION_SHARE))
                .addAction(0, ctx.getString(R.string.errlog_action_save), open(42, ACTION_SAVE))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }

    /** Diagnose geoeffnet: Benachrichtigung weg, Zaehler zuruecksetzen. */
    fun markSeen(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_UNSEEN).apply()
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        }
    }

    private fun stack(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
}
