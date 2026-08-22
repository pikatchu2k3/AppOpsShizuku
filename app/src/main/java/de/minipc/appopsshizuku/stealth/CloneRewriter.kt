package de.minipc.appopsshizuku.stealth

import java.io.File

/**
 * App-Cloning: Ziel-App mit NEUEM Paketnamen patchen (parallele Installation,
 * Original bleibt unangetastet). Arbeitet auf dem baksmali-Smali-Text.
 *
 * Kernprinzip (Qwen-Konzept 08/2026):
 * - Klassen-Referenzen  L<oldPkg>/...  ->  L<newPkg>/...
 *   (nur exakte Paket-Grenzen — "de.baumann.browserx" bleibt unangetastet)
 * - String-Konstanten   "de.baumann.browser" / "de.baumann.browser."  ->  neue Form
 *   (SharedPreferences-Namen, Provider-Authorities, Intent-Packages)
 * - Smali-Dateien VERSCHIEBEN, weil smali die Klasse unter dem neuen Pfad sucht.
 *
 * Idempotent: Wenn der Text bereits newPkg enthält, wird nichts doppelt ersetzt.
 */
object CloneRewriter {

    /** Text-Rewrite auf einer Smali-Datei. Gibt neuen Text zurück (ggf. unverändert). */
    fun rewriteSmaliText(text: String, oldPkg: String, newPkg: String): String {
        if (oldPkg == newPkg) return text
        val newSlash = newPkg.replace('.', '/')
        val oldSlash = oldPkg.replace('.', '/')

        // Idempotenz: Bereits vorhandene newPkg-Vorkommen (aus früherem Lauf)
        // schützen, damit sie NICHT erneut ersetzt werden (kein .stealth.stealth).
        // ZWEI Platzhalter: Punkt-Form (com.example.app.stealth) UND Slash-Form
        // (com/example/app/stealth/) — am Ende werden sie getrennt wiederhergestellt.
        val keepDot = "\u0000CLONE_KEEP_DOT\u0000"
        val keepSlash = "\u0000CLONE_KEEP_SLASH\u0000"
        var out = text
            .replace(newPkg, keepDot)
            .replace("L$newSlash/", "L$keepSlash/")

        // 1) Klassen-Referenzen: Lde/baumann/browser/... -> Lde/baumann/browser/stealth/...
        //    Lookbehind verhindert Teilstring-Matches (browserx, $inner, ...)
        val classRefRe = Regex("""(?<![A-Za-z0-9_\$/])L${Regex.escape(oldSlash)}/""")
        out = classRefRe.replace(out) { "L${newSlash}/" }

        // 2) Punktierte Paketform in Pfaden/Konstanten: de.baumann.browser. -> de.baumann.browser.stealth.
        val dottedPrefixRe = Regex("""(?<![A-Za-z0-9_$.])${Regex.escape("$oldPkg.")}""")
        out = dottedPrefixRe.replace(out) { "$newPkg." }

        // 2b) Prefs-Präfix (Unterstrich): de.baumann.browser_preferences -> de.baumann.browser.stealth_preferences
        val underscoreRe = Regex("""(?<![A-Za-z0-9_$.])${Regex.escape(oldPkg)}_""")
        out = underscoreRe.replace(out) { "${newPkg}_" }

        // 3) Exakter Paketstring in const-string (z.B. "de.baumann.browser" als Prefs-Name)
        val exactStrRe = Regex("""(?<![\w.])${Regex.escape(oldPkg)}(?![\w.])""")
        out = exactStrRe.replace(out) { newPkg }

        // Geschützte newPkg-Vorkommen wiederherstellen (getrennt!)
        return out
            .replace(keepSlash, newSlash)
            .replace(keepDot, newPkg)
    }

    /**
     * Patchen des gesamten Smali-Baums + Verschieben der Verzeichnisse.
     * @return Anzahl geänderter Dateien (für Debug-Log).
     */
    fun rewriteSmaliTree(smaliDir: File, oldPkg: String, newPkg: String): Int {
        if (!smaliDir.isDirectory || oldPkg == newPkg) return 0
        val oldSlash = oldPkg.replace('.', '/')
        val newSlash = newPkg.replace('.', '/')
        var changed = 0

        smaliDir.walkTopDown().filter { it.isFile && it.extension == "smali" }.forEach { f ->
            val before = f.readText(Charsets.UTF_8)
            val after = rewriteSmaliText(before, oldPkg, newPkg)
            if (after != before) {
                f.writeText(after, Charsets.UTF_8)
                changed++
            }
        }

        // Verzeichnisse verschieben: smaliDir/de/baumann/browser/* -> smaliDir/de/baumann/browser/stealth/*
        val src = File(smaliDir, oldSlash)
        if (!src.isDirectory) return changed
        val dst = File(smaliDir, newSlash)
        if (!dst.exists()) dst.mkdirs()

        val toMove = src.walkTopDown().filter { it.isFile && it.extension == "smali" }.toList()
        for (f in toMove) {
            val rel = f.relativeTo(src).path
            val target = File(dst, rel)
            target.parentFile?.mkdirs()
            if (!f.renameTo(target)) {
                f.copyTo(target, overwrite = true); f.delete()
            }
        }
        // Leere Verzeichnisse in src rekursiv aufräumen
        src.walkBottomUp().filter { it.isDirectory }.forEach { d ->
            if (d.listFiles()?.isEmpty() == true) d.delete()
        }
        return changed
    }

    /** Manifest-Binär-XML: Paketnamen tauschen (delegiert an BinaryXml). */
    fun renamePackageInManifest(xmlBytes: ByteArray, oldPkg: String, newPkg: String): ByteArray =
        BinaryXml.renamePackage(xmlBytes, oldPkg, newPkg)
}
