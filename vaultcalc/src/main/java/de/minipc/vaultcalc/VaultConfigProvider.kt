package de.minipc.vaultcalc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Konfigurationskanal von AppOpsShizuku → VaultCalculator.
 *
 * AppOpsShizuku ruft nach erfolgreichem Stealth-Patch `call("set", ...)` auf und
 * übergibt Zielpaket, Zielklasse und optional eine benutzerdefinierte PIN. Die
 * Werte werden in SharedPreferences persistiert; MainActivity liest sie beim
 * Start der versteckten App aus.
 */
class VaultConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_SET || extras == null) {
            return null
        }
        val pkg = extras.getString(KEY_PACKAGE)
        val cls = extras.getString(KEY_CLASS)
        if (pkg.isNullOrBlank() || cls.isNullOrBlank()) {
            return null
        }
        val ctx = context ?: return null
        val editor = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(STORE_KEY_PACKAGE, pkg)
            .putString(STORE_KEY_CLASS, cls)
        // Benutzerdefinierte PIN optional mitspeichern (Default "12345" in MainActivity)
        extras.getString(KEY_SECRET_CODE)?.takeIf { it.isNotBlank() }?.let {
            editor.putString(STORE_KEY_SECRET_CODE, it)
        }
        editor.apply()
        return Bundle().apply {
            putBoolean(KEY_OK, true)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val AUTHORITY = "de.minipc.vaultcalc.config"
        const val METHOD_SET = "set"
        const val KEY_PACKAGE = "package"
        const val KEY_CLASS = "class"
        const val KEY_SECRET_CODE = "secret_code"
        const val KEY_OK = "ok"
        // WICHTIG: Gleiche Prefs-Datei wie MainActivity ("vault"), sonst liest
        // MainActivity die PIN/Ziel nicht (realer Bug 08/2026: Provider schrieb
        // in "vaultcalc.xml", MainActivity las "vault.xml" → alte PIN griff).
        private const val PREFS_NAME = "vault"
        private const val STORE_KEY_PACKAGE = "vault_package"
        private const val STORE_KEY_CLASS = "vault_class"
        private const val STORE_KEY_SECRET_CODE = "secret_code"
    }
}
