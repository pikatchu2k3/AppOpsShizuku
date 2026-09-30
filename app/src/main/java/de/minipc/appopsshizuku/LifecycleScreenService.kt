package de.minipc.appopsshizuku

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.minipc.appopsshizuku.appops.LifecycleStore
import de.minipc.appopsshizuku.appops.ScreenOffExecutor
import de.minipc.appopsshizuku.appops.screenActions
import de.minipc.appopsshizuku.appops.screenOffServiceNeeded
import de.minipc.appopsshizuku.appops.updateSuspendedByUs
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Feature 3 "Screen-Off-Exit": beobachtet Bildschirm an/aus und haelt die
 * gewaehlten Apps an (pm suspend oder am force-stop).
 *
 * Warum ein Vordergrunddienst: ACTION_SCREEN_ON/OFF sind ab Android 8 nicht per
 * Manifest-Empfaenger empfangbar (Implicit-Broadcast-Verbot), nur per
 * registerReceiver in einem LAUFENDEN Prozess. Damit Android den Prozess nicht
 * beendet, braucht es einen Vordergrunddienst mit sichtbarer Benachrichtigung.
 * Der Dienst laeuft nur, solange mindestens eine App den Haken hat ([sync]).
 *
 * Typ "specialUse": dataSync ist ab Android 15 auf 6 h/Tag begrenzt und passt
 * inhaltlich nicht; specialUse hat kein Zeitlimit (braucht
 * FOREGROUND_SERVICE_SPECIAL_USE, siehe Manifest).
 */
class LifecycleScreenService : Service() {

    companion object {
        const val TAG = "AppOps.Lifecycle"
        private const val CHANNEL_ID = "lifecycle_screen"
        private const val NOTIF_ID = 43

        /**
         * Dienst starten oder stoppen - je nachdem, ob mindestens eine App den Haken hat.
         * Gibt eine Fehlermeldung zurueck, wenn der Start nicht moeglich war (sonst null).
         */
        fun sync(context: Context): String? {
            val app = context.applicationContext
            val entries = LifecycleStore(app).all()
            val needed = screenOffServiceNeeded(entries, app.packageName, systemPackagesOf(app, entries.keys))
            val intent = Intent(app, LifecycleScreenService::class.java)
            if (!needed) {
                app.stopService(intent)
                return null
            }
            return runCatching { ContextCompat.startForegroundService(app, intent) }
                .exceptionOrNull()
                ?.let {
                    // z. B. ForegroundServiceStartNotAllowedException, wenn die App im Hintergrund ist
                    Log.w(TAG, "Dienst-Start nicht moeglich: $it")
                    it.message ?: it.javaClass.simpleName
                }
        }

        /** Welche der Pakete sind System-Apps? (auf die wird nie etwas angewendet) */
        fun systemPackagesOf(context: Context, packages: Collection<String>): Set<String> {
            val pm = context.packageManager
            return packages.filter { pkg ->
                runCatching {
                    (pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
                }.getOrDefault(false)
            }.toSet()
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: LifecycleStore
    private var registered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = when (intent.action) {
                Intent.ACTION_SCREEN_ON -> true
                Intent.ACTION_SCREEN_OFF -> false
                else -> return
            }
            val pending = goAsync()
            executor.execute {
                try {
                    handleScreen(on)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = LifecycleStore(this)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.lifecycle_notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.lifecycle_notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground MUSS kurz nach startForegroundService kommen - vor jeder Pruefung.
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        }
        if (started.isFailure) {
            Log.w(TAG, "startForeground fehlgeschlagen: ${started.exceptionOrNull()}")
            stopSelf()
            return START_NOT_STICKY
        }
        val entries = store.all()
        if (!screenOffServiceNeeded(entries, packageName, systemPackagesOf(this, entries.keys))) {
            Log.i(TAG, "Keine App mit Screen-Off-Exit - Dienst endet")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            Log.i(TAG, "Bildschirm-Empfaenger registriert")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (registered) runCatching { unregisterReceiver(screenReceiver) }
        registered = false
        executor.shutdown()
        super.onDestroy()
    }

    private fun handleScreen(screenOn: Boolean) {
        val label = if (screenOn) "Bildschirm an" else "Bildschirm aus"
        val entries = store.all()
        val backend = ShizukuBackend()
        val usable = runCatching { backend.isAvailable() && backend.hasPermission() }.getOrDefault(false)
        if (!usable) {
            // Ehrlich: ohne Shizuku passiert nichts - das auch so melden.
            report("$label: nicht möglich – Shizuku ist nicht verbunden, keine App angehalten")
            return
        }
        val exec = ScreenOffExecutor(backend)
        val system = systemPackagesOf(this, entries.keys + store.suspendedByUs)
        val suspendedByUs = store.suspendedByUs
        val alreadySuspended = if (screenOn) emptySet() else entries
            .filter { it.value.screenOffExit == true && it.key !in suspendedByUs }
            .keys.filter { exec.isSuspended(it) }.toSet()
        val actions = screenActions(entries, screenOn, packageName, system, suspendedByUs, alreadySuspended)
        if (actions.isEmpty()) {
            Log.i(TAG, "$label: nichts zu tun")
            return
        }
        val results = exec.run(actions)
        store.suspendedByUs = updateSuspendedByUs(suspendedByUs, results)
        results.forEach { Log.i(TAG, "$label: ${it.describe()}") }
        val failed = results.count { !it.ok }
        val summary = if (failed == 0) {
            "$label: ${results.size} App(s) – alle bestätigt"
        } else {
            "$label: $failed von ${results.size} NICHT übernommen – " +
                results.filter { !it.ok }.joinToString("; ") { it.describe() }
        }
        report(summary)
    }

    private fun report(text: String) {
        val time = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
        store.lastReport = "$time $text"
        Log.i(TAG, text)
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val count = runCatching { store.all().count { it.value.screenOffExit == true } }.getOrDefault(0)
        val last = runCatching { store.lastReport }.getOrDefault("")
        val contentIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.lifecycle_notif_title, count))
            .setContentText(last.ifBlank { getString(R.string.lifecycle_notif_text) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(last.ifBlank { getString(R.string.lifecycle_notif_text) }))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
