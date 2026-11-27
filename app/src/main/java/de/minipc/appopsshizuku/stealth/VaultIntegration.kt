package de.minipc.appopsshizuku.stealth

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import de.minipc.appopsshizuku.ErrorLog
import de.minipc.appopsshizuku.PackageSigner

/**
 * Wem darf die PIN des Vaults anvertraut werden? Ein Paketname oder eine Content-Provider-Authority allein
 * beweisen nichts: ist die Begleit-App nicht installiert, kann eine fremde App die Authority belegen oder
 * den Paketnamen annehmen und bekaeme dann PIN und Zielpaket. Deshalb wird beim ersten Mal der Signierer
 * der Begleit-App gemerkt (Trust on first use) und danach verlangt.
 */
object VaultTrust {
    enum class Verdict { TRUST, TRUST_AND_PIN, REJECT }

    /**
     * @param providerPackage Paket, dem die Authority gehoert (null = niemand)
     * @param signer Fingerabdruck der installierten Begleit-App (null = nicht lesbar/nicht installiert)
     * @param pinned beim ersten Vertrauen gemerkter Fingerabdruck (null = noch keiner)
     */
    fun decide(expectedPackage: String, providerPackage: String?, signer: String?, pinned: String?): Verdict = when {
        providerPackage != expectedPackage -> Verdict.REJECT
        signer == null -> Verdict.REJECT
        pinned == null -> Verdict.TRUST_AND_PIN
        pinned == signer -> Verdict.TRUST
        else -> Verdict.REJECT
    }
}

/**
 * Konfiguriert die CalcYou-Vault-App nach erfolgreichem Stealth-Patch.
 *
 * Bevorzugt über den ContentProvider (App muss nicht sichtbar gestartet werden).
 * Fallback: MainActivity mit Extras starten. Übergibt Zielpaket, Zielklasse und
 * die benutzerdefinierte PIN.
 */
object VaultIntegration {

    private const val VAULT_PACKAGE = "de.minipc.calcyou"
    private const val VAULT_ACTIVITY = "de.minipc.calcyou.MainActivity"
    private const val CONFIG_AUTHORITY = "content://de.minipc.calcyou.config"
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
        if (!trusted(context)) return
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

    /** Gehoert die Authority wirklich der (gemerkten) Begleit-App? Sonst geht weder PIN noch Ziel raus. */
    private fun trusted(context: Context): Boolean {
        val pm = context.packageManager
        val provider = runCatching {
            pm.resolveContentProvider(CONFIG_AUTHORITY.removePrefix("content://"), 0)?.packageName
        }.getOrNull()
        val signer = PackageSigner.fingerprint(pm, VAULT_PACKAGE)
        val prefs = context.getSharedPreferences("vault_trust", Context.MODE_PRIVATE)
        val pinned = prefs.getString("signer", null)
        return when (VaultTrust.decide(VAULT_PACKAGE, provider, signer, pinned)) {
            VaultTrust.Verdict.TRUST -> true
            VaultTrust.Verdict.TRUST_AND_PIN -> {
                prefs.edit().putString("signer", signer).apply()
                true
            }
            VaultTrust.Verdict.REJECT -> {
                ErrorLog.w("VaultIntegration", "Vault-Begleit-App nicht vertrauenswürdig (Paket/Signatur) - PIN nicht übergeben")
                false
            }
        }
    }
}
