package de.minipc.appopsshizuku.appops

data class AppOp(val opName: String, val label: String, val permissionName: String?)

/** Privacy-AppOps fuer das Profil. READ/WRITE_CLIPBOARD existieren erst ab Android 10 — Laufzeit-Probe. */
object PrivacyOps {
    val PRIVACY_OPS: List<AppOp> = listOf(
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
        AppOp("ACTIVITY_RECOGNITION", "Aktivitaetserkennung", "android.permission.ACTIVITY_RECOGNITION")
    )

    val MODES = listOf("default", "allow", "ignore", "deny")
}
