package de.minipc.appopsshizuku.appops

import android.content.Context

/**
 * Welche Apps schuetzt DIESE App gerade, und ist der Schutz ueberhaupt wirksam?
 *
 * Fast alle Hebel (Profil-Blockaden, Netz, "Nur bei Bedarf", "Nur waehrend Nutzung", Quarantaene,
 * Lockdown) brauchen ein laufendes Shizuku. Nach einem Neustart laeuft Shizuku nicht - dann ist der
 * eingestellte Schutz still inaktiv. Diese Klasse zaehlt die eingerichteten Apps (nur Lesen der eigenen
 * Speicher) und formuliert den Hinweis.
 */
object ProtectionStatus {

    /** Alle Apps, fuer die irgendein Schutz eingerichtet ist (Vereinigung ueber alle Speicher). */
    fun protectedPackages(context: Context): Set<String> {
        val app = context.applicationContext
        val out = mutableSetOf<String>()
        PrivacyProfileStore(app).allProfiles().forEach { (pkg, ops) ->
            if (ops.values.any { it == "deny" || it == "ignore" }) out += pkg
        }
        out += NetStore(app).allMarked().keys
        LifecycleStore(app).all().forEach { (pkg, e) ->
            if (e.onDemand == true || e.screenOffExit == true) out += pkg
        }
        out += WhileInUseStore(app).all().keys
        out += QuarantineStore(app).quarantined().keys
        LockdownStore(app).revoked.forEach { Lockdown.parseKey(it)?.let { (pkg, _) -> out += pkg } }
        return out
    }

    /**
     * Hinweistext fuer den Neustart: null = kein Hinweis noetig (kein Schutz eingerichtet oder Shizuku da).
     */
    fun bootHint(protectedCount: Int, shizukuUsable: Boolean): HintKind? = when {
        protectedCount <= 0 -> null
        shizukuUsable -> null
        else -> HintKind.SHIZUKU_MISSING
    }

    enum class HintKind { SHIZUKU_MISSING }
}

/**
 * Abweichung zwischen gespeichertem Profil und tatsaechlichem Geraetezustand (nach Neustart/Update
 * kann eine App z. B. neu installiert und damit wieder freigegeben worden sein).
 */
object DriftCheck {

    data class Item(val packageName: String, val opName: String, val want: String)

    /**
     * Ist der Ist-Zustand [actual] eine Abweichung vom gewuenschten [want]?
     * - "default"/"allow" als Wunsch erzwingt nichts -> keine Abweichung (nichts wiederherzustellen).
     * - Sonst zaehlt, was [OpEffect.isSetEffective] als "nicht wirksam gesetzt" ansieht.
     */
    fun isDrift(permissionName: String?, want: String, actual: String?): Boolean {
        if (want == "default" || want == "allow") return false
        return !OpEffect.isSetEffective(permissionName, want, actual)
    }
}
