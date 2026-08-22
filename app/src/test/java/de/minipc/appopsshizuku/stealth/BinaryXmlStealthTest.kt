package de.minipc.appopsshizuku.stealth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Verifiziert die Stealth-AXML-Surgery mit aapt2 (echter Android-Toolchain-Parser):
 * Das FOSS-Browser-Manifest wird gepatcht und per `aapt2 dump xmltree` geprueft,
 * ob excludeFromRecents=true in der Launcher-Activity steht bzw. die
 * LAUNCHER-Kategorie entfernt wurde.
 */
class BinaryXmlStealthTest {

    private val fossApk = File("/tmp/foss_original.apk")
    private val aapt2 = "/opt/android-sdk/build-tools/35.0.0/aapt2"

    private fun manifestFromApk(apk: File): ByteArray =
        ZipFile(apk).use { zf ->
            zf.getInputStream(zf.getEntry("AndroidManifest.xml")).use { it.readBytes() }
        }

    private fun aapt2Dump(manifest: ByteArray): String {
        val tmpZip = File.createTempFile("manifest_test", ".apk")
        ZipOutputStream(tmpZip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(manifest)
            zos.closeEntry()
        }
        val proc = ProcessBuilder(
            aapt2, "dump", "xmltree", tmpZip.absolutePath, "--file", "AndroidManifest.xml"
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        tmpZip.delete()
        return out
    }

    @Test
    fun setExcludeFromRecents_patziertFossManifestKorrekt() {
        if (!fossApk.exists()) { println("SKIP: /tmp/foss_original.apk fehlt"); return }
        val original = manifestFromApk(fossApk)
        val patched = BinaryXml.setExcludeFromRecents(original)
        assertFalse("Manifest muss sich aendern", patched.contentEquals(original))
        File("/tmp/original_manifest.axml").writeBytes(original)
        File("/tmp/patched_manifest.axml").writeBytes(patched)
        println("original=${original.size} patched=${patched.size}")

        val dump = aapt2Dump(patched)
        println("--- aapt2 dump (activity/excludeFromRecents-Zeilen) ---")
        println(dump.lines().filter {
            it.contains("activity") || it.contains("excludeFromRecents") ||
                it.contains("MAIN") || it.contains("LAUNCHER")
        }.joinToString("\n"))

        assertTrue("excludeFromRecents muss im Manifest stehen", dump.contains("excludeFromRecents"))
        assertTrue(
            "excludeFromRecents muss true sein (Boolean, echte ID 0x01010017)",
            dump.contains("excludeFromRecents(0x01010017)=(Boolean) true") ||
                dump.contains("excludeFromRecents(0x01010017)=(Boolean) 0x1") ||
                dump.contains("excludeFromRecents(0x01010017)=true")
        )
        // Launcher-Kategorie muss noch da sein (nur excludeFromRecents gepatcht)
        assertTrue("LAUNCHER-Kategorie bleibt erhalten", dump.contains("android.intent.category.LAUNCHER"))
    }

    @Test
    fun removeCategoryLauncher_entferntLauncherKategorie() {
        if (!fossApk.exists()) { println("SKIP: /tmp/foss_original.apk fehlt"); return }
        val original = manifestFromApk(fossApk)
        val patched = BinaryXml.removeCategoryLauncher(original)
        val dump = aapt2Dump(patched)
        assertFalse(
            "LAUNCHER-Kategorie muss entfernt sein",
            dump.contains("android.intent.category.LAUNCHER")
        )
    }

    @Test
    fun beidePatches_kombinierbar() {
        if (!fossApk.exists()) { println("SKIP: /tmp/foss_original.apk fehlt"); return }
        val original = manifestFromApk(fossApk)
        var patched = BinaryXml.setExcludeFromRecents(original)
        patched = BinaryXml.removeCategoryLauncher(patched)
        val dump = aapt2Dump(patched)
        assertTrue("excludeFromRecents gesetzt", dump.contains("excludeFromRecents"))
        assertFalse("LAUNCHER-Kategorie entfernt", dump.contains("android.intent.category.LAUNCHER"))
        println("--- kombiniertes Manifest ok ---")
    }
}
