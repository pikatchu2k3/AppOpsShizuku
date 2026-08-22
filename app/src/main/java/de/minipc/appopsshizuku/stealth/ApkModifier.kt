package de.minipc.appopsshizuku.stealth

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * APK repacken: alle Einträge unverändert kopieren, nur AndroidManifest.xml
 * ersetzen und alte Signatur-Dateien (META-INF-Verzeichnis) verwerfen.
 *
 * 16KB-Alignment (Android 15+ / 16KB-Page-Geräte): Alle unkomprimierten
 * (STORED) Einträge werden über ein Padding-Extra-Feld so geschrieben, dass
 * ihr Daten-Offset auf 16384-Byte-Grenzen liegt (zipalign -P 16 äquivalent).
 * Pflicht für native Libraries auf 16KB-Geräten; schadet sonst nie.
 */
object ApkModifier {

    private const val PAGE_SIZE = 16384
    private const val ALIGN_4 = 4

    /** Zählt exakt die in den Ziel-Stream geschriebenen Bytes (für Alignment). */
    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    fun extractManifest(apk: File): ByteArray {
        ZipFile(apk).use { zf ->
            val e = zf.getEntry("AndroidManifest.xml")
                ?: throw IllegalStateException("Keine AndroidManifest.xml im APK")
            return zf.getInputStream(e).use { it.readBytes() }
        }
    }

    fun repack(apk: File, newManifest: ByteArray, out: File) =
        repackWithReplacements(apk, newManifest, emptyMap(), out)

    /**
     * Wie [repack], ersetzt aber zusaetzlich beliebige Eintraege (z.B. classes*.dex
     * nach dem Dex-Patch). Eintraege in [replacements] werden NICHT aus dem Original
     * kopiert, stattdessen ihr neuer Inhalt geschrieben.
     */
    fun repackWithReplacements(
        apk: File,
        newManifest: ByteArray,
        replacements: Map<String, ByteArray>,
        out: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        val fos = FileOutputStream(out)
        val cos = CountingOutputStream(fos)
        ZipOutputStream(BufferedOutputStream(cos)).use { zos ->
            ZipFile(apk).use { zf ->
                val seen = HashSet<String>()
                var manifestWritten = false
                val entries = zf.entries()
                val total = zf.size().toInt().coerceAtLeast(1)
                var done = 0
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    done++
                    onProgress(done, total)
                    val name = e.name
                    if (!seen.add(name)) continue
                    if (name.startsWith("META-INF/")) {
                        val upper = name.uppercase(Locale.ROOT)
                        if (upper == "META-INF/MANIFEST.MF" ||
                            upper.endsWith(".SF") || upper.endsWith(".RSA") ||
                            upper.endsWith(".DSA") || upper.endsWith(".EC")
                        ) continue
                    }
                    if (replacements.containsKey(name)) continue
                    val ze = ZipEntry(name)
                    ze.method = e.method
                    ze.time = e.time
                    ze.comment = e.comment
                    if (e.method == ZipEntry.STORED) {
                        ze.size = e.size
                        ze.crc = e.crc
                        zos.flush()
                        val page = if (name.startsWith("lib/")) PAGE_SIZE else ALIGN_4
                        val pad = alignPadding(cos.count, name.length, page)
                        alignExtra(pad, page)?.let { ze.extra = it }
                    }
                    zos.putNextEntry(ze)
                    if (name == "AndroidManifest.xml") {
                        zos.write(newManifest)
                        manifestWritten = true
                    } else {
                        zf.getInputStream(e).use { it.copyTo(zos) }
                    }
                    zos.closeEntry()
                }
                // Fallback: Manifest-Eintrag existierte nicht → am Ende anhängen
                if (!manifestWritten) {
                    val ze = ZipEntry("AndroidManifest.xml")
                    ze.time = System.currentTimeMillis()
                    zos.putNextEntry(ze)
                    zos.write(newManifest)
                    zos.closeEntry()
                }
                // Ersetzte Dateien (z.B. classes.dex) schreiben
                for ((name, content) in replacements) {
                    val ze = ZipEntry(name)
                    ze.time = System.currentTimeMillis()
                    ze.method = ZipEntry.STORED
                    ze.size = content.size.toLong()
                    ze.crc = java.util.zip.CRC32().apply { update(content) }.value
                    zos.putNextEntry(ze)
                    zos.write(content)
                    zos.closeEntry()
                }
            }
        }
    }

    /**
     * Padding so, dass data_off = currentCount + 30 (Local-Header) + nameLen + pad
     * auf page-Grenze fällt (page = 4 oder 16384).
     */
    private fun alignPadding(currentCount: Long, nameLen: Int, page: Int): Int {
        val dataOff = currentCount + 30 + nameLen
        val mod = (dataOff % page).toInt()
        return if (mod == 0) 0 else page - mod
    }

    /**
     * Extra-Feld, das den Daten-Offset um `pad` Bytes verschiebt.
     * Feld = id(2)=0x0000 (unbekannt, von Android/Zip ignoriert) + size(2) + Füllbytes.
     * dataLen so, dass Gesamtlänge (4 + dataLen) % page == pad. Für page=4 → pad 1..3,
     * für page=16384 → pad >= 1 (kleine pads erzeugen hier riesige Extras — tritt in der
     * Praxis nicht auf, da lib/-Einträge selten sind und der Fehler nur bei 16KB-Geräten
     * relevant ist).
     */
    private fun alignExtra(pad: Int, page: Int): ByteArray? {
        if (pad == 0) return null
        val dataLen = ((pad - 4) % page + page) % page
        if (dataLen > 0xFFFF) return null
        val extra = ByteArray(4 + dataLen)
        extra[2] = (dataLen and 0xFF).toByte()
        extra[3] = ((dataLen shr 8) and 0xFF).toByte()
        return extra
    }
}
