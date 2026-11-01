package de.minipc.appopsshizuku

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat
import de.minipc.appopsshizuku.appops.AutoRule
import de.minipc.appopsshizuku.appops.AutoRuleStore
import de.minipc.appopsshizuku.appops.ChangeLog
import de.minipc.appopsshizuku.appops.NetControl
import de.minipc.appopsshizuku.appops.NetStore
import de.minipc.appopsshizuku.appops.RuleAction
import de.minipc.appopsshizuku.appops.RuleEval
import de.minipc.appopsshizuku.appops.RuleTrigger
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import java.util.Calendar

/**
 * Wertet die Automatik-Regeln aus und wendet Uebergaenge an. Zeit-Regeln werden ueber einen
 * ungefaehren Weckruf (AlarmManager) an der naechsten Fenstergrenze ausgeloest; WLAN-Regeln ueber
 * einen ~15-min-Fallback-Weckruf und bei jedem Oeffnen der App (kein Dauerdienst, daher kann eine
 * WLAN-Regel bis zu ~15 min nachlaufen - bewusst, um Akku und einen weiteren Dienst zu sparen).
 */
object AutoRuleManager {
    private const val TAG = "AppOps.AutoRule"
    const val ACTION_EVAL = "de.minipc.appopsshizuku.AUTO_RULE_EVAL"
    private const val REQ_EDGE = 7001
    private const val REQ_PERIODIC = 7002
    private const val PERIODIC_MS = 15 * 60 * 1000L

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Verbundene SSID oder null (nicht verbunden / keine Standortberechtigung / nicht lesbar). */
    fun currentSsid(context: Context): String? {
        if (!hasLocationPermission(context)) return null
        return runCatching {
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            val raw = wm.connectionInfo?.ssid ?: return null
            val s = raw.trim('"')
            if (s.isEmpty() || s == "<unknown ssid>" || s == "0x") null else s
        }.getOrNull()
    }

    private fun shizukuUsable(): Boolean =
        runCatching { ShizukuBackend().let { it.isAvailable() && it.hasPermission() } }.getOrDefault(false)

    /** Alle Regeln auswerten und Uebergaenge anwenden, danach den naechsten Weckruf planen. */
    @Synchronized
    fun evaluate(context: Context) {
        val app = context.applicationContext
        val store = AutoRuleStore(app)
        val rules = store.all()
        val cal = Calendar.getInstance()
        val dayMon0 = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // So=1 -> 6, Mo=2 -> 0 ...
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val ssid = currentSsid(app)
        for (rule in rules) {
            if (!rule.enabled) continue
            val active = RuleEval.active(rule, dayMon0, minute, ssid)
            val prev = store.lastActive(rule.id)
            if (prev == active) continue
            // WLAN-Regel ohne lesbaren Netznamen (Standort fehlt): nicht raten, Zustand offen lassen
            if (rule.trigger == RuleTrigger.WIFI && ssid == null && !rule.whenConnected) {
                // "aktiv wenn NICHT verbunden" bleibt ohne Lesbarkeit unsicher -> ueberspringen
                continue
            }
            if (!shizukuUsable()) continue // ohne Shizuku nichts anwenden; beim naechsten Mal erneut
            val ok = runCatching { apply(app, rule, active) }.getOrDefault(false)
            if (ok) {
                store.setLastActive(rule.id, active)
                runCatching {
                    ChangeLog.recordInfo(
                        app, app.getString(
                            if (active) R.string.log_info_rule_on else R.string.log_info_rule_off, rule.name
                        )
                    )
                }
            }
            Log.i(TAG, "Regel '${rule.name}' -> aktiv=$active ok=$ok")
        }
        scheduleNext(app, rules)
    }

    private fun apply(context: Context, rule: AutoRule, active: Boolean): Boolean = when (rule.action) {
        RuleAction.LOCKDOWN ->
            if (active) !LockdownManager.activate(context).noShizuku
            else !LockdownManager.deactivate(context).noShizuku
        RuleAction.APPS_OFFLINE -> {
            val backend = ShizukuBackend()
            val net = NetControl(backend)
            val store = NetStore(context)
            var allOk = true
            for (pkg in rule.packages) {
                val uid = runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
                if (uid == null) continue
                if (net.set(de.minipc.appopsshizuku.appops.NetSwitch.OFFLINE, pkg, uid, active)) {
                    store.mark(pkg, de.minipc.appopsshizuku.appops.NetSwitch.OFFLINE, active)
                } else allOk = false
            }
            allOk
        }
    }

    /** Naechsten Weckruf planen: an der naechsten Zeitgrenze; zusaetzlich periodisch, wenn WLAN-Regeln da sind. */
    fun scheduleNext(context: Context, rules: List<AutoRule> = AutoRuleStore(context).all()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val cal = Calendar.getInstance()
        val dayMon0 = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val edge = RuleEval.nextEdgeMinutes(rules, dayMon0, minute)
        if (edge != null) {
            val at = System.currentTimeMillis() + edge * 60_000L
            runCatching {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, REQ_EDGE))
            }
        } else {
            runCatching { am.cancel(pending(context, REQ_EDGE)) }
        }
        val hasWifi = rules.any { it.enabled && it.trigger == RuleTrigger.WIFI }
        val periodic = pending(context, REQ_PERIODIC)
        if (hasWifi) {
            runCatching {
                am.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + PERIODIC_MS, PERIODIC_MS, periodic
                )
            }
        } else {
            runCatching { am.cancel(periodic) }
        }
    }

    private fun pending(context: Context, req: Int): PendingIntent = PendingIntent.getBroadcast(
        context, req,
        Intent(context, AutoRuleReceiver::class.java).setAction(ACTION_EVAL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
