package de.minipc.appopsshizuku.appops

import android.content.Context

/**
 * Geraeteinhaber (Device Owner): staerkster Weg ohne Root. Ein Geraeteinhaber darf ueber die
 * Geraeteverwaltung (DevicePolicyManager.setApplicationHidden) auch System-Apps verstecken -
 * ein anderer Weg als die Shell-Befehle, die manche Hersteller sperren. Eingerichtet wird er mit
 * `dpm set-device-owner` (ueber Shizuku oder einmalig per adb vom PC; beides laeuft als Shell).
 *
 * Android verlangt dafuer: keine Konten auf dem Geraet und nur ein Nutzer (keine weiteren Nutzer
 * oder Profile). Diese Vorbedingungen raeumt die App NICHT selbst ab - das entscheidet der Nutzer.
 */
object DeviceOwner {

    enum class SetupResult { OK, ACCOUNTS, USERS, ALREADY_SET, OTHER }

    /** Antwort von `dpm set-device-owner` einordnen (Wortlaut je Android-Version verschieden). */
    fun classify(output: String): SetupResult {
        val t = output.lowercase()
        return when {
            t.contains("success") && !t.contains("exception") -> SetupResult.OK
            t.contains("account") -> SetupResult.ACCOUNTS
            t.contains("several users") || t.contains("multiple users") || t.contains("more than one user") ||
                (t.contains("user") && t.contains("already") && t.contains("several")) -> SetupResult.USERS
            t.contains("already") && (t.contains("owner") || t.contains("provisioned")) -> SetupResult.ALREADY_SET
            else -> SetupResult.OTHER
        }
    }
}

/** Von uns per Geraeteinhaber versteckte Apps (fuer Anzeige und Wiederherstellen). */
class HiddenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("owner_hidden", Context.MODE_PRIVATE)
    fun all(): Set<String> = prefs.all.keys
    fun add(pkg: String) = prefs.edit().putLong(pkg, System.currentTimeMillis()).apply()
    fun remove(pkg: String) = prefs.edit().remove(pkg).apply()
}
