package de.minipc.appopsshizuku.appops

/**
 * Automatik-Regeln: eine Bedingung (Zeitfenster an bestimmten Wochentagen ODER WLAN) loest eine
 * Aktion aus. Wird die Bedingung wahr, laeuft die "Ein"-Aktion; wird sie wieder falsch, die
 * Gegenaktion. Reine Entscheidungslogik (JVM-testbar); Anwenden und Planen macht der Manager.
 *
 * WLAN: Um den Netznamen (SSID) zu lesen, verlangt Android ab 10 die Standortberechtigung FUER DIESE
 * App. Die App hat selbst keinen Netzzugriff, der Standort verlaesst das Geraet also nie. Die
 * Berechtigung wird erst abgefragt, wenn der Nutzer eine WLAN-Regel anlegt (nicht beim Start).
 */
enum class RuleTrigger { TIME, WIFI }

/** LOCKDOWN = systemweite Szene (Kamera/Mikro). APPS_OFFLINE = ausgewaehlte Apps offline nehmen. */
enum class RuleAction { LOCKDOWN, APPS_OFFLINE }

data class AutoRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: RuleTrigger = RuleTrigger.TIME,
    /** Wochentage als Bitmaske: Bit 0 = Montag ... Bit 6 = Sonntag (nur TIME). */
    val days: Int = 0b1111111,
    /** Fenster in Minuten ab Mitternacht; start > end bedeutet ueber Nacht (nur TIME). */
    val startMin: Int = 0,
    val endMin: Int = 0,
    /** Netzname (nur WIFI). */
    val ssid: String = "",
    /** true = aktiv wenn mit [ssid] verbunden, false = aktiv wenn NICHT damit verbunden (nur WIFI). */
    val whenConnected: Boolean = true,
    val action: RuleAction = RuleAction.LOCKDOWN,
    /** Betroffene Pakete (nur APPS_OFFLINE). */
    val packages: List<String> = emptyList()
)

object RuleEval {

    /** Tag innerhalb des Fensters? Fenster [start,end) in Minuten; start>end = ueber Mitternacht. */
    fun inWindow(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean {
        if (startMin == endMin) return false // leeres Fenster
        return if (startMin < endMin) minuteOfDay in startMin until endMin
        else minuteOfDay >= startMin || minuteOfDay < endMin
    }

    /**
     * Ist die Regel JETZT aktiv?
     * @param dayMon0 Wochentag 0=Montag .. 6=Sonntag
     * @param wifiSsid aktuell verbundene SSID oder null (nicht verbunden / nicht lesbar)
     */
    fun active(rule: AutoRule, dayMon0: Int, minuteOfDay: Int, wifiSsid: String?): Boolean {
        if (!rule.enabled) return false
        return when (rule.trigger) {
            RuleTrigger.TIME -> {
                val dayHit = (rule.days shr dayMon0) and 1 == 1
                // Bei Fenster ueber Mitternacht gilt der STARTTAG; nach Mitternacht zaehlt der Vortag.
                if (rule.startMin <= rule.endMin) dayHit && inWindow(minuteOfDay, rule.startMin, rule.endMin)
                else {
                    val startDayActive = dayHit && minuteOfDay >= rule.startMin
                    val prevDay = (rule.days shr ((dayMon0 + 6) % 7)) and 1 == 1
                    val afterMidnight = prevDay && minuteOfDay < rule.endMin
                    startDayActive || afterMidnight
                }
            }
            RuleTrigger.WIFI -> (wifiSsid != null && wifiSsid == rule.ssid) == rule.whenConnected
        }
    }

    /**
     * Minuten bis zur naechsten Fenstergrenze aller Zeit-Regeln (fuer den naechsten Weckruf).
     * null = keine Zeit-Regel (dann nur Ereignisse/Fallback). Scannt bis zu 8 Tage voraus.
     */
    fun nextEdgeMinutes(rules: List<AutoRule>, dayMon0: Int, minuteOfDay: Int): Int? {
        val edges = rules.filter { it.enabled && it.trigger == RuleTrigger.TIME && it.startMin != it.endMin }
        if (edges.isEmpty()) return null
        var best: Int? = null
        for (ahead in 0..8) {
            val day = (dayMon0 + ahead) % 7
            for (r in edges) {
                for (edge in listOf(r.startMin, r.endMin)) {
                    val delta = ahead * 1440 + edge - minuteOfDay
                    if (delta > 0 && (best == null || delta < best!!)) best = delta
                }
            }
            if (best != null && ahead >= 1) break // eine volle Tagesrunde reicht fuer das Minimum
        }
        return best
    }
}
