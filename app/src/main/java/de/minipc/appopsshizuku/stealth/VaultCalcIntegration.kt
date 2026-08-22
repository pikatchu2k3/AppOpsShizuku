package de.minipc.appopsshizuku.stealth

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Konfiguriert die VaultCalculator-App nach erfolgreichem Stealth-Patch.
 *
 * Bevorzugt über den ContentProvider (App muss nicht sichtbar gestartet werden).
 * Fallback: MainActivity mit Extras starten. Übergibt Zielpaket, Zielklasse und
 * die benutzerdefinierte PIN.
 */
object VaultCalcIntegration {

    private const val VAULT_PACKAGE = "de.minipc.vaultcalc"
    private const val VAULT_ACTIVITY = "de.minipc.vaultcalc.MainActivity"
    private const val CONFIG_AUTHORITY = "content://de.minipc.vaultcalc.config"
    private const val METHOD_SET = "set"
    private const val KEY_PACKAGE = "package"
    private const val KEY_CLASS = "class"
    private const val KEY_SECRET_CODE = "secret_code"
    private const val KEY_OK = "ok"
    private const val EXTRA_VAULT_PACKAGE = "vault_package"
    private const val EXTRA_VAULT_CLASS = "vault_class"
    private const val EXTRA_SECRET_CODE = "secret_code"

    /**
     * Übergibt der VaultCalculator-App die startbare Ziel-Component und PIN.
     */
    fun configureVaultCalculator(
        context: Context,
        targetPackage: String,
        targetActivity: String,
        secretCode: String? = null
    ) {
        val extras = Bundle().apply {
            putString(KEY_PACKAGE, targetPackage)
            putString(KEY_CLASS, targetActivity)
            secretCode?.takeIf { it.isNotBlank() }?.let { putString(KEY_SECRET_CODE, it) }
        }
        try {
            val result = context.contentResolver.call(
                Uri.parse(CONFIG_AUTHORITY),
                METHOD_SET,
                null,
                extras
            )
            if (result != null && result.getBoolean(KEY_OK, false)) {
                return
            }
        } catch (_: Throwable) {
            // Provider nicht verfügbar oder keine Berechtigung. Fallback versuchen.
        }
        try {
            val intent = Intent().apply {
                component = ComponentName(VAULT_PACKAGE, VAULT_ACTIVITY)
                putExtra(EXTRA_VAULT_PACKAGE, targetPackage)
                putExtra(EXTRA_VAULT_CLASS, targetActivity)
                secretCode?.takeIf { it.isNotBlank() }?.let {
                    putExtra(EXTRA_SECRET_CODE, it)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
            // VaultCalculator nicht installiert oder Activity nicht startbar.
        }
    }
}
