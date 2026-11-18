package de.minipc.appopsshizuku.appops

import android.content.Context

/**
 * "Fuer diesen Nutzer entfernen": Ausweichweg, wenn `pm disable-user` abgelehnt wird.
 *
 * Laut AOSP (PackageManagerService.setEnabledSettings) darf die Shell Apps deaktivieren, AUSSER
 * geschuetzte Pakete ("Cannot disable a protected package"). Hersteller (z. B. OnePlus/ColorOS)
 * erweitern diese Sperre fuer eigene Apps. `pm uninstall -k --user 0` nimmt die App nur aus dem
 * Hauptnutzer: das APK bleibt im System, die Daten bleiben (-k), und `cmd package install-existing`
 * holt sie jederzeit zurueck - so arbeiten auch Debloater wie Canta oder UAD.
 *
 * Reine Logik (JVM-testbar); die Shell-Aufrufe macht der UserRemovalManager.
 */
object UserRemoval {

    enum class Block { OWN, SHIZUKU, CRITICAL, HOME, KEYBOARD }

    /**
     * Pakete, deren Entfernen das Geraet unbenutzbar machen kann (Bootschleife, keine Einstellungen,
     * keine Berechtigungsdialoge, kein Telefon). Werden nie angeboten.
     */
    val CRITICAL: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.shell",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.providers.settings",
        "com.android.providers.telephony",
        "com.android.providers.contacts",
        "com.android.providers.media",
        "com.android.providers.media.module",
        "com.google.android.providers.media.module",
        "com.android.providers.downloads",
        "com.android.externalstorage",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.android.networkstack",
        "com.google.android.networkstack",
        "com.android.server.telecom",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.se",
        "com.android.webview",
        "com.google.android.webview",
        "com.android.inputdevices",
        "com.android.keychain",
        "com.android.location.fused",
        "com.android.provision",
        "com.google.android.setupwizard",
        "com.oplus.wirelesssettings",
        "com.oplus.securitypermission",
        "com.coloros.safecenter",
        "com.oplus.battery",
        "com.android.launcher3"
    )

    private const val SHIZUKU = "moe.shizuku.privileged.api"

    /** Warum das Entfernen nicht angeboten wird; null = erlaubt (mit Warnung). */
    fun blockReason(pkg: String, ownPackage: String, homePackage: String?, keyboardPackage: String?): Block? = when {
        pkg == ownPackage -> Block.OWN
        pkg == SHIZUKU -> Block.SHIZUKU
        pkg in CRITICAL || pkg.startsWith("com.android.providers.") -> Block.CRITICAL
        pkg == homePackage -> Block.HOME
        pkg == keyboardPackage -> Block.KEYBOARD
        else -> null
    }

    /** IME-ID `pkg/.Klasse` -> Paket. */
    fun keyboardPackage(defaultInputMethod: String?): String? =
        defaultInputMethod?.trim()?.substringBefore('/')?.takeIf { it.isNotEmpty() && it != "null" }

    /**
     * Lesbarer Grund aus der Shell-Antwort: erste Zeile mit "Exception"/"Error"/"Failure", ohne
     * Java-Klassenpraefix. Beispiel: "Cannot disable a protected package: com.x".
     */
    fun reason(output: String): String? {
        val line = output.lineSequence().map { it.trim() }.firstOrNull {
            it.contains("Exception", true) || it.startsWith("Error", true) || it.startsWith("Failure", true)
        } ?: return null
        return line
            .replace(Regex("""^([\w.$]+(Exception|Error)):\s*"""), "")
            .replace(Regex("""^(Error|Failure):?\s*"""), "")
            .trim().ifEmpty { line }
    }
}

/**
 * Ausweich-Kette, wenn eine (System-)App sich nicht deaktivieren laesst. Gemessen auf OnePlus /
 * OxygenOS (Android 17): `disable-user` wird angenommen, aber ignoriert ("new state: default"),
 * `uninstall --user 0` mit "only root can delete system app for a particular user" abgelehnt.
 * Belegt aus AOSP: die Shell hat SUSPEND_APPS und CHANGE_COMPONENT_ENABLED_STATE, aber kein
 * MANAGE_USERS (`pm hide` scheidet damit aus).
 *
 * Reihenfolge: staerkster Hebel zuerst, jeder Schritt wird zurueckgelesen.
 */
object DisableChain {

    enum class Step {
        /** `pm disable-user` - Standardweg. */
        DISABLE_USER,
        /** `pm disable-until-used` - anderer Zustand, den Herstellersperren oft nicht abdecken. */
        DISABLE_UNTIL_USED,
        /** `pm uninstall -k --user 0` - APK und Daten bleiben, wiederherstellbar. */
        REMOVE_FOR_USER,
        /**
         * Als Geraeteinhaber verstecken (DevicePolicyManager.setApplicationHidden). Nur moeglich,
         * wenn App Ops Shizuku Geraeteinhaber ist; anderer Weg als die Shell-Befehle.
         */
        HIDE_AS_OWNER,
        /**
         * Letzter Ausweg: einfrieren + beenden + Hintergrund sperren. Schwaecher: das System kann
         * eine eingefrorene App weiterhin im Hintergrund starten.
         */
        NEUTRALIZE
    }

    val ORDER = listOf(
        Step.DISABLE_USER, Step.DISABLE_UNTIL_USED, Step.REMOVE_FOR_USER, Step.HIDE_AS_OWNER, Step.NEUTRALIZE
    )

    /** Shell-Befehl je Schritt (NEUTRALIZE besteht aus mehreren, siehe Manager). */
    fun command(step: Step, pkg: String): List<String>? = when (step) {
        Step.DISABLE_USER -> listOf("pm", "disable-user", "--user", "0", pkg)
        Step.DISABLE_UNTIL_USED -> listOf("pm", "disable-until-used", "--user", "0", pkg)
        Step.REMOVE_FOR_USER -> listOf("pm", "uninstall", "-k", "--user", "0", pkg)
        Step.HIDE_AS_OWNER, Step.NEUTRALIZE -> null
    }

    /** Enabled-Zustaende (PackageManager.COMPONENT_ENABLED_STATE_*), die als "deaktiviert" zaehlen. */
    fun isDisabledState(state: Int?): Boolean = state == 2 || state == 3 || state == 4

    /** Ergebnis eines Versuchs: geklappt, oder Grund aus der Shell-Antwort. */
    data class Attempt(val step: Step, val ok: Boolean, val reason: String?)

    /** Der Schritt, der gewirkt hat (null = keiner). */
    fun winner(attempts: List<Attempt>): Step? = attempts.firstOrNull { it.ok }?.step
}

/** Welche Apps WIR fuer den Nutzer entfernt haben (fuer Anzeige und Wiederherstellen). */
class UserRemovalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("user_removed", Context.MODE_PRIVATE)

    fun all(): Map<String, Long> = prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
    fun add(pkg: String, at: Long) = prefs.edit().putLong(pkg, at).apply()
    fun remove(pkg: String) = prefs.edit().remove(pkg).apply()
}
