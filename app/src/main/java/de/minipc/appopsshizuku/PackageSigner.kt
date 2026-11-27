package de.minipc.appopsshizuku

import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * SHA-256 der Signaturzertifikate einer installierten App - als Vertrauensanker fuer Pakete, denen diese
 * App etwas anvertraut (Vault-Begleit-App, Starter-Apps fuer "Nur bei Bedarf"). Ein Paketname allein
 * beweist nichts: nach einer Deinstallation kann eine andere App denselben Namen belegen.
 */
object PackageSigner {

    /** Fingerabdruck der aktuellen Signierer (mehrere sortiert, mit ',' getrennt); null = nicht lesbar. */
    fun fingerprint(pm: PackageManager, pkg: String): String? = runCatching {
        val certs: List<ByteArray> = if (Build.VERSION.SDK_INT >= 28) {
            val si = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            si?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.map { it.toByteArray() }.orEmpty()
        }
        fingerprintOf(certs)
    }.getOrNull()

    /** Reine Rechnung (JVM-testbar): SHA-256 je Zertifikat, hex, sortiert. null bei leerer Liste. */
    fun fingerprintOf(certs: List<ByteArray>): String? {
        if (certs.isEmpty()) return null
        return certs.map { c ->
            MessageDigest.getInstance("SHA-256").digest(c).joinToString("") { "%02x".format(it) }
        }.sorted().joinToString(",")
    }
}
