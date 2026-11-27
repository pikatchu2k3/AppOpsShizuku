package de.minipc.appopsshizuku.appops

/**
 * Prueft importierte Sicherungsdateien, BEVOR irgendetwas davon mit Shell-Rechten ausgefuehrt oder
 * gespeichert wird. Eine Sicherung ist eine fremde Datei: Profile werden geteilt (Funktion "Profil
 * teilen"), also darf ihr Inhalt weder beliebige Paketnamen noch Systempakete noch unbekannte Modi
 * einschleusen.
 *
 * Reine JVM-Logik (unit-testbar). [Sanitized.dropped] zaehlt, was verworfen wurde - die Oberflaeche
 * zeigt das vor dem Uebernehmen an.
 */
object ImportGuard {

    private const val MAX_PACKAGE_LENGTH = 255
    private const val MAX_NAME_LENGTH = 80
    private const val MAX_SSID_LENGTH = 32
    private const val MAX_RULE_PACKAGES = 500

    /** Gueltiger Java-Paketname mit mindestens einem Punkt (wie ihn Android verlangt). */
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /** Modi, die in einem Profil stehen duerfen. */
    val MODES: Set<String> = setOf("default", "reset", "allow", "ignore", "deny", WhileInUse.MODE)

    /** Standby-Stufen, die `am set-standby-bucket` kennt (siehe [standbyName]). */
    private val STANDBY_BUCKETS = setOf(10, 20, 30, 40, 45, 50)

    data class Sanitized<T>(val value: T, val dropped: Int)

    fun validPackage(pkg: String?): Boolean =
        pkg != null && pkg.length <= MAX_PACKAGE_LENGTH && PACKAGE.matches(pkg)

    /** Profile: gueltiges Paket, nur Katalog-Ops, nur bekannte Modi. */
    fun profiles(
        raw: Map<String, Map<String, String>>,
        catalog: Set<String> = PrivacyOps.PRIVACY_OPS.map { it.opName }.toSet()
    ): Sanitized<Map<String, Map<String, String>>> {
        var dropped = 0
        val out = LinkedHashMap<String, Map<String, String>>()
        for ((pkg, ops) in raw) {
            if (!validPackage(pkg)) { dropped += ops.size.coerceAtLeast(1); continue }
            val kept = ops.filter { (op, mode) -> op in catalog && mode in MODES }
            dropped += ops.size - kept.size
            if (kept.isNotEmpty()) out[pkg] = kept
        }
        return Sanitized(out, dropped)
    }

    /**
     * Verhaltens-Einstellungen: nur gueltige Paketnamen. Bei geschuetzten Paketen (System-UI, Einstellungen,
     * Launcher, Tastatur, Shizuku, diese App ...) werden "deaktiviert" und "eingefroren" verworfen - alles
     * andere (Akku-Ausnahme, Standby-Stufe) bleibt. Standby-Stufen nur aus der bekannten Menge.
     */
    fun behavior(
        raw: Map<String, BehaviorState>,
        isProtected: (String) -> Boolean
    ): Sanitized<Map<String, BehaviorState>> {
        var dropped = 0
        val out = LinkedHashMap<String, BehaviorState>()
        for ((pkg, st) in raw) {
            if (!validPackage(pkg)) { dropped++; continue }
            var fixed = st
            val bucket = fixed.standbyBucket
            if (bucket != null && bucket !in STANDBY_BUCKETS) {
                dropped++
                fixed = fixed.copy(standbyBucket = null)
            }
            if (isProtected(pkg) && (fixed.disabled == true || fixed.frozen == true)) {
                dropped++
                fixed = fixed.copy(disabled = null, frozen = null)
            }
            if (fixed != BehaviorState()) out[pkg] = fixed
        }
        return Sanitized(out, dropped)
    }

    /** Lifecycle-Eintraege: nur gueltige, nicht geschuetzte Pakete. */
    fun lifecycle(
        raw: Map<String, LifecycleEntry>,
        isProtected: (String) -> Boolean
    ): Sanitized<Map<String, LifecycleEntry>> {
        val kept = raw.filterKeys { validPackage(it) && !isProtected(it) }
        return Sanitized(kept, raw.size - kept.size)
    }

    /** Automatik-Regeln: Zahlen in den Bereich zwingen, Pakete wie bei [behavior] pruefen. */
    fun rules(raw: List<AutoRule>, isProtected: (String) -> Boolean): Sanitized<List<AutoRule>> {
        var dropped = 0
        val out = raw.mapNotNull { r ->
            if (r.id.isBlank() || r.id.length > MAX_NAME_LENGTH) { dropped++; return@mapNotNull null }
            val pkgs = r.packages.take(MAX_RULE_PACKAGES).filter { validPackage(it) && !isProtected(it) }
            dropped += r.packages.size - pkgs.size
            r.copy(
                name = r.name.take(MAX_NAME_LENGTH),
                days = r.days and 0b1111111,
                startMin = r.startMin.coerceIn(0, 24 * 60 - 1),
                endMin = r.endMin.coerceIn(0, 24 * 60 - 1),
                ssid = r.ssid.take(MAX_SSID_LENGTH),
                packages = pkgs
            )
        }
        return Sanitized(out, dropped)
    }

    /** "Nur waehrend Nutzung": gueltige, nicht geschuetzte Pakete. */
    fun whileInUse(
        raw: Map<String, Set<String>>,
        isProtected: (String) -> Boolean
    ): Sanitized<Map<String, Set<String>>> {
        val kept = raw.filterKeys { validPackage(it) && !isProtected(it) }
        return Sanitized(kept, raw.size - kept.size)
    }
}
