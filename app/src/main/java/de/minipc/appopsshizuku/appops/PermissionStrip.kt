package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.stealth.BinaryXml

/**
 * Berechtigungen beim Patchen aus dem Manifest entfernen (Beta).
 *
 * Anders als `pm revoke` ist das eine Eigenschaft der installierten APK: Was nicht im Manifest
 * steht, bekommt die App nie - auch nicht nach einem Neustart, ohne laufendes Shizuku und ohne
 * Rueckfrage-Dialog (`requestPermissions` liefert sofort "abgelehnt").
 *  - [StripGroup.OFFLINE]: ohne INTERNET darf die App-UID keine Netz-Sockets oeffnen (Kernel/netd).
 *  - [StripGroup.AD_ID]: ohne AD_ID liefert Google Play-Dienste Apps mit targetSdk >= 33 eine
 *    Werbe-ID aus Nullen (Google-Doku). Bei kleinerem targetSdk ist das nicht zugesagt.
 *  - [StripGroup.APP_LIST]: ohne QUERY_ALL_PACKAGES sieht die App nur noch die Pakete, die sie
 *    im Manifest anfragt - die Paket-Sichtbarkeit gilt aber erst ab targetSdk 30.
 * Nichts davon greift in den Code ein; die App muss mit "abgelehnt" selbst umgehen.
 */
enum class StripGroup(val permissions: Set<String>) {
    OFFLINE(setOf("android.permission.INTERNET")),
    AD_ID(
        setOf(
            "com.google.android.gms.permission.AD_ID",
            // Privacy Sandbox (Android 13+ mit AdServices): Werbe-ID, Attribution, Themen
            "android.permission.ACCESS_ADSERVICES_AD_ID",
            "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
            "android.permission.ACCESS_ADSERVICES_TOPICS",
            "android.permission.ACCESS_ADSERVICES_CUSTOM_AUDIENCE"
        )
    ),
    APP_LIST(setOf("android.permission.QUERY_ALL_PACKAGES")),
    LOCATION(
        setOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION"
        )
    ),
    PHONE(setOf("android.permission.READ_PHONE_STATE", "android.permission.READ_PHONE_NUMBERS")),
    ACCOUNTS(setOf("android.permission.GET_ACCOUNTS"));

    /**
     * Ab welchem targetSdk der Effekt laut Google-Doku zugesagt ist; null = immer.
     * Darunter wird trotzdem entfernt, das Ergebnis meldet es aber als "nicht zugesagt".
     */
    val minTargetSdk: Int?
        get() = when (this) {
            AD_ID -> 33
            APP_LIST -> 30
            else -> null
        }
}

object PermissionStrip {

    /** Alle Berechtigungen der gewaehlten Gruppen. */
    fun targets(groups: Set<StripGroup>): Set<String> = groups.flatMapTo(LinkedHashSet()) { it.permissions }

    /** Welche der Ziel-Berechtigungen stehen ueberhaupt im Manifest? */
    fun present(manifest: ByteArray, groups: Set<StripGroup>): Set<String> {
        val declared = BinaryXml.permissions(manifest).toSet()
        return targets(groups).filterTo(LinkedHashSet()) { it in declared }
    }

    /** Entfernt die Berechtigungen der [groups]. Gibt [manifest] selbst zurueck, wenn nichts zu tun war. */
    fun apply(manifest: ByteArray, groups: Set<StripGroup>): ByteArray {
        val toRemove = present(manifest, groups)
        if (toRemove.isEmpty()) return manifest
        return BinaryXml.removePermissions(manifest, toRemove)
    }

    /**
     * Vorher/Nachher aus den echten Manifesten (Original gegen gepatchte APK), nicht aus dem Wunsch.
     * @param removed stand im Original und fehlt in der gepatchten APK.
     * @param stillPresent gewaehlt, steht aber weiterhin drin (darf nicht vorkommen -> Fehler).
     * @param notPromised Gruppen mit entfernten Rechten, deren Effekt bei [targetSdk] nicht zugesagt ist.
     */
    class Report(
        val removed: List<String>,
        val stillPresent: List<String>,
        val notPromised: List<StripGroup>
    ) {
        val ok: Boolean get() = stillPresent.isEmpty()
    }

    fun compare(
        originalManifest: ByteArray,
        patchedManifest: ByteArray,
        groups: Set<StripGroup>,
        targetSdk: Int?
    ): Report {
        val before = present(originalManifest, groups)
        val after = present(patchedManifest, groups)
        val removed = before.filter { it !in after }
        val notPromised = groups.filter { g ->
            val min = g.minTargetSdk
            min != null && targetSdk != null && targetSdk < min && g.permissions.any { it in removed }
        }
        return Report(removed, after.toList(), notPromised)
    }

    /** "android.permission.INTERNET" -> "INTERNET" (fuer die Ergebnisanzeige). */
    fun shortName(permission: String): String = permission.substringAfterLast('.')
}
