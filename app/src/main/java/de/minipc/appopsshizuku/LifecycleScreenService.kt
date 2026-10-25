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
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.minipc.appopsshizuku.appops.LifecycleEntry
import de.minipc.appopsshizuku.appops.LifecycleStore
import de.minipc.appopsshizuku.appops.OnDemand
import de.minipc.appopsshizuku.appops.OnDemandExecutor
import de.minipc.appopsshizuku.appops.ScreenOffExecutor
import de.minipc.appopsshizuku.appops.installedOnly
import de.minipc.appopsshizuku.appops.screenActions
import de.minipc.appopsshizuku.appops.screenOffServiceNeeded
import de.minipc.appopsshizuku.appops.updateSuspendedByUs
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
 *
 * Benachrichtigung: wegwischbar (Android 14+) und ueber "Ausblenden" in den
 * Kanal-Einstellungen abschaltbar - der Dienst laeuft in beiden Faellen weiter.
 * Deinstallierte Apps zaehlen nicht mehr; ihr Eintrag wird beim Entfernen geloescht.
 *
 * "Nur bei Bedarf" ([OnDemand]): Solange eine ueber die Verknuepfung geoeffnete App frei ist,
 * prueft der Dienst alle [POLL_SECONDS] s, ob sie noch vorne ist. Nach der Frist (oder beim
 * Bildschirm-Aus sofort) wird sie beendet und gesperrt. Beim Bildschirm-An wird sie nie freigegeben.
 */
class LifecycleScreenService : Service() {

    companion object {
        const val TAG = "AppOps.Lifecycle"
        private const val CHANNEL_ID = "lifecycle_screen"
        private const val NOTIF_ID = 43
        private const val POLL_SECONDS = 3L
        private const val ACTION_NOTIF_DISMISSED = "de.minipc.appopsshizuku.lifecycle.NOTIF_DISMISSED"

        /**
         * Dienst starten oder stoppen - je nachdem, ob mindestens eine App den Haken hat.
         * Gibt eine Fehlermeldung zurueck, wenn der Start nicht moeglich war (sonst null).
         */
        fun sync(context: Context): String? {
            val app = context.applicationContext
            val entries = installedEntries(app, LifecycleStore(app))
            val needed = screenOffServiceNeeded(entries, app.packageName, systemPackagesOf(app, entries.keys))
            val intent = Intent(app, LifecycleScreenService::class.java)
            if (!needed) {
                app.stopService(intent)
                // Falls doch eine Meldung haengen geblieben ist (z. B. aus einer alten Version): weg damit
                runCatching { app.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
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

        /** Eintraege nur fuer installierte Apps (deinstallierte zaehlen nicht). */
        fun installedEntries(context: Context, store: LifecycleStore) =
            installedOnly(store.all()) { isInstalled(context, it) }

        private fun isInstalled(context: Context, pkg: String): Boolean =
            runCatching { context.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

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

    /** Ein Thread fuer alles (Bildschirm, Abfrage, Sperren) - keine Wettlaeufe zwischen ihnen. */
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var poll: ScheduledFuture<*>? = null
    /** Seit wann die App als geoeffnet gilt (Fenster fuer Nutzungsereignisse) / seit wann sie verlassen ist. */
    private val openedAt = HashMap<String, Long>()
    private val leftAt = HashMap<String, Long>()
    /** "Shizuku fehlt" pro Abfrage-Serie nur einmal melden. */
    private var pollShizukuReported = false
    private lateinit var store: LifecycleStore
    private var registered = false
    /** Nutzer hat die Benachrichtigung weggewischt: nicht bei jedem Bildschirm-Wechsel neu zeigen. */
    @Volatile private var notificationDismissed = false
    /** Nach onDestroy keine Meldung mehr posten (sonst bliebe eine verwaiste Benachrichtigung). */
    @Volatile private var destroyed = false

    /** Deinstallation einer App + Wegwischen der Benachrichtigung. */
    private val otherReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_NOTIF_DISMISSED -> notificationDismissed = true
                Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    if (pkg !in store.all() && pkg !in store.suspendedByUs) return
                    store.remove(pkg)
                    Log.i(TAG, "$pkg deinstalliert - Eintrag entfernt")
                    // Letzte App weg -> Dienst und Benachrichtigung enden; sonst Zaehler aktualisieren.
                    // NICHT erst stoppen und danach notify(): das hinterliesse eine verwaiste Meldung.
                    val entries = installedEntries(context, store)
                    if (!screenOffServiceNeeded(entries, packageName, systemPackagesOf(context, entries.keys))) {
                        stopSelf()
                    } else if (!notificationDismissed) {
                        refreshNotification()
                    }
                }
            }
        }
    }

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
        val entries = installedEntries(this, store)
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
            ContextCompat.registerReceiver(
                this, otherReceiver,
                IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED).apply { addDataScheme("package") },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            ContextCompat.registerReceiver(
                this, otherReceiver, IntentFilter(ACTION_NOTIF_DISMISSED), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registered = true
            Log.i(TAG, "Bildschirm-Empfaenger registriert")
        }
        // Bedarfs-Apps: offene ueberwachen, alle anderen sicher gesperrt halten
        executor.execute { onDemandSweep() }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        poll?.cancel(false)
        if (registered) {
            runCatching { unregisterReceiver(screenReceiver) }
            runCatching { unregisterReceiver(otherReceiver) }
        }
        registered = false
        executor.shutdown()
        super.onDestroy()
    }

    private fun handleScreen(screenOn: Boolean) {
        val label = if (screenOn) "Bildschirm an" else "Bildschirm aus"
        val entries = installedEntries(this, store)
        // Von uns eingefrorene Apps, die inzwischen deinstalliert sind: vergessen statt freigeben
        store.suspendedByUs.filterNot { isInstalled(this, it) }.forEach { store.remove(it) }
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
        if (!screenOn) lockOnDemandApps(entries, exec = OnDemandExecutor(backend), all = true, reason = label)
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
        // Weggewischt: Meldung nur speichern (steht im Reiter Verhalten), nicht neu einblenden
        if (!notificationDismissed) refreshNotification()
    }

    private fun refreshNotification() {
        if (destroyed) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    // ------------------------------------------------------------------ Nur bei Bedarf

    private fun onDemandEntries(): Map<String, LifecycleEntry> {
        val entries = installedEntries(this, store)
        val apps = OnDemand.apps(entries, packageName, systemPackagesOf(this, entries.keys))
        return entries.filterKeys { it in apps }
    }

    /**
     * Beim Dienst-Start / nach jedem Oeffnen: Offene Apps ueberwachen; Bedarfs-Apps, die NICHT
     * ueber die Verknuepfung geoeffnet sind, aber frei laufen, sofort wieder sperren.
     */
    private fun onDemandSweep() {
        val entries = onDemandEntries()
        // Offen-Liste bereinigen: Modus aus oder App weg -> nicht mehr ueberwachen
        val open = store.onDemandOpen.filter { it in entries }.toSet()
        if (open != store.onDemandOpen) store.onDemandOpen = open
        val now = System.currentTimeMillis()
        // Fenster fuer Nutzungsereignisse ab dem gespeicherten Oeffnen (fehlt er: letzte Stunde)
        for (pkg in open) {
            val t = store.openedAt(pkg) ?: (now - TimeUnit.HOURS.toMillis(1))
            // Erneut ueber die Verknuepfung geoeffnet: alte "verlassen um"-Zeit gilt nicht mehr
            if (openedAt[pkg] != t) leftAt.remove(pkg)
            openedAt[pkg] = t
        }
        openedAt.keys.retainAll(open)
        leftAt.keys.retainAll(open)
        val backend = ShizukuBackend()
        if (runCatching { backend.isAvailable() && backend.hasPermission() }.getOrDefault(false)) {
            lockOnDemandApps(entries, OnDemandExecutor(backend), all = false, reason = getString(R.string.ondemand_report_sweep))
        }
        updatePoll()
    }

    /** Abfrage nur laufen lassen, solange eine Bedarfs-App offen ist. */
    private fun updatePoll() {
        val needed = store.onDemandOpen.isNotEmpty()
        if (needed && poll == null) {
            pollShizukuReported = false
            poll = executor.scheduleWithFixedDelay({ runCatching { pollTick() }.onFailure {
                Log.w(TAG, "Abfrage fehlgeschlagen: $it")
            } }, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS)
        } else if (!needed) {
            poll?.cancel(false)
            poll = null
        }
    }

    private fun pollTick() {
        val entries = onDemandEntries()
        val open = store.onDemandOpen.filter { it in entries }
        if (open.isEmpty()) {
            store.onDemandOpen = emptySet()
            updatePoll()
            return
        }
        val backend = ShizukuBackend()
        val usable = runCatching { backend.isAvailable() && backend.hasPermission() }.getOrDefault(false)
        val now = System.currentTimeMillis()
        for (pkg in open) {
            val since = openedAt.getOrPut(pkg) { store.openedAt(pkg) ?: now } - 2000
            val fg = OnDemandSupport.isForeground(this, if (usable) backend else null, pkg, since)
            when (fg) {
                true -> leftAt.remove(pkg)
                false -> leftAt.putIfAbsent(pkg, now)
                null -> Unit // keine Aussage: nichts aendern, lieber nicht faelschlich sperren
            }
            val e = entries.getValue(pkg)
            if (!OnDemand.shouldLock(now, leftAt[pkg], OnDemand.graceOf(e))) continue
            if (!usable) {
                if (!pollShizukuReported) report(getString(R.string.ondemand_report_no_shizuku))
                pollShizukuReported = true
                continue
            }
            lockOne(pkg, e, OnDemandExecutor(backend), getString(R.string.ondemand_report_left))
        }
    }

    /**
     * Sperrt Bedarfs-Apps. [all] = true (Bildschirm aus): jede noch freie Bedarfs-App, auch die
     * offenen. false (Sweep): nur die NICHT geoeffneten, die trotzdem frei sind.
     */
    private fun lockOnDemandApps(
        entries: Map<String, LifecycleEntry>,
        exec: OnDemandExecutor,
        all: Boolean,
        reason: String
    ) {
        val apps = OnDemand.apps(entries, packageName, systemPackagesOf(this, entries.keys))
        val open = store.onDemandOpen
        for (pkg in apps.sorted()) {
            if (!all && pkg in open) continue
            val e = entries.getValue(pkg)
            if (exec.state(pkg).isLocked(OnDemand.lockOf(e)) && pkg !in open) continue
            lockOne(pkg, e, exec, reason)
        }
    }

    private fun lockOne(pkg: String, e: LifecycleEntry, exec: OnDemandExecutor, reason: String) {
        val r = exec.lock(pkg, OnDemand.lockOf(e))
        Log.i(TAG, "$reason: $pkg gesperrt ok=${r.ok} ${r.state} ${r.raw}")
        if (r.ok) {
            store.onDemandOpen = store.onDemandOpen - pkg
            openedAt.remove(pkg)
            leftAt.remove(pkg)
            updatePoll()
        }
        report(
            if (r.ok) getString(R.string.ondemand_report_locked, reason, appLabel(pkg))
            else getString(R.string.ondemand_report_lock_failed, reason, appLabel(pkg), r.state.toString())
        )
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    }.getOrDefault(pkg)

    private fun buildNotification(): Notification {
        val count = runCatching {
            installedEntries(this, store).count { it.value.screenOffExit == true || it.value.onDemand == true }
        }.getOrDefault(0)
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
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    this, 2, Intent(ACTION_NOTIF_DISMISSED).setPackage(packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            // Manche Geraete (und Android < 14) lassen Dienst-Meldungen nicht wegwischen:
            // Kanal ausschalten geht immer, der Dienst laeuft trotzdem weiter.
            .addAction(
                0, getString(R.string.lifecycle_notif_hide),
                PendingIntent.getActivity(
                    this, 3,
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
