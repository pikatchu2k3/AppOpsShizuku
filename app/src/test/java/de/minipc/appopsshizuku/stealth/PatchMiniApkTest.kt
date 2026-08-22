package de.minipc.appopsshizuku.stealth

import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Diagnose: Wendet BinaryXml.setExcludeFromRecents auf die aapt2-KORREKTE
 * Mini-APK an (die OHNE Patch nachweislich aus Recents verschwindet).
 * Wenn die gepatchte Mini-APK dann NICHT mehr verschwindet, liegt der Bug
 * in unserem AXML-Patch (nicht FOSS-spezifisch).
 */
class PatchMiniApkTest {

    @Test
    fun patchMiniApk() {
        val apk = File("/tmp/foss_original.apk")
        if (!apk.exists()) { println("SKIP: mini5 fehlt"); return }
        val manifest = ZipFile(apk).use { zf ->
            zf.getInputStream(zf.getEntry("AndroidManifest.xml")).use { it.readBytes() }
        }
        val patched = BinaryXml.setExcludeFromRecents(manifest)
        println("original=${manifest.size} patched=${patched.size}")
        File("/tmp/mini3_patched_manifest.axml").writeBytes(patched)

        // Neues APK zusammenbauen: Manifest ersetzen, Rest aus Original
        // WICHTIG: AndroidManifest.xml MUSS STORED (unkomprimiert) sein!
        val out = File("/tmp/foss_patched_v4.apk")
        ZipOutputStream(out.outputStream()).use { zos ->
            ZipFile(apk).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val ze = ZipEntry(e.name)
                    if (e.name == "AndroidManifest.xml" || e.name == "resources.arsc") {
                        ze.method = ZipEntry.STORED
                        val bytes = if (e.name == "AndroidManifest.xml") {
                            patched
                        } else {
                            zf.getInputStream(e).use { it.readBytes() }
                        }
                        ze.size = bytes.size.toLong()
                        ze.compressedSize = bytes.size.toLong()
                        ze.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                        zos.putNextEntry(ze)
                        zos.write(bytes)
                    } else {
                        zos.putNextEntry(ze)
                        zf.getInputStream(e).use { it.copyTo(zos) }
                    }
                    zos.closeEntry()
                }
            }
        }
        println("APK: ${out.absolutePath} (${out.length()} B)")
    }
}
