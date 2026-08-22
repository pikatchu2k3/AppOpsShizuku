package de.minipc.appopsshizuku.stealth

import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Neu-Signierung modifizierter APKs mit einem selbst erzeugten RSA-Schlüssel.
 * Nutzt die apksig-Library (das Herz von apksigner) — läuft on-device.
 * Signaturschemata v1+v2+v3 → installierbar auf Android 7 bis 15+.
 */
object ApkSigner {

    @Volatile
    private var cachedKeyPair: KeyPair? = null

    @Volatile
    private var cachedCert: X509Certificate? = null

    @Synchronized
    private fun getKeyPair(): Pair<KeyPair, X509Certificate> {
        cachedKeyPair?.let { return it to cachedCert!! }
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

        cachedKeyPair = kp
        cachedCert = cert
        return kp to cert
    }

    fun sign(input: File, output: File) {
        val (kp, cert) = getKeyPair()
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
