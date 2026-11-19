package de.minipc.appopsshizuku.stealth

import de.minipc.appopsshizuku.ErrorLog
import android.content.Context
import android.util.Log
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Neu-Signierung modifizierter APKs mit einem selbst erzeugten RSA-Schlüssel.
 * Nutzt die apksig-Library (das Herz von apksigner) — läuft on-device.
 * Signaturschemata v1+v2+v3 → installierbar auf Android 7 bis 15+.
 */
object ApkSigner {

    private const val TAG = "ApkSigner"

    /** Persistenter RSA-Privatkey (PKCS#8-DER) im filesDir. */
    private const val KEY_FILE = "apk_signer_key.der"
    /** Passendes X.509-Zertifikat (DER) — apksig signiert mit PrivateKey + Cert-Kette. */
    private const val CERT_FILE = "apk_signer_cert.der"

    @Volatile
    private var cachedKeyPair: KeyPair? = null

    @Volatile
    private var cachedCert: X509Certificate? = null

    @Synchronized
    private fun getKeyPair(context: Context): Pair<KeyPair, X509Certificate> {
        cachedKeyPair?.let { return it to cachedCert!! }

        // Persistenz: Ohne Speicherung würde bei jedem Prozess-Neustart ein neuer
        // Schlüssel generiert -> bereits gepatchte Apps ließen sich danach nicht
        // mehr updaten ("INSTALL_UPDATE_INCOMPATIBLE / signatures do not match").
        val keyFile = File(context.filesDir, KEY_FILE)
        val certFile = File(context.filesDir, CERT_FILE)
        if (keyFile.isFile && certFile.isFile) {
            try {
                val kp = loadKeyPair(keyFile, certFile)
                cachedKeyPair = kp.first
                cachedCert = kp.second
                return kp
            } catch (e: Exception) {
                ErrorLog.e(TAG, "Persistenter Signaturschlüssel unlesbar ($KEY_FILE/$CERT_FILE), generiere neu", e)
                keyFile.delete()
                certFile.delete()
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        val dn = X500Name("CN=AppOpsShizuku, O=mini-pc")
        val now = Date()
        val notAfter = Date(now.time + 30L * 365 * 24 * 3600 * 1000)
        val builder = JcaX509v3CertificateBuilder(
            dn,
            BigInteger(160, SecureRandom()),
            now,
            notAfter,
            dn,
            kp.public
        )
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(contentSigner))
        cert.checkValidity()

        try {
            FileOutputStream(keyFile).use { it.write(kp.private.encoded) }
            FileOutputStream(certFile).use { it.write(cert.encoded) }
        } catch (e: Exception) {
            ErrorLog.e(TAG, "Signaturschlüssel konnte nicht persistiert werden", e)
        }

        cachedKeyPair = kp
        cachedCert = cert
        return kp to cert
    }

    /** Lädt PrivateKey (PKCS#8-DER) + Zertifikat (X.509-DER) als KeyPair. */
    private fun loadKeyPair(keyFile: File, certFile: File): Pair<KeyPair, X509Certificate> {
        val privateKey = keyFile.inputStream().use { input ->
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(input.readBytes()))
        }
        val cert = FileInputStream(certFile).use { input ->
            CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        }
        return KeyPair(cert.publicKey, privateKey) to cert
    }

    fun sign(context: Context, input: File, output: File) {
        val (kp, cert) = getKeyPair(context)
        val config = ApkSigner.SignerConfig.Builder("CERT", kp.private, listOf(cert)).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setCreatedBy("AppOpsShizuku")
            .build()
            .sign()
    }
}
