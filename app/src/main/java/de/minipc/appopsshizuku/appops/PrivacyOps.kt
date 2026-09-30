package de.minipc.appopsshizuku.appops

data class AppOp(
    val opName: String,
    val label: String,
    val permissionName: String?,
    val category: String = "Privacy"
)

/** Privacy-AppOps fuer das Profil. READ/WRITE_CLIPBOARD existieren erst ab Android 10 — Laufzeit-Probe. */
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
        // --- Medien (Android 13+, pm grant/revoke; auf aelteren Geraeten per Probe nicht setzbar) ---
        AppOp("READ_MEDIA_IMAGES", "Fotos lesen", "android.permission.READ_MEDIA_IMAGES", "Medien"),
        AppOp("READ_MEDIA_VIDEO", "Videos lesen", "android.permission.READ_MEDIA_VIDEO", "Medien"),
        AppOp("READ_MEDIA_AUDIO", "Audio lesen", "android.permission.READ_MEDIA_AUDIO", "Medien"),
        AppOp(
            "READ_MEDIA_VISUAL_USER_SELECTED", "Ausgewaehlte Fotos/Videos",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "Medien"
        ),
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
        AppOp("TURN_SCREEN_ON", "Bildschirm einschalten", null, "System-UI"),
        AppOp("PICTURE_IN_PICTURE", "Bild-in-Bild", null, "System-UI")
    )

    val MODES = listOf("default", "allow", "ignore", "deny")
}
