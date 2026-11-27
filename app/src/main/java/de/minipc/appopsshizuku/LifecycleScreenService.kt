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
import de.minipc.appopsshizuku.appops.OnDemandLock
import de.minipc.appopsshizuku.appops.OnDemandExecutor
import de.minipc.appopsshizuku.appops.ScreenOffExecutor
import de.minipc.appopsshizuku.appops.UserRemoval
import de.minipc.appopsshizuku.appops.installedOnly
import de.minipc.appopsshizuku.appops.screenActions
import de.minipc.appopsshizuku.appops.screenOffServiceNeeded
import de.minipc.appopsshizuku.appops.updateSuspendedByUs
import de.minipc.appopsshizuku.appops.AppOpsController
import de.minipc.appopsshizuku.appops.PrivacyOps
import de.minipc.appopsshizuku.appops.WhileInUse
import de.minipc.appopsshizuku.appops.WhileInUseStore
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
        /** Abstand des Weck-Waechters (zaehlt nur, solange das Geraet wach ist - genau dann laufen auch Jobs). */
        private const val WAKE_GUARD_SECONDS = 300L
        private const val ACTION_NOTIF_DISMISSED = "de.minipc.appopsshizuku.lifecycle.NOTIF_DISMISSED"

        /**
         * Dienst starten oder stoppen - je nachdem, ob mindestens eine App den Haken hat.
         * Gibt eine Fehlermeldung zurueck, wenn der Start nicht moeglich war (sonst null).
         */
        fun sync(context: Context): String? {
            val app = context.applicationContext
            val needed = serviceNeeded(app)
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
                    ErrorLog.w(TAG, "Dienst-Start nicht moeglich: $it")
                    it.message ?: it.javaClass.simpleName
                }
        }

        /**
         * Laeuft der Dienst? Bildschirm-Aus / Nur bei Bedarf ODER "Nur waehrend Nutzung" (auch solange
         * noch eine von uns erteilte Berechtigung zurueckzunehmen ist).
         */
        fun serviceNeeded(context: Context): Boolean {
            val app = context.applicationContext
            val entries = installedEntries(app, LifecycleStore(app))
            if (screenOffServiceNeeded(entries, app.packageName, protectedPackagesOf(app, entries.keys))) return true
            val wiu = WhileInUseStore(app)
            return whileInUseApps(app, wiu).isNotEmpty() || wiu.grantedByUs.isNotEmpty()
        }

        /** Installierte Apps im Modus "Nur waehrend Nutzung" (eigene App nie). */
        fun whileInUseApps(context: Context, store: WhileInUseStore): Map<String, Set<String>> =
            store.all().filterKeys { it != context.packageName && isInstalled(context, it) }

        /** Eintraege nur fuer installierte Apps (deinstallierte zaehlen nicht). */
        fun installedEntries(context: Context, store: LifecycleStore) =
            installedOnly(store.all()) { isInstalled(context, it) }

        private fun isInstalled(context: Context, pkg: String): Boolean =
            runCatching { context.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

        /**
         * Welche der Pakete sind geschuetzt? (auf die wird nie etwas angewendet: System-UI,
         * Einstellungen, Telefon, Provider, aktueller Launcher und Tastatur, Shizuku, diese App)
         */
        fun protectedPackagesOf(context: Context, packages: Collection<String>): Set<String> {
            val home = UserRemovalManager.homePackage(context)
            val keyboard = UserRemovalManager.keyboardPackage(context, null)
            return packages.filter { pkg ->
                UserRemoval.blockReason(pkg, context.packageName, home, keyboard) != null
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

    /** Prueft regelmaessig, ob eine eingefrorene Bedarfs-App im Hintergrund geweckt wurde. */
    private var wakeGuard: ScheduledFuture<*>? = null
    private lateinit var store: LifecycleStore
    private lateinit var wiuStore: WhileInUseStore
    /** "Nur waehrend Nutzung": eigene Abfrage, Vordergrund-Zustand, Verlassen-Zeitpunkte. */
    private var wiuPoll: ScheduledFuture<*>? = null
    private val wiuForeground = HashMap<String, Boolean>()
    private val wiuLeftAt = HashMap<String, Long>()
    private var wiuLastPoll = 0L
    @Volatile private var screenOn = true
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
                Intent.ACTION_PACKAGE_ADDED -> {
                    // Neu installiert (kein Update): sofort Quarantaene pruefen
                    if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                    val pending = goAsync()
                    executor.execute {
                        try {
                            QuarantineManager.check(context)?.let { r ->
                                if (r.applied.isNotEmpty() || r.failed.isNotEmpty()) {
                                    report(getString(R.string.quarantine_report, r.applied.size + r.failed.size, r.failed.size))
                                }
                            }
                        } finally {
                            pending.finish()
                        }
                    }
                }
                Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    if (pkg !in store.all() && pkg !in store.suspendedByUs && wiuStore.ops(pkg).isEmpty()) return
                    store.remove(pkg)
                    wiuStore.remove(pkg)
                    wiuStore.grantedByUs = wiuStore.grantedByUs - pkg
                    Log.i(TAG, "$pkg deinstalliert - Eintrag entfernt")
                    // Letzte App weg -> Dienst und Benachrichtigung enden; sonst Zaehler aktualisieren.
                    // NICHT erst stoppen und danach notify(): das hinterliesse eine verwaiste Meldung.
                    if (!serviceNeeded(context)) {
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
        wiuStore = WhileInUseStore(this)
        screenOn = runCatching { getSystemService(android.os.PowerManager::class.java).isInteractive }.getOrDefault(true)
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
            ErrorLog.w(TAG, "startForeground fehlgeschlagen: ${started.exceptionOrNull()}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!serviceNeeded(this)) {
            Log.i(TAG, "Keine App mit Screen-Off-Exit / Nur waehrend Nutzung - Dienst endet")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            // Paket-Broadcasts kommen vom System (das erreicht auch nicht exportierte Empfaenger);
            // als Laufzeit-Empfaenger ist PACKAGE_ADDED trotz Manifest-Verbot fuer implizite Broadcasts erlaubt.
            ContextCompat.registerReceiver(
                this, otherReceiver,
                IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED).apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addDataScheme("package")
                },
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
        if (wakeGuard == null) {
            wakeGuard = executor.scheduleWithFixedDelay({ runCatching { wakeGuardTick() }.onFailure {
                ErrorLog.w(TAG, "Weck-Waechter fehlgeschlagen: $it")
            } }, WAKE_GUARD_SECONDS, WAKE_GUARD_SECONDS, TimeUnit.SECONDS)
        }
        executor.execute { whileInUseSweep() }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        poll?.cancel(false)
        wiuPoll?.cancel(false)
        wakeGuard?.cancel(false)
        if (registered) {
            runCatching { unregisterReceiver(screenReceiver) }
            runCatching { unregisterReceiver(otherReceiver) }
        }
        registered = false
        executor.shutdown()
        super.onDestroy()
    }

    private fun handleScreen(screenOn: Boolean) {
        this.screenOn = screenOn
        // Nach dem Bildschirm-An gilt keine App als vorne, bis ein neues Ereignis das sagt
        // (sonst wuerde schon auf dem Sperrbildschirm wieder erteilt)
        if (!screenOn) wiuForeground.clear()
        // "Nur waehrend Nutzung": beim Bildschirm-Aus sofort zuruecknehmen, beim An wieder beobachten
        runCatching { whileInUseTick() }.onFailure { ErrorLog.w(TAG, "Nur waehrend Nutzung: $it") }
        updateWiuPoll()
        if (!screenOn) stopLastPoll = 0L
        updatePoll() // "Nur beenden"-Apps nur bei eingeschaltetem Bildschirm beobachten
        val label = getString(if (screenOn) R.string.screen_event_on else R.string.screen_event_off)
        val entries = installedEntries(this, store)
        // Von uns eingefrorene Apps, die inzwischen deinstalliert sind: vergessen statt freigeben
        store.suspendedByUs.filterNot { isInstalled(this, it) }.forEach { store.remove(it) }
        val backend = ShizukuBackend()
        val usable = runCatching { backend.isAvailable() && backend.hasPermission() }.getOrDefault(false)
        if (!usable) {
            // Ehrlich: ohne Shizuku passiert nichts - das auch so melden.
            report(getString(R.string.screen_report_no_shizuku, label))
            return
        }
        val exec = ScreenOffExecutor(backend)
        val protectedPkgs = protectedPackagesOf(this, entries.keys + store.suspendedByUs)
        val suspendedByUs = store.suspendedByUs
        val alreadySuspended = if (screenOn) emptySet() else entries
            .filter { it.value.screenOffExit == true && it.key !in suspendedByUs }
            .keys.filter { exec.isSuspended(it) }.toSet()
        if (!screenOn) lockOnDemandApps(entries, exec = onDemandExecutor(backend), all = true, reason = label)
        val actions = screenActions(entries, screenOn, packageName, protectedPkgs, suspendedByUs, alreadySuspended)
        if (actions.isEmpty()) {
            Log.i(TAG, "$label: nichts zu tun")
            return
        }
        val results = exec.run(actions)
        store.suspendedByUs = updateSuspendedByUs(suspendedByUs, results)
        results.forEach { Log.i(TAG, "$label: ${it.describe()}") }
        val failed = results.count { !it.ok }
        val summary = if (failed == 0) {
            getString(R.string.screen_report_all_ok, label, results.size)
        } else {
            getString(
                R.string.screen_report_some_failed, label, failed, results.size,
                results.filter { !it.ok }.joinToString("; ") { it.describe() }
            )
        }
        report(summary)
    }

    private fun report(text: String) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
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
        val apps = OnDemand.apps(entries, packageName, protectedPackagesOf(this, entries.keys))
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
            lockOnDemandApps(entries, onDemandExecutor(backend), all = false, reason = getString(R.string.ondemand_report_sweep))
        }
        updatePoll()
    }

    /**
     * `pm suspend` sperrt nur das Oeffnen. Startet das System eine eingefrorene Bedarfs-App im
     * Hintergrund (Job, Bluetooth, andere App), laeuft sie trotz Sperre - bis hier wieder beendet wird.
     * Zwischen zwei Bildschirm-Aus-Ereignissen ist das die einzige Stelle, die das bemerkt.
     */
    private fun wakeGuardTick() {
        // Nur eingefrorene Apps: deaktivierte kann niemand wecken, "Nur beenden" darf laufen, bis sie
        // verlassen wird. Ohne eingefrorene Bedarfs-App kostet der Durchlauf keinen Shell-Aufruf.
        val all = onDemandEntries()
        val frozen = OnDemand.suspendApps(all, all.keys)
        val entries = all.filterKeys { it in frozen }
        if (entries.isEmpty()) return
        val backend = ShizukuBackend()
        if (!runCatching { backend.isAvailable() && backend.hasPermission() }.getOrDefault(false)) return
        lockOnDemandApps(entries, onDemandExecutor(backend), all = false, reason = getString(R.string.ondemand_report_woken))
    }

    private fun onDemandExecutor(backend: de.minipc.appopsshizuku.shizuku.ShellBackend) =
        OnDemandExecutor(backend, getString(R.string.ondemand_suspend_dialog))

    /** Bedarfs-Apps mit Sperrart "Nur beenden" (aus jedem Launcher startbar). */
    private fun stopModeApps(): Set<String> {
        val entries = onDemandEntries()
        return OnDemand.stopApps(entries, entries.keys)
    }

    /** Seit wann nach Starts von "Nur beenden"-Apps gesucht wurde. */
    private var stopLastPoll = 0L

    /**
     * Start einer "Nur beenden"-App aus einem fremden Launcher erkennen: dann gilt sie als geoeffnet
     * (wie nach unserer Verknuepfung) und wird nach dem Verlassen wieder beendet.
     */
    private fun detectStopModeLaunches(backend: de.minipc.appopsshizuku.shizuku.ShellBackend?) {
        val candidates = stopModeApps() - store.onDemandOpen
        if (candidates.isEmpty()) return
        // Ohne Nutzungszugriff bliebe nur `dumpsys activity` - das alle paar Sekunden ueber die Shell
        // zu rufen kostet spuerbar Akku. Dann lieber seltener pruefen.
        if (!OnDemandSupport.hasUsageAccess(this)) {
            if (System.currentTimeMillis() - stopLastPoll < TimeUnit.SECONDS.toMillis(15)) return
        }
        val now = System.currentTimeMillis()
        val since = if (stopLastPoll == 0L) now - TimeUnit.SECONDS.toMillis(30) else stopLastPoll - 1_000
        stopLastPoll = now
        val fg = OnDemandSupport.foregroundMap(this, backend, candidates, since)
        for ((pkg, isFg) in fg) {
            if (isFg == true) {
                store.markOpened(pkg, now)
                openedAt[pkg] = now
                leftAt.remove(pkg)
                Log.i(TAG, "Nur bei Bedarf: $pkg ueber anderen Launcher geoeffnet")
            }
        }
    }

    /** Abfrage laufen lassen, solange eine Bedarfs-App offen ist ODER "Nur beenden"-Apps beobachtet werden. */
    private fun updatePoll() {
        val needed = store.onDemandOpen.isNotEmpty() || (screenOn && stopModeApps().isNotEmpty())
        if (needed && poll == null) {
            pollShizukuReported = false
            poll = executor.scheduleWithFixedDelay({ runCatching { pollTick() }.onFailure {
                ErrorLog.w(TAG, "Abfrage fehlgeschlagen: $it")
            } }, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS)
        } else if (!needed) {
            poll?.cancel(false)
            poll = null
        }
    }

    private fun pollTick() {
        val entries = onDemandEntries()
        if (screenOn) {
            val b = ShizukuBackend()
            val usable = runCatching { b.isAvailable() && b.hasPermission() }.getOrDefault(false)
            detectStopModeLaunches(if (usable) b else null)
        }
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
            lockOne(pkg, e, onDemandExecutor(backend), getString(R.string.ondemand_report_left))
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
        val apps = OnDemand.apps(entries, packageName, protectedPackagesOf(this, entries.keys))
        val open = store.onDemandOpen
        for (pkg in apps.sorted()) {
            if (!all && pkg in open) continue
            val e = entries.getValue(pkg)
            val st = exec.state(pkg)
            val woke = st.wokeUp(OnDemand.lockOf(e))
            if (st.isLocked(OnDemand.lockOf(e)) && pkg !in open && !woke) continue
            if (woke && pkg !in open) {
                // Eingefroren, aber vom System/anderen Apps im Hintergrund gestartet: erneut beenden
                lockOne(pkg, e, exec, getString(R.string.ondemand_report_woken))
                continue
            }
            // "Nur beenden": laeuft die App, kann sie gerade aus einem fremden Launcher geoeffnet
            // worden sein. Nur beenden, wenn sicher NICHT vorne - eine offene App nie abschiessen.
            if (!all && OnDemand.lockOf(e) == OnDemandLock.STOP) {
                val b = ShizukuBackend()
                val usable = runCatching { b.isAvailable() && b.hasPermission() }.getOrDefault(false)
                val fg = OnDemandSupport.isForeground(
                    this, if (usable) b else null, pkg, System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10)
                )
                if (fg != false) {
                    if (fg == true) store.markOpened(pkg, System.currentTimeMillis())
                    continue
                }
            }
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

    // ------------------------------------------------------------------ Nur waehrend Nutzung

    private fun whileInUseSweep() {
        runCatching { whileInUseTick() }.onFailure { ErrorLog.w(TAG, "Nur waehrend Nutzung: $it") }
        updateWiuPoll()
    }

    private fun updateWiuPoll() {
        val needed = screenOn && (whileInUseApps(this, wiuStore).isNotEmpty() || wiuStore.grantedByUs.isNotEmpty())
        if (needed && wiuPoll == null) {
            wiuPoll = executor.scheduleWithFixedDelay({
                runCatching { whileInUseTick() }.onFailure { ErrorLog.w(TAG, "Nur waehrend Nutzung: $it") }
            }, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS)
        } else if (!needed) {
            wiuPoll?.cancel(false)
            wiuPoll = null
        }
    }

    /** Ein Durchlauf: Vordergrund lesen, erteilen/entziehen, Ergebnis merken. */
    private fun whileInUseTick() {
        val apps = whileInUseApps(this, wiuStore)
        val granted = wiuStore.grantedByUs
        if (apps.isEmpty() && granted.isEmpty()) {
            updateWiuPoll()
            return
        }
        val backend = ShizukuBackend()
        if (!runCatching { backend.isAvailable() && backend.hasPermission() }.getOrDefault(false)) return
        val now = System.currentTimeMillis()
        // Erstes Fenster: letzte halbe Stunde, danach nur seit der letzten Abfrage (mit Ueberlappung)
        val since = if (wiuLastPoll == 0L) now - TimeUnit.MINUTES.toMillis(30) else wiuLastPoll - 1_000
        val fresh = OnDemandSupport.foregroundMap(this, backend, apps.keys, since)
        // Ohne neue Ereignisse bleibt der letzte bekannte Zustand gueltig
        for ((pkg, fg) in fresh) if (fg != null) wiuForeground[pkg] = fg
        wiuForeground.keys.retainAll(apps.keys)
        wiuLastPoll = now
        val actions = WhileInUse.decide(now, apps.keys, wiuForeground.toMap(), granted, wiuLeftAt, screenOn)
        if (actions.isEmpty()) return
        val controller = AppOpsController(backend)
        var next = granted
        for ((pkg, action) in actions) {
            val names = apps[pkg]
            if (names == null) {
                // Modus fuer die App ausgeschaltet: den Zustand hat die Oberflaeche dabei selbst gesetzt
                next = next - pkg
                continue
            }
            val ops = PrivacyOps.PRIVACY_OPS.filter {
                it.opName in names && it.permissionName != null &&
                    // Lockdown hat Vorrang: waehrenddessen nichts davon erteilen
                    !(action == WhileInUse.Action.GRANT && LockdownManager.blocksGrant(this, it.opName))
            }
            val mode = if (action == WhileInUse.Action.GRANT) "allow" else "deny"
            val failed = ops.count { op -> controller.setMode(pkg, op, mode).exitCode != 0 }
            next = if (action == WhileInUse.Action.GRANT) next + pkg else next - pkg
            Log.i(TAG, "Nur waehrend Nutzung: $action $pkg (${ops.size} Ops, $failed fehlgeschlagen)")
            if (failed > 0) {
                report(getString(R.string.while_in_use_report_failed, appLabel(pkg), failed))
            }
        }
        wiuStore.grantedByUs = next
        if (next.isEmpty() && apps.isEmpty()) updateWiuPoll()
    }

    private fun buildNotification(): Notification {
        val count = runCatching {
            val lifecycle = installedEntries(this, store)
                .filterValues { it.screenOffExit == true || it.onDemand == true }.keys
            (lifecycle + whileInUseApps(this, wiuStore).keys).size
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
