package de.minipc.appopsshizuku

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import de.minipc.appopsshizuku.stealth.DeviceSpoofOptions
import de.minipc.appopsshizuku.stealth.StealthPhase
import de.minipc.appopsshizuku.stealth.StealthStealer
import java.io.File
import java.util.concurrent.Executors

/**
 * Fuehrt den Stealth-Patch (oder Restore) in einem FOREGROUND-Service aus.
 *
 * Warum Service statt Activity-Thread (realer User-Fall 08/2026):
 * - Der Patch einer grossen APK (Google Maps ~150MB) dauert 9+ Minuten.
 * - Laueft er im Activity-Thread, wird der Prozess beim App-Wechsel von
 *   OxygenOS/OnePlus eingefroren (OEM-Freezer, Hintergrund-Cpuset) und haengt.
 * - Der Foreground-Service haelt den Prozess im foreground-Cpuset (volle CPU-
 *   Zuteilung, kein Freeze) und zeigt den Fortschritt in der Notification.
 * - Die Activity verbindet sich ueber [progressListener] (gleicher Prozess)
 *   und zeigt zusaetzlich einen modalen Fortschrittsdialog mit Ring.
 *
 * Kommunikation Activity <-> Service: statische Felder im Companion (beide im
 * selben Prozess) — [currentPhase]/[currentPercent] fuer den Wieder-Einstieg
 * der Activity, [lastResult] fuer das Endergebnis (Erfolgs-/Fehlerdialog).
 */
class StealthPatchService : Service() {

    companion object {
        private const val TAG = "StealthPatchService"

        const val ACTION_PATCH = "de.minipc.appopsshizuku.action.PATCH"
        const val ACTION_RESTORE = "de.minipc.appopsshizuku.action.RESTORE"

        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_CLONE_PKG = "clonePkg"
        const val EXTRA_CLONE_LABEL = "cloneLabel"
        const val EXTRA_EXCLUDE_RECENTS = "excludeRecents"
        const val EXTRA_REMOVE_ICON = "removeIcon"
        const val EXTRA_SECURE_SNAPSHOT = "secureSnapshot"
        const val EXTRA_REQUIRE_VPN = "requireVpn"
        const val EXTRA_VAULT = "vaultCalc"
        const val EXTRA_VAULT_PIN = "vaultPin"
        const val EXTRA_SPOOF_MODEL = "spoofModel"
        const val EXTRA_SPOOF_MANUFACTURER = "spoofManufacturer"
        const val EXTRA_SPOOF_BRAND = "spoofBrand"
        const val EXTRA_SPOOF_DEVICE = "spoofDevice"
        const val EXTRA_SPOOF_FINGERPRINT = "spoofFingerprint"
        const val EXTRA_SPOOF_ANDROID_ID = "spoofAndroidId"
        const val EXTRA_SPOOF_IMEI = "spoofImei"
        const val EXTRA_SPOOF_IMSI = "spoofImsi"
        const val EXTRA_SPOOF_BUILD = "spoofBuild"
        const val EXTRA_SPOOF_ANDROID_ID_ON = "spoofAndroidIdOn"
        const val EXTRA_SPOOF_TELEPHONY = "spoofTelephony"

        /** Begleit-App (CalcYou-Vault-Fork), die bei Vault-Patches auto-installiert wird. */
        const val VAULT_CALC_PACKAGE = "de.minipc.calcyou"

        const val CHANNEL_ID = "stealth_patch"
        const val NOTIF_ID = 42

        // --- Kommunikation Activity <-> Service (gleicher Prozess) ---
        @Volatile var running = false
        @Volatile var currentPhase: StealthPhase? = null
        @Volatile var currentPercent: Int? = null
        @Volatile var progressListener: ((StealthPhase, Int?) -> Unit)? = null
        @Volatile var lastResult: PatchResult? = null

        /** Kurzer Phasen-Text fuer Dialog + Notification. */
        fun phaseText(ctx: Context, phase: StealthPhase): String = when (phase) {
            StealthPhase.EXTRACT -> ctx.getString(R.string.stealth_phase_extract)
            StealthPhase.DEX_PATCH -> ctx.getString(R.string.stealth_phase_dex)
            StealthPhase.REPACK -> ctx.getString(R.string.stealth_phase_repack)
            StealthPhase.SIGN -> ctx.getString(R.string.stealth_phase_sign)
            StealthPhase.TRANSFER -> ctx.getString(R.string.stealth_phase_transfer)
            StealthPhase.INSTALL -> ctx.getString(R.string.stealth_phase_install)
            StealthPhase.RESTORE -> ctx.getString(R.string.stealth_phase_restore)
        }

        /**
         * GESAMT-Fortschritt 0-100 ueber alle Phasen (gewichtet nach Arbeitsanteil;
         * bei grossen APKs dominiert der DEX-Patch). null = unbestimmt.
         * Wird im Dialog IN den Ring geschrieben und in der Notification gezeigt.
         */
        fun overallPercent(phase: StealthPhase, pct: Int?): Int? {
            if (pct == null) return null
            return when (phase) {
                StealthPhase.EXTRACT -> pct * 15 / 100
                StealthPhase.DEX_PATCH -> 15 + pct * 55 / 100
                StealthPhase.REPACK -> 70 + pct * 10 / 100
                StealthPhase.SIGN -> 80 + pct * 8 / 100
                StealthPhase.TRANSFER -> 88 + pct * 8 / 100
                StealthPhase.INSTALL -> 96 + pct * 4 / 100
                StealthPhase.RESTORE -> pct
            }.coerceIn(0, 100)
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var currentPkg: String = ""
    private var wakeLock: PowerManager.WakeLock? = null
    private var hintSession: Any? = null // PerformanceHintManager.HintSession (API 31+)
    private var lastHintReportNanos = 0L

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.stealth_notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.stealth_notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        // WakeLock: verhindert, dass die CPU waehrend des langen Patches in
        // Suspend geht (Qwen-Empfehlung 08/2026; 2h-Sicherheits-Timeout)
        wakeLock = (getSystemService(PowerManager::class.java))?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "appopsshizuku:stealth"
        )?.apply {
            setReferenceCounted(false)
            acquire(2 * 60 * 60 * 1000L)
        }
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        closeHintSession()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        currentPkg = intent.getStringExtra(EXTRA_PACKAGE) ?: ""
        startForeground(
            NOTIF_ID,
            buildNotification(getString(R.string.stealth_notif_title), null, null, null)
        )
        executor.execute { runTask(intent) }
        return START_NOT_STICKY
    }

    private fun runTask(intent: Intent) {
        running = true
        lastResult = null
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        if (pkg == null) {
            finish(false, "Kein Paket angegeben")
            return
        }
        // CPU-Scheduling: Patch-Thread im Vordergrund-Rang laufen lassen
        // (gibt dem Prozess bessere Kern-/Taktzuteilung, realer User-Bericht:
        // "CPU taktet beim Patchen kaum hoch").
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
        } catch (e: Exception) {
            Log.w(TAG, "setThreadPriority fehlgeschlagen", e)
        }
        // ADPF (Android 12+): offizieller, root-freier CPU-Boost-Hint an den
        // PowerHAL (Qwen-Empfehlung 08/2026). Kein Garant, aber der einzige
        // app-zugaengliche Weg, dem System "aktive Arbeit" zu melden.
        openHintSession()

        val backend = runCatching {
            ShizukuBackend().takeIf { it.isAvailable() && it.hasPermission() }
        }.getOrNull() ?: NoopBackend()
        if (backend is NoopBackend) {
            finish(false, getString(R.string.status_no_permission))
            return
        }
        val stealer = StealthStealer(backend, applicationContext)
        // Eigene App vor App-Standby/Doze schuetzen (als shell-UID setzbar,
        // app-spezifisch fuer die EIGENE App, reversibel). Ohne das kann
        // OxygenOS den Patch-Prozess im Hintergrund einfrieren (realer
        // User-Fall 08/2026: Patch hing nach App-Wechsel fest).
        runCatching {
            val ownPkg = applicationContext.packageName
            backend.exec("cmd", "appops", "set", ownPkg, "RUN_IN_BACKGROUND", "allow")
            backend.exec("cmd", "appops", "set", ownPkg, "RUN_ANY_IN_BACKGROUND", "allow")
            backend.exec("cmd", "appops", "set", ownPkg, "START_FOREGROUND_SERVICES_FROM_BACKGROUND", "allow")
            backend.exec("cmd", "appops", "set", ownPkg, "WAKE_LOCK", "allow")
            backend.exec("cmd", "deviceidle", "whitelist", "+$ownPkg")
            backend.exec("cmd", "activity", "set-standby-bucket", ownPkg, "active")
            backend.exec("cmd", "activity", "set-inactive", ownPkg, "false")
        }
        stealer.progressListener = { phase, pct ->
            currentPhase = phase
            currentPercent = pct
            updateNotification(phase, pct)
            reportHint()
            Log.d(TAG, "stealer.progress: $phase pct=$pct -> UI-Listener=${progressListener != null}")
            progressListener?.invoke(phase, pct)
        }
        try {
            when (intent.action) {
                ACTION_RESTORE -> {
                    val workDir = StealthStealer.workDir(this, pkg)
                    // Originale auflösen: dauerhafter Ort (filesDir) zuerst, sonst
                    // Legacy-Cache. Nach einem Cache-Leeren (Android/Einstellungen)
                    // sind die Originale NUR noch in filesDir vorhanden.
                    val originals = stealer.resolveOriginals(pkg, workDir)
                    if (originals == null) {
                        finish(false, getString(R.string.stealth_error_restore))
                        return
                    }
                    val res = stealer.restore(originals, pkg)
                    finish(
                        res.success,
                        if (res.success) getString(R.string.stealth_restore_success) else res.message
                    )
                }
                else -> {
                    val workDir = StealthStealer.workDir(this, pkg)
                    // Bereits extrahierte Originale (base + Splits) NUR wiederverwenden,
                    // wenn die Anzahl zur aktuellen pm-path-Anzahl passt — sonst ist die
                    // Extraktion veraltet (z.B. vorher ohne Splits extrahiert, App ist
                    // jetzt ein Bundle; realer Fall 08/2026: Klon kam ohne Splits an).
                    // Auflösung: dauerhafter Ort (filesDir) zuerst, sonst Legacy-Cache.
                    val expectedCount = pmPathCount(pkg, backend)
                    var originals = stealer.resolveOriginals(pkg, workDir)
                    val ok = if (originals != null && originals.size == expectedCount) {
                        stealer.progressListener?.invoke(StealthPhase.EXTRACT, 100)
                        workDir.mkdirs() // fehlt, wenn Originale aus filesDir kommen
                        true
                    } else {
                        stealer.extractApk(pkg, workDir) != null
                    }
                    if (!ok) {
                        finish(false, getString(R.string.stealth_error_extract))
                        return
                    }
                    if (originals == null || originals.size != expectedCount) {
                        originals = originalApks(workDir)
                    }
                    if (originals == null) {
                        finish(false, getString(R.string.stealth_error_extract))
                        return
                    }
                    val base = originals.first()
                    val splits = originals.drop(1)
                    val cloneNewPkg = intent.getStringExtra(EXTRA_CLONE_PKG)
                    val signed = stealer.buildStealthApkWithSplits(
                        base, workDir, splits,
                        intent.getBooleanExtra(EXTRA_EXCLUDE_RECENTS, false),
                        intent.getBooleanExtra(EXTRA_REMOVE_ICON, false),
                        intent.getBooleanExtra(EXTRA_SECURE_SNAPSHOT, false),
                        intent.getBooleanExtra(EXTRA_REQUIRE_VPN, false),
                        intent.getBooleanExtra(EXTRA_VAULT, false),
                        intent.spoofOptions(),
                        cloneNewPkg,
                        intent.getStringExtra(EXTRA_CLONE_LABEL)
                    )
                    if (signed == null) {
                        finish(false, getString(R.string.stealth_error_patch))
                        return
                    }
                    val targetPkg = cloneNewPkg ?: pkg
                    val res = stealer.installApk(
                        signed, targetPkg,
                        allowAutoUninstall = cloneNewPkg == null
                    )
                    // Vault: Begleit-App (VaultCalculator) automatisch mitinstallieren,
                    // wenn sie fehlt. NICHT blockierend: Ein Fehler hier aendert das
                    // Patch-Ergebnis nicht (nur Log; Nutzer kann Rechner manuell holen).
                    if (res.success && intent.getBooleanExtra(EXTRA_VAULT, false)) {
                        ensureVaultAppInstalled(backend, stealer)
                    }
                    if (res.success) {
                        // Originale dauerhaft sichern (filesDir) + Arbeitsordner
                        // aufräumen: repacked/signed-Kopien sind nach dem Install
                        // Einweg-Artefakte — nur die Originale braucht der Restore.
                        // Ergebnis nicht blockierend (Originale bleiben sonst im Cache).
                        if (!stealer.relocateOriginals(workDir, pkg)) {
                            Log.w(TAG, "relocateOriginals fehlgeschlagen für $pkg (Originale bleiben im Cache)")
                        }
                        val msg = if (cloneNewPkg != null) {
                            getString(R.string.stealth_success_clone, cloneNewPkg)
                        } else {
                            getString(R.string.stealth_success)
                        }
                        finish(true, msg)
                    } else {
                        finish(false, res.message)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Patch fehlgeschlagen", e)
            finish(false, e.message ?: "Unbekannter Fehler")
        }
    }

    /**
     * Anzahl der installierten APK-Dateien der Ziel-App (`pm path`-Zeilen).
     * 1 = normale App, >1 = App-Bundle mit Splits. -1 bei Fehler.
     */
    private fun pmPathCount(pkg: String, backend: de.minipc.appopsshizuku.shizuku.ShellBackend): Int {
        val r = runCatching { backend.exec("pm", "path", pkg) }.getOrNull()
            ?: return -1
        if (r.exitCode != 0) return -1
        return r.stdout.lines().count { it.trim().startsWith("package:") }
    }

    /**
     * Sammelt die extrahierten Original-APKs aus dem workDir: base (`original.apk`)
     * + Splits (`original_split_*.apk`, alphabetisch = pm-path-Reihenfolge).
     * null = noch keine gültige Extraktion vorhanden.
     */
    private fun originalApks(workDir: File): List<File>? {
        val base = File(workDir, "original.apk")
        if (!base.exists() || base.length() <= 0) return null
        val splits = workDir.listFiles { f -> f.name.startsWith("original_split_") }
            ?.sortedBy { it.name }
            ?: emptyList()
        return listOf(base) + splits
    }

    /**
     * Installiert die Vault-Begleit-App (CalcYou-Fork, de.minipc.calcyou) automatisch,
     * wenn sie noch nicht installiert ist. Die APK ist als Raw-Ressource gebuendelt
     * (app/src/main/res/raw/calcyou.apk). Fehler sind NICHT blockierend: Der
     * Haupt-Patch bleibt erfolgreich; ein fehlgeschlagener Begleit-Install wird nur
     * geloggt (der Nutzer kann den Rechner dann manuell installieren).
     */
    private fun ensureVaultAppInstalled(
        backend: de.minipc.appopsshizuku.shizuku.ShellBackend,
        stealer: StealthStealer
    ) {
        try {
            val check = backend.exec("pm", "path", VAULT_CALC_PACKAGE)
            if (check.exitCode == 0 && check.stdout.contains("package:")) return // bereits installiert
            val apk = File(cacheDir, "calcyou.apk")
            try {
                resources.openRawResource(R.raw.calcyou).use { input ->
                    apk.outputStream().use { out -> input.copyTo(out) }
                }
                val result = stealer.installApk(apk, VAULT_CALC_PACKAGE, allowAutoUninstall = false)
                if (!result.success) {
                    Log.w(TAG, "CalcYou-Auto-Install fehlgeschlagen: ${result.message}")
                } else {
                    Log.d(TAG, "CalcYou (Vault) automatisch installiert")
                }
            } finally {
                // Temp-Datei entfernen — wird bei Bedarf aus der Raw-Ressource neu geschrieben
                apk.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "CalcYou-Auto-Install Fehler", e)
        }
    }

    private fun finish(success: Boolean, message: String) {
        lastResult = PatchResult(success, message)
        running = false
        // Letztes Signal an die Activity: Ergebnis sofort verarbeiten, auch wenn
        // die Activity die ganze Zeit sichtbar war (kein onResume-Trigger mehr).
        // Die Activity prueft lastResult und zeigt den Erfolgs-/Fehlerdialog.
        try {
            progressListener?.invoke(StealthPhase.INSTALL, if (success) 100 else null)
        } catch (e: Exception) {
            Log.w(TAG, "final progressListener fehlgeschlagen", e)
        }
        val notif = buildNotification(
            if (success) getString(R.string.stealth_notif_done_ok)
            else getString(R.string.stealth_notif_done_error),
            message, null, null
        )
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(phase: StealthPhase, percent: Int?) {
        val overall = overallPercent(phase, percent)
        val text = if (overall != null) {
            "${phaseText(this, phase)} $overall %"
        } else {
            phaseText(this, phase)
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(getString(R.string.stealth_notif_title), text, phase, overall))
    }

    // ------------------------------------------------------------------
    // ADPF PerformanceHint (Android 12+, root-freier CPU-Boost-Hint)
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun openHintSession() {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            val phm = getSystemService("performance") // PerformanceHintManager
                ?: return
            val create = phm.javaClass.getMethod("createHintSession", IntArray::class.java, Long::class.javaPrimitiveType)
            val session = create.invoke(phm, intArrayOf(Process.myTid()), 8_000_000L)
            if (session != null) {
                hintSession = session
                lastHintReportNanos = System.nanoTime()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ADPF-Session nicht verfuegbar: ${e.message}")
            hintSession = null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun reportHint() {
        val session = hintSession ?: return
        val now = System.nanoTime()
        if (now - lastHintReportNanos < 100_000_000L) return // max. alle 100ms
        try {
            val report = session.javaClass.getMethod("reportActualWorkDuration", Long::class.javaPrimitiveType)
            report.invoke(session, now - lastHintReportNanos)
            lastHintReportNanos = now
        } catch (e: Throwable) {
            hintSession = null
        }
    }

    private fun closeHintSession() {
        val session = hintSession ?: return
        try {
            session.javaClass.getMethod("close").invoke(session)
        } catch (e: Throwable) {
            // ignorieren
        }
        hintSession = null
    }

    private fun buildNotification(
        title: String,
        text: String?,
        phase: StealthPhase?,
        percent: Int?
    ): Notification {
        // Tippen auf die Fortschritts-Notification oeffnet die App-Detailseite direkt
        // auf dem Reiter "Tarnung" (dort haengt jetzt die Fortschrittsanzeige).
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, AppDetailActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (currentPkg.isNotEmpty()) putExtra(AppDetailActivity.EXTRA_PACKAGE, currentPkg)
                putExtra(AppDetailActivity.EXTRA_TAB, AppDetailActivity.TAB_STEALTH)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(percent != null || phase != null)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (phase != null && percent != null) {
            builder.setProgress(100, percent, false)
        } else if (phase != null) {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }
}

/** Ergebnis des Stealth-Patchs (Erfolg + Meldung fuer den Dialog). */
data class PatchResult(val success: Boolean, val message: String)

/** DeviceSpoofOptions -> Intent-Extras (fuer den Service-Start). */
internal fun DeviceSpoofOptions.putInto(intent: Intent) {
    intent.putExtra(StealthPatchService.EXTRA_SPOOF_MODEL, fakeModel)
    intent.putExtra(StealthPatchService.EXTRA_SPOOF_MANUFACTURER, fakeManufacturer)
    intent.putExtra(StealthPatchService.EXTRA_SPOOF_BRAND, fakeBrand)
    intent.putExtra(StealthPatchService.EXTRA_SPOOF_DEVICE, fakeDevice)
    intent.putExtra(StealthPatchService.EXTRA_SPOOF_FINGERPRINT, fakeFingerprint)
    intent.putExtra(StealthPatchService.EXTRA_SPOOF_ANDROID_ID, fakeAndroidId)
    intent.putExtra(StealthPatchService.EXTRA_SPOOF_IMEI, fakeImei)
    intent.putExtra(StealthPatchService.EXTRA_SPOOF_IMSI, fakeImsi)
    intent.putExtra(StealthPatchService.EXTRA_SPOOF_BUILD, spoofBuild)
    intent.putExtra(StealthPatchService.EXTRA_SPOOF_ANDROID_ID_ON, spoofAndroidId)
    intent.putExtra(StealthPatchService.EXTRA_SPOOF_TELEPHONY, spoofTelephony)
}

/** Intent-Extras -> DeviceSpoofOptions. */
internal fun Intent.spoofOptions(): DeviceSpoofOptions = DeviceSpoofOptions(
    fakeModel = getStringExtra(StealthPatchService.EXTRA_SPOOF_MODEL).orEmpty(),
    fakeManufacturer = getStringExtra(StealthPatchService.EXTRA_SPOOF_MANUFACTURER).orEmpty(),
    fakeBrand = getStringExtra(StealthPatchService.EXTRA_SPOOF_BRAND).orEmpty(),
    fakeDevice = getStringExtra(StealthPatchService.EXTRA_SPOOF_DEVICE).orEmpty(),
    fakeFingerprint = getStringExtra(StealthPatchService.EXTRA_SPOOF_FINGERPRINT).orEmpty(),
    fakeAndroidId = getStringExtra(StealthPatchService.EXTRA_SPOOF_ANDROID_ID).orEmpty(),
    fakeImei = getStringExtra(StealthPatchService.EXTRA_SPOOF_IMEI).orEmpty(),
    fakeImsi = getStringExtra(StealthPatchService.EXTRA_SPOOF_IMSI),
    spoofBuild = getBooleanExtra(StealthPatchService.EXTRA_SPOOF_BUILD, false),
    spoofAndroidId = getBooleanExtra(StealthPatchService.EXTRA_SPOOF_ANDROID_ID_ON, false),
    spoofTelephony = getBooleanExtra(StealthPatchService.EXTRA_SPOOF_TELEPHONY, false)
)
