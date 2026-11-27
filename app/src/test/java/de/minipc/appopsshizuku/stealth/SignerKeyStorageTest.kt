package de.minipc.appopsshizuku.stealth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Der Signaturschluessel liegt nie im Klartext - und eine vorhandene Identitaet bleibt erhalten. */
class SignerKeyStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Gleiche Huelle wie [KeystoreKeyWrapper] (AES-GCM, IV vorangestellt), nur mit JVM-Schluessel. */
    private class JvmWrapper(keyBytes: ByteArray) : KeyWrapper {
        private val key = SecretKeySpec(keyBytes, "AES")
        override fun wrap(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv + c.doFinal(plain)
        }
        override fun unwrap(wrapped: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, 12))
            return c.doFinal(wrapped, 12, wrapped.size - 12)
        }
    }

    private val wrapper = JvmWrapper(ByteArray(32) { it.toByte() })
    // PKCS#8 beginnt mit 0x30 (SEQUENCE)
    private val secret = byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x02, 9, 8, 7, 6, 5, 4, 3, 2, 1)

    @Test fun geschriebeneDateiIstNichtImKlartext() {
        val f = tmp.newFile("key.der")
        SignerKeyStorage.write(f, secret, wrapper)
        val onDisk = f.readBytes()
        assertTrue(SignerKeyStorage.isWrapped(onDisk))
        assertFalse(onDisk.toList().windowed(secret.size).any { it == secret.toList() })
        assertArrayEquals(secret, SignerKeyStorage.read(f, wrapper))
    }

    @Test fun klartextAltbestandBleibtDieselbeIdentitaetUndWirdUmhuellt() {
        val f = tmp.newFile("key.der")
        f.writeBytes(secret)
        assertFalse(SignerKeyStorage.isWrapped(f.readBytes()))
        // erstes Lesen liefert denselben Schluessel ...
        assertArrayEquals(secret, SignerKeyStorage.read(f, wrapper))
        // ... und hat die Datei danach umhuellt
        assertTrue(SignerKeyStorage.isWrapped(f.readBytes()))
        assertArrayEquals(secret, SignerKeyStorage.read(f, wrapper))
    }

    @Test fun falscherHuellschluesselWirdNichtStillIgnoriert() {
        val f = tmp.newFile("key.der")
        SignerKeyStorage.write(f, secret, wrapper)
        try {
            SignerKeyStorage.read(f, JvmWrapper(ByteArray(32) { 99 }))
            fail("falscher Schluessel muss scheitern")
        } catch (e: GeneralSecurityException) {
            assertTrue(e is AEADBadTagException || e is GeneralSecurityException)
        }
    }

    @Test fun kaputteHuelleWirdErkannt() {
        val f = tmp.newFile("key.der")
        SignerKeyStorage.write(f, secret, wrapper)
        val b = f.readBytes(); b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte(); f.writeBytes(b)
        try { SignerKeyStorage.read(f, wrapper); fail() } catch (_: GeneralSecurityException) { }
    }

    @Test fun schreibenLaesstKeineTempDateiZurueck() {
        val f = tmp.newFile("key.der")
        SignerKeyStorage.write(f, secret, wrapper)
        assertFalse(java.io.File(f.parentFile, "key.der.tmp").exists())
    }
}
