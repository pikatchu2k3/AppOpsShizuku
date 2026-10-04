package de.minipc.appopsshizuku.appops

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Zweite Tracker-Erkennung: Marker-Strings (Klassenpfade) in den `classes*.dex` der Ziel-APK.
 * Eine normale App darf fremde `base.apk` lesen (am Redmi/Android 14 gemessen) - kein Shizuku noetig.
 * Kein Dex-Parsing: die Klassennamen stehen als Klartext in der String-Tabelle, ein Byte-Vergleich
 * auf dem entpackten Dex-Strom genuegt. Reine JVM-Logik, ohne Android-Abhaengigkeit.
 */
object TrackerCodeScan {

    const val DEFAULT_MAX_BYTES = 800L * 1024 * 1024

    /**
     * [found] = IDs der gefundenen Sonden. [complete] = false, wenn etwas nicht durchsucht wurde
     * (Datei nicht lesbar, Obergrenze erreicht, keine Datei) - dann ist "nicht gefunden" schwaecher.
     */
    class Result(
        val found: Set<String>,
        val bytesRead: Long,
        val truncated: Boolean,
        val failedFiles: List<String>,
        val filesScanned: Int
    ) {
        val complete: Boolean get() = !truncated && failedFiles.isEmpty() && filesScanned > 0
    }

    private val DEX_NAME = Regex("classes\\d*\\.dex")

    private fun isDex(entryName: String) = DEX_NAME.matches(entryName.substringAfterLast('/'))

    fun scan(apks: List<File>, probes: List<TrackerProbe>): Set<String> =
        scanWithInfo(apks, probes).found

    /** Je Datei ein `ZipFile`, je `classes*.dex` ein Durchlauf; Fehler pro Datei werden nur protokolliert. */
    fun scanWithInfo(
        apks: List<File>,
        probes: List<TrackerProbe>,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        log: (String) -> Unit = {}
    ): Result {
        val found = mutableSetOf<String>()
        val failed = mutableListOf<String>()
        var scanned = 0
        var truncated = false
        val budget = BudgetCounter(maxBytes)
        for (apk in apks) {
            if (found.size == probes.size) break
            if (budget.remaining <= 0) {
                truncated = true
                break
            }
            try {
                ZipFile(apk).use { zip ->
                    val dexes = zip.entries().asSequence().filter { !it.isDirectory && isDex(it.name) }
                    for (entry in dexes) {
                        if (found.size == probes.size) break
                        if (budget.remaining <= 0) {
                            truncated = true
                            break
                        }
                        val open = probes.filter { it.id !in found }
                        zip.getInputStream(entry).use { found += findProbes(budget.wrap(it), open) }
                    }
                }
                scanned++
            } catch (e: IOException) { // ZipException, FileNotFoundException, EOFException, ...
                log("${apk.name}: nicht lesbar (${e.javaClass.simpleName}: ${e.message})")
                failed += apk.name
            } catch (e: SecurityException) {
                log("${apk.name}: kein Zugriff (${e.message})")
                failed += apk.name
            }
        }
        if (budget.hit) truncated = true
        if (truncated) log("Obergrenze von ${maxBytes / (1024 * 1024)} MB erreicht - Rest nicht durchsucht")
        return Result(found, budget.used, truncated, failed, scanned)
    }

    /** Zaehlt gelesene (entpackte) Bytes ueber alle Dateien und kappt bei [limit] mit EOF. */
    private class BudgetCounter(private val limit: Long) {
        var used = 0L
            private set
        var hit = false
            private set
        val remaining get() = limit - used

        fun wrap(inner: InputStream) = object : InputStream() {
            override fun read(): Int {
                val b = ByteArray(1)
                return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) {
                    hit = true
                    return -1
                }
                val n = inner.read(b, off, minOf(len.toLong(), remaining).toInt())
                if (n > 0) used += n
                return n
            }
        }
    }

    /**
     * Steht [marker] im Strom? Reiner Byte-Vergleich (ISO-8859-1), Namensgrenze siehe [findProbes].
     */
    fun containsMarker(input: InputStream, marker: String, chunkSize: Int = 64 * 1024): Boolean =
        findProbes(input, listOf(TrackerProbe(marker, listOf(marker))), chunkSize).isNotEmpty()

    /**
     * Ein Durchlauf fuer alle [probes] (die Dex-Datei wird nur einmal entpackt): streamt in Bloecken
     * von [chunkSize] Bytes; zwischen zwei Bloecken bleiben die letzten `maxMarkerLen` Bytes
     * stehen, damit kein Marker an der Blockgrenze verloren geht. Gefundene Sonden fallen aus der
     * Suche, bei lauter Treffern endet das Lesen sofort. Rueckgabe: IDs der getroffenen Sonden.
     *
     * Namensgrenze: ein Marker, der nicht auf `/` endet, zaehlt nur, wenn danach KEIN Buchstabe,
     * keine Ziffer und kein `_` folgt (`com/segment/analytics` trifft `.../analytics/X;`,
     * nicht `.../analyticsx`). Davor wird nichts geprueft: umbenannte (relocated) Kopien
     * `com/<app>/com/segment/analytics` sind weiterhin das SDK.
     */
    fun findProbes(input: InputStream, probes: List<TrackerProbe>, chunkSize: Int = 64 * 1024): Set<String> {
        require(chunkSize > 0)
        // Leere Marker werden ignoriert, sonst traefe jeder Strom.
        val all = probes.flatMap { p ->
            p.markers.filter { it.isNotEmpty() }
                .map { Marker(p.id, it.toByteArray(Charsets.ISO_8859_1), !it.endsWith("/")) }
        }
        val hits = mutableSetOf<String>()
        if (all.isEmpty()) return hits
        val idCount = all.mapTo(mutableSetOf()) { it.id }.size
        val maxLen = all.maxOf { it.bytes.size }
        val firstBytes = BooleanArray(256).also { t -> all.forEach { t[it.bytes[0].toInt() and 0xFF] = true } }
        val buf = ByteArray(maxLen + chunkSize)
        var len = 0
        while (hits.size < idCount) {
            val n = input.read(buf, len, buf.size - len)
            val eof = n < 0
            if (!eof) len += n
            // Nicht-EOF: Startpositionen, deren Folgebyte (Namensgrenze) noch fehlen koennte, bleiben
            // im behaltenen Rest und werden im naechsten Durchlauf geprueft. EOF: kein Folgebyte mehr.
            val end = if (eof) len else len - maxLen
            for (i in 0 until end) {
                if (!firstBytes[buf[i].toInt() and 0xFF]) continue
                for (m in all) if (m.id !in hits && m.matchesAt(buf, i, len)) hits += m.id
            }
            if (eof) break
            val keep = minOf(len, maxLen)
            System.arraycopy(buf, len - keep, buf, 0, keep)
            len = keep
        }
        return hits
    }

    private class Marker(val id: String, val bytes: ByteArray, val needsBoundary: Boolean) {
        /** [len] = gueltige Bytes im Puffer; liegt das Folgebyte hinter [len], ist der Strom zu Ende. */
        fun matchesAt(buf: ByteArray, at: Int, len: Int): Boolean {
            val stop = at + bytes.size
            if (stop > len) return false
            for (k in bytes.indices) if (buf[at + k] != bytes[k]) return false
            return !needsBoundary || stop >= len || !isNameByte(buf[stop])
        }
    }

    private fun isNameByte(b: Byte): Boolean {
        val c = b.toInt().toChar()
        return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
    }
}
