package de.minipc.appopsshizuku.stealth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Umhuellt/entschluesselt Schluesselmaterial. [wrap] liefert alles, was [unwrap] zum Entschluesseln braucht. */
interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * Ablage des Signaturschluessels (PKCS#8-DER) im privaten App-Speicher.
 *
 * Frueher lag er im Klartext in `filesDir` - ein Backup, eine Geraete-Uebertragung oder ein Dump des
 * Verzeichnisses haette gereicht, um Updates fuer alle gepatchten Fremd-Apps zu signieren. Jetzt ist die
 * Datei mit einem nicht exportierbaren Android-Keystore-Schluessel (AES-GCM) umhuellt. Die Signatur-
 * Identitaet bleibt dabei erhalten: ein vorhandener Klartext-Schluessel wird beim ersten Lesen nur
 * umhuellt, nicht neu erzeugt (sonst liessen sich bereits gepatchte Apps nicht mehr aktualisieren).
 *
 * Reine JVM-Logik ueber [KeyWrapper] - unit-testbar ohne Geraet.
 */
object SignerKeyStorage {

    /** Kennung am Dateianfang; ein PKCS#8-DER beginnt dagegen mit 0x30. */
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'O'.code.toByte(), 'S'.code.toByte(), 'K'.code.toByte(), 1)

    fun isWrapped(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    /**
     * Schluessel lesen. Liegt er noch im Klartext, wird er umhuellt zurueckgeschrieben (Migration).
     * @throws java.security.GeneralSecurityException wenn die Umhuellung nicht mehr lesbar ist
     *   (z. B. der Keystore-Schluessel fehlt nach einer Wiederherstellung auf einem anderen Geraet)
     */
    fun read(file: File, wrapper: KeyWrapper): ByteArray {
        val raw = file.readBytes()
        if (isWrapped(raw)) return wrapper.unwrap(raw.copyOfRange(MAGIC.size, raw.size))
        // Altbestand im Klartext: umhuellt zurueckschreiben, dieselbe Identitaet behalten
        runCatching { write(file, raw, wrapper) }
        return raw
    }

    /** Schluessel umhuellt und atomar (Temp-Datei + Umbenennen) schreiben. */
    fun write(file: File, plain: ByteArray, wrapper: KeyWrapper) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(MAGIC + wrapper.wrap(plain))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("Schluessel konnte nicht gespeichert werden")
        }
    }
}

/** AES-256-GCM mit einem nicht exportierbaren Schluessel im Android-Keystore. */
class KeystoreKeyWrapper(private val alias: String = "aos_signer_wrap") : KeyWrapper {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)          // IV ist 12 Byte lang
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, wrapped, 0, IV_LEN))
        return c.doFinal(wrapped, IV_LEN, wrapped.size - IV_LEN)
    }

    private companion object { const val IV_LEN = 12 }
}
