package de.minipc.appopsshizuku.appops

data class AppOp(
    val opName: String,
    val label: String,
    val permissionName: String?,
    val category: String = "Privacy",
    /**
     * true = der Zustand steht NICHT verlaesslich unter `cmd appops get <opName>` (keine eigene AppOp,
     * z. B. Hintergrund-Standort) und wird stattdessen aus `dumpsys package` (Grant-Flag) gelesen.
     */
    val readViaPermission: Boolean = false
)

/**
 * Privacy-AppOps fuer das Profil. READ/WRITE_CLIPBOARD existieren erst ab Android 10 — Laufzeit-Probe.
 *
 * Namensfalle: opName ist der AppOp-Name (`cmd appops get`), permissionName die Runtime-Permission (`pm revoke`).
 * Sie unterscheiden sich, z. B. FINE_LOCATION (AppOp) vs. android.permission.ACCESS_FINE_LOCATION —
 * `cmd appops get <pkg> ACCESS_FINE_LOCATION` antwortet nur "Unknown operation string".
 * MANAGE_EXTERNAL_STORAGE ist KEINE aenderbare Runtime-Permission (`pm revoke` -> SecurityException),
 * deshalb reine AppOp (permissionName = null, Hebel `cmd appops set ... deny|allow`).
 * Ops, die ein Geraet nicht kennt, erscheinen in der UI als "nicht setzbar" (Probe statt Annahme).
 */
object PrivacyOps {
    val PRIVACY_OPS: List<AppOp> = listOf(
        // --- Privacy (Berechtigungen, pm grant/revoke-Hebel) ---
        AppOp("CAMERA", "Kamera", "android.permission.CAMERA"),
        AppOp("RECORD_AUDIO", "Mikrofon", "android.permission.RECORD_AUDIO"),
        AppOp("READ_CONTACTS", "Kontakte lesen", "android.permission.READ_CONTACTS"),
        AppOp("WRITE_CONTACTS", "Kontakte schreiben", "android.permission.WRITE_CONTACTS"),
        AppOp("READ_CALENDAR", "Kalender lesen", "android.permission.READ_CALENDAR"),
        AppOp("WRITE_CALENDAR", "Kalender schreiben", "android.permission.WRITE_CALENDAR"),
        AppOp("READ_CALL_LOG", "Anrufliste lesen", "android.permission.READ_CALL_LOG"),
        AppOp("WRITE_CALL_LOG", "Anrufliste schreiben", "android.permission.WRITE_CALL_LOG"),
        AppOp("GET_ACCOUNTS", "Konten", "android.permission.GET_ACCOUNTS"),
        AppOp("READ_CLIPBOARD", "Zwischenablage lesen", null),
        AppOp("WRITE_CLIPBOARD", "Zwischenablage schreiben", null),
        AppOp("BODY_SENSORS", "Koerper-Sensoren", "android.permission.BODY_SENSORS"),
        AppOp("ACTIVITY_RECOGNITION", "Aktivitaetserkennung", "android.permission.ACTIVITY_RECOGNITION"),
        AppOp("READ_PHONE_STATE", "Telefonstatus", "android.permission.READ_PHONE_STATE"),
        AppOp("READ_SMS", "SMS lesen", "android.permission.READ_SMS"),
        AppOp("RECEIVE_SMS", "SMS empfangen", "android.permission.RECEIVE_SMS"),
        AppOp("BLUETOOTH_SCAN", "Bluetooth-Suche", "android.permission.BLUETOOTH_SCAN"),
        AppOp("NEARBY_WIFI_DEVICES", "WLAN-Geräte in der Nähe", "android.permission.NEARBY_WIFI_DEVICES"),
        // Android 17 (API 37): auf aelteren Geraeten unbekannt (AppOp UND Permission) -> "nicht setzbar".
        // Zustand kommt aus dem Grant-Flag, da der AppOp-Name auf Android 17 nicht gesichert ist.
        AppOp(
            "ACCESS_LOCAL_NETWORK", "Lokales Netzwerk", "android.permission.ACCESS_LOCAL_NETWORK",
            readViaPermission = true
        ),
        // --- Standort (Paar FINE/COARSE: pm revoke FINE laesst COARSE erteilt = "nur ungefaehr") ---
        // Live Android 14: nach pm revoke bleibt `cmd appops get` bei "allow" (Paket-Modus folgt der Permission
        // nicht) -> Rücklesen aus dem Grant-Flag, sonst wirkt ein gelungener Entzug wie "nicht änderbar".
        AppOp(
            "FINE_LOCATION", "Genauer Standort", "android.permission.ACCESS_FINE_LOCATION", "Standort",
            readViaPermission = true
        ),
        AppOp(
            "COARSE_LOCATION", "Ungefährer Standort", "android.permission.ACCESS_COARSE_LOCATION", "Standort",
            readViaPermission = true
        ),
        // Keine eigene AppOp (Modus foreground/allow der Standort-Ops) -> Zustand aus dem Grant-Flag.
        AppOp(
            "ACCESS_BACKGROUND_LOCATION", "Standort im Hintergrund",
            "android.permission.ACCESS_BACKGROUND_LOCATION", "Standort", readViaPermission = true
        ),
        AppOp(
            "ACCESS_MEDIA_LOCATION", "Standort in Foto-EXIF",
            "android.permission.ACCESS_MEDIA_LOCATION", "Standort"
        ),
        // --- Medien (Android 13+, pm grant/revoke; auf aelteren Geraeten per Probe nicht setzbar) ---
        AppOp("READ_MEDIA_IMAGES", "Fotos lesen", "android.permission.READ_MEDIA_IMAGES", "Medien"),
        AppOp("READ_MEDIA_VIDEO", "Videos lesen", "android.permission.READ_MEDIA_VIDEO", "Medien"),
        AppOp("READ_MEDIA_AUDIO", "Audio lesen", "android.permission.READ_MEDIA_AUDIO", "Medien"),
        AppOp(
            "READ_MEDIA_VISUAL_USER_SELECTED", "Ausgewaehlte Fotos/Videos",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "Medien"
        ),
        AppOp("MANAGE_EXTERNAL_STORAGE", "Alle Dateien", null, "Medien"),
        // --- Hintergrund (reine AppOps, cmd appops set-Hebel) ---
        AppOp("RUN_IN_BACKGROUND", "Hintergrund-Ausfuehrung", null, "Hintergrund"),
        AppOp("RUN_ANY_IN_BACKGROUND", "Hintergrund-Ausfuehrung (beliebig)", null, "Hintergrund"),
        AppOp("START_FOREGROUND_SERVICES_FROM_BACKGROUND", "FGS aus Hintergrund starten", null, "Hintergrund"),
        AppOp("GET_USAGE_STATS", "Nutzungsstatistik", null, "Hintergrund"),
        // --- Identitaet ---
        AppOp("READ_DEVICE_IDENTIFIERS", "Geraete-IDs lesen", null, "Identitaet"),
        AppOp("MOCK_LOCATION", "Standort vortaeuschen", null, "Identitaet"),
        // --- System-UI ---
        AppOp("SYSTEM_ALERT_WINDOW", "Ueber anderen Apps anzeigen", null, "System-UI"),
        AppOp("ACCESS_NOTIFICATIONS", "Benachrichtigungen", null, "System-UI"),
        // AppOp heisst POST_NOTIFICATION (ohne S); pm-Hebel live auf Android 14 belegt.
        AppOp("POST_NOTIFICATION", "Benachrichtigungen senden", "android.permission.POST_NOTIFICATIONS", "System-UI"),
        AppOp("TURN_SCREEN_ON", "Bildschirm einschalten", null, "System-UI"),
        AppOp("PICTURE_IN_PICTURE", "Bild-in-Bild", null, "System-UI")
    )

    val MODES = listOf("default", "allow", "ignore", "deny")
}
