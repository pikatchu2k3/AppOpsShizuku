package de.minipc.appopsshizuku.stealth

import de.minipc.appopsshizuku.ErrorLog
import android.content.Context
import android.util.Base64
import android.util.Log
import de.minipc.appopsshizuku.appops.PermissionStrip
import de.minipc.appopsshizuku.appops.StripGroup
import de.minipc.appopsshizuku.appops.TrackerDisable
import de.minipc.appopsshizuku.appops.TrackerGroup
import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShellResult
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Patch-Phasen für den Fortschrittsdialog / die Notification.
 * [StealthPhase] + Prozent (null = unbestimmt, endloser Ring) werden über
 * [StealthStealer.progressListener] an die UI gemeldet.
 */
enum class StealthPhase {
    /** Original-APK extrahieren + als Backup sichern. */
    EXTRACT,
    /** Dex-Dateien mit baksmali/smali neu assemblieren (Spoof/Clone/VPN/Secure). */
    DEX_PATCH,
    /** APK neu packen (Alignment). */
    REPACK,
    /** APK signieren (v1+v2+v3). */
    SIGN,
    /** Signierte APK zum Installer übertragen. */
    TRANSFER,
    /** pm install. */
    INSTALL,
    /** Gepatchte APK mit dem Tracker-Katalog scannen (Vorher/Nachher). */
    PROOF,
    /** Startprobe: App starten, Logcat auf Absturzmerkmale pruefen. */
    PROBE,
    /** Original wiederherstellen (Rollback). */
    RESTORE
}

/**
 * Stealth-Modifikation einer Ziel-App (Qwen-Konzept + AppClonerLite-Pipeline):
 * 1. APK extrahieren (pm path + cp nach /data/local/tmp + Base64-Transfer in App-Cache)
 * 2. Manifest patchen: CATEGORY_LAUNCHER entfernen (Icon aus Launcher, App bleibt startbar)
 * 3. Repack (ApkModifier, 4KB/16KB-Alignment) + Neu-Signatur (ApkSigner, v1+v2+v3)
 * 4. pm install -r via Shizuku (Original-Signatur wird ersetzt -> ggf. erst deinstallieren)
 * 5. Rollback: gesicherte Original-APK zurueckspielen
 *
 * Transfer-Weg: /data/local/tmp ist shell-owned (Shizuku = shell). Der App-Cache ist
 * app-owned -> App liest/schreibt per Base64 (Bytes als String) und macht das
 * signierte APK per setReadable(true,false) fuer die Shell lesbar (cp zurueck).
 *
 * Grosse APKs (Google Maps ~150MB+): Extraktion und Install-Transfer laufen CHUNKED
 * (4MB / 8MB), damit (a) kein 200MB-String den 192MB-App-Heap sprengt (realer
 * OOM-Fall bei smali-Assembly, gleiche Heap-Grenze) und (b) echte Prozente gemeldet
 * werden koennen. Der Single-Call-Base64 war ausserdem nahe am 180s-Timeout.
 */
class StealthStealer(private val backend: ShellBackend, private val appContext: Context) {

    companion object {
        private const val TAG = "StealthStealer"
        private const val TMP_PREFIX = "/data/local/tmp/stealth_"

        /**
         * Pfade landen in `sh -c "..."`: nur Zeichen, die dort nichts bedeuten. Dateinamen kommen vom System
         * (`pm path`), Paketnamen vom Nutzer (Klon) - geprueft wird trotzdem, nicht angenommen.
         */
        private val SAFE_NAME = Regex("^[A-Za-z0-9._-]+$")
        internal fun safeTmpName(name: String): Boolean = name.isNotEmpty() && SAFE_NAME.matches(name)
        private const val BASE64_TIMEOUT = 120L
        /** Extraktion: 4MB-Binaerchunk -> ~5.5MB Base64-String pro IPC-Roundtrip. */
        private const val EXTRACT_CHUNK = 4 * 1024 * 1024
        /** Install-Transfer: 8MB pro Stdin-Write. */
        private const val INSTALL_CHUNK = 8 * 1024 * 1024

        /** Session-ID aus `pm install-create`-Output: "Success: created install session [123]". */
        private val SESSION_RE = Regex("""install session \[(\d+)\]""")

        /** Gemeinsamer Arbeitsordner (Activity + Service nutzen denselben Cache-Pfad). */
        fun workDir(context: android.content.Context, packageName: String): File =
            File(context.cacheDir, "stealth_$packageName")

        /**
         * Dauerhafter Aufbewahrungsort für die Original-APKs (filesDir statt Cache):
         * überlebt ein Cache-Leeren durch Android/die Einstellungen, ohne dass die
         * "Original wiederherstellen"-Funktion stirbt. Cache war der falsche Ort —
         * Android behandelt ihn als wegwerfbar (real: 700 MB Cache nach großem Patch).
         */
        fun preservedDir(context: android.content.Context, packageName: String): File =
            File(context.filesDir, "stealth_$packageName")
    }

    /**
     * Fortschritts-Callback: (Phase, Prozent 0..100 oder null = unbestimmt).
     * Wird von UI/Service gesetzt und in jedem langen Schritt aufgerufen
     * (Extraktion/Transfer chunked mit echten Prozenten, sonst Phasen-Marker).
     */
    var progressListener: ((StealthPhase, Int?) -> Unit)? = null

    /** Konkreter Grund, wenn [buildStealthApkWithSplits] aus einem erklaerbaren Grund null liefert. */
    var lastError: String? = null

    private fun report(phase: StealthPhase, percent: Int?) {
        try {
            progressListener?.invoke(phase, percent)
        } catch (e: Exception) {
            Log.w(TAG, "progressListener fehlgeschlagen", e)
        }
    }

    /**
     * Extrahiert ALLE installierten APKs der Ziel-App nach [targetDir] (im App-Cache).
     * App-Bundles (Google Maps etc.) bestehen aus base.apk + mehreren Split-APKs
     * (`pm path` liefert mehrere `package:`-Zeilen) — ohne die Splits schlägt die
     * Klon-Installation mit `INSTALL_FAILED_MISSING_SPLIT` fehl (realer User-Fall
     * 08/2026: Google Maps klonen). Dateinamen im Zielordner: `original.apk` (base,
     * erster Pfad) + `original_split_<name>.apk` (jede Split, Reihenfolge erhalten).
     * @return Liste der extrahierten Dateien (base zuerst) oder null bei Fehler.
     */
    fun extractApk(packageName: String, targetDir: File): List<File>? {
        val pathResult = backend.exec("pm", "path", packageName)
        if (pathResult.exitCode != 0) return null
        val apkPaths = pathResult.stdout.lines()
            .mapNotNull { it.trim().removePrefix("package:").takeIf(String::isNotEmpty) }
        if (apkPaths.isEmpty()) return null

        // Alte Extraktionen entfernen (App kann sich geaendert haben: frueher
        // ohne Splits extrahiert, jetzt mit — sonst bleiben veraltete Splits
        // liegen und der Wiederverwendungs-Check im Service stimmt nicht).
        targetDir.listFiles { f ->
            f.name == "original.apk" || f.name.startsWith("original_split_")
        }?.forEach { it.delete() }

        // (1) Alle APKs nach /data/local/tmp kopieren (shell-owned) + Größen sammeln
        val tmpFiles = ArrayList<String>()
        val sizes = ArrayList<Long>()
        var totalSize = 0L
        for (apkPath in apkPaths) {
            val fileName = apkPath.substringAfterLast('/')
            if (!safeTmpName(fileName)) {
                tmpFiles.forEach { backend.exec("rm", "-f", it) }
                return null
            }
            val tmp = "$TMP_PREFIX$fileName"
            val copy = backend.exec("cp", apkPath, tmp)
            if (copy.exitCode != 0) {
                tmpFiles.forEach { backend.exec("rm", "-f", it) }
                return null
            }
            val stat = backend.exec("stat", "-c", "%s", tmp)
            val sz = stat.stdout.trim().toLongOrNull() ?: 0L
            tmpFiles.add(tmp)
            sizes.add(sz)
            totalSize += sz
        }

        // (2) Alle chunked in den App-Cache transferieren (Base64-String-Kanal;
        //     echte Gesamt-Prozente über ALLE Dateien)
        targetDir.mkdirs()
        val outFiles = ArrayList<File>(apkPaths.size)
        var writtenTotal = 0L
        var ok = true
        try {
            for (i in tmpFiles.indices) {
                val tmp = tmpFiles[i]
                val originalName = apkPaths[i].substringAfterLast('/')
                // base = erste Zeile aus pm path, Splits danach
                val targetName = if (i == 0) "original.apk" else "original_split_$originalName"
                val target = File(targetDir, targetName)
                FileOutputStream(target).use { fos ->
                    val sz = sizes[i]
                    if (sz > 0) {
                        val chunks = ((sz + EXTRACT_CHUNK - 1) / EXTRACT_CHUNK).toInt()
                        for (c in 0 until chunks) {
                            val b64 = backend.execTimeout(
                                BASE64_TIMEOUT, "sh", "-c",
                                "dd if=$tmp bs=$EXTRACT_CHUNK skip=$c count=1 2>/dev/null | base64"
                            )
                            if (b64.exitCode != 0 || b64.stdout.isBlank()) {
                                ok = false
                                break
                            }
                            val bytes = try {
                                Base64.decode(b64.stdout.trim(), Base64.DEFAULT)
                            } catch (e: Exception) {
                                ok = false
                                break
                            }
                            if (!ok) break
                            fos.write(bytes)
                            writtenTotal += bytes.size.toLong()
                            if (totalSize > 0) {
                                report(StealthPhase.EXTRACT, (writtenTotal * 100 / totalSize).toInt().coerceIn(0, 100))
                            }
                        }
                    } else {
                        // Fallback: komplette Datei (kleine APKs ohne stat)
                        val b64 = backend.execTimeout(BASE64_TIMEOUT, "base64", tmp)
                        if (b64.exitCode != 0 || b64.stdout.isBlank()) {
                            ok = false
                        } else {
                            val bytes = try {
                                Base64.decode(b64.stdout.trim(), Base64.DEFAULT)
                            } catch (e: Exception) {
                                null
                            }
                            if (bytes == null) ok = false else {
                                fos.write(bytes)
                                writtenTotal += bytes.size.toLong()
                            }
                        }
                    }
                }
                if (!ok) break
                outFiles.add(target)
            }
        } catch (e: Exception) {
            ErrorLog.e(TAG, "extractApk: Transfer fehlgeschlagen", e)
            ok = false
        } finally {
            tmpFiles.forEach { backend.exec("rm", "-f", it) }
        }
        if (!ok) return null
        report(StealthPhase.EXTRACT, 100)
        if (outFiles.isEmpty() || outFiles.any { it.length() <= 0 }) return null
        return outFiles
    }

    /** Original-APKs (base + Splits) aus einem Ordner sammeln, null wenn keine gültige Extraktion. */
    private fun collectOriginals(dir: File): List<File>? {
        val base = File(dir, "original.apk")
        if (!base.exists() || base.length() <= 0) return null
        val splits = dir.listFiles { f -> f.name.startsWith("original_split_") }
            ?.sortedBy { it.name }
            ?: emptyList()
        return listOf(base) + splits
    }

    /**
     * Original-APKs am dauerhaften Ort (filesDir) — überleben ein Cache-Leeren.
     * Wird nach erfolgreichem Patch per [relocateOriginals] dorthin verschoben.
     */
    fun preservedOriginals(pkg: String): List<File>? =
        collectOriginals(preservedDir(appContext, pkg))

    /**
     * Originale auflösen: bevorzugt dauerhafter Ort (filesDir, neuer Speicherort),
     * sonst Legacy-Cache (Bestandsinstallationen vor dem filesDir-Umzug). Der Cache
     * wird beim nächsten erfolgreichen Patch automatisch via relocateOriginals migriert.
     */
    fun resolveOriginals(pkg: String, workDir: File): List<File>? =
        preservedOriginals(pkg) ?: collectOriginals(workDir)

    /**
     * Nach erfolgreichem Patch: Originale in den dauerhaften Ordner (filesDir)
     * verschieben und den Arbeitsordner aufräumen. Verhindert, dass der App-Cache
     * durch 2–3 APK-Kopien pro Patch explodiert (real: ~700 MB nach großem Patch:
     * original + repacked + signed blieben liegen). Im Arbeitsordner verbleiben
     * sonst nur Einweg-Artefakte (repacked/signed/Splits) — die werden gelöscht.
     * @return true wenn die Originale sicher im dauerhaften Ordner liegen.
     */
    fun relocateOriginals(workDir: File, pkg: String): Boolean {
        val originals = collectOriginals(workDir) ?: return false
        return try {
            val targetDir = preservedDir(appContext, pkg)
            targetDir.mkdirs()
            // Veraltete Ziel-Dateien (z.B. ältere Splits) ersetzen
            targetDir.listFiles()?.forEach { it.delete() }
            var moved = true
            for (f in originals) {
                val dest = File(targetDir, f.name)
                if (!f.renameTo(dest)) {
                    moved = try {
                        f.copyTo(dest, overwrite = true)
                        f.delete()
                        true
                    } catch (e: Exception) {
                        ErrorLog.e(TAG, "relocateOriginals: Kopie von ${f.name} fehlgeschlagen", e)
                        false
                    }
                }
                if (!moved) break
            }
            if (!moved) return false
            // Rest im Arbeitsordner sind Einweg-Artefakte (repacked/signed/Splits)
            workDir.listFiles()?.forEach { it.delete() }
            workDir.delete()
            true
        } catch (e: Exception) {
            ErrorLog.e(TAG, "relocateOriginals fehlgeschlagen", e)
            false
        }
    }

    /**
     * Patchen (excludeFromRecents + optional Launcher-Kategorie entfernen +
     * optional FLAG_SECURE-Dex-Patch + optional VPN-Guard-Dex-Patch),
     * repacken und signieren.
     * @return die signierte APK (im Cache, fuer Shell lesbar) oder null.
     */
    fun buildStealthApk(
        originalApk: File,
        workDir: File,
        excludeFromRecents: Boolean,
        removeLauncherIcon: Boolean,
        secureSnapshot: Boolean = false,
        requireVpn: Boolean = false,
        vaultCalc: Boolean = false,
        spoofOptions: DeviceSpoofOptions = DeviceSpoofOptions(),
        cloneNewPkg: String? = null,
        cloneLabel: String? = null,
        trackerGroups: Set<TrackerGroup> = emptySet(),
        stripGroups: Set<StripGroup> = emptySet()
    ): File? {
        return try {
            val manifest = ApkModifier.extractManifest(originalApk)
            val anyDexPatch = secureSnapshot || requireVpn || spoofOptions.anyEnabled || cloneNewPkg != null
            // Launcher-Klasse VOR removeCategoryLauncher bestimmen — danach ist der
            // MAIN/LAUNCHER-Filter weg und launcherActivityClass liefert null
            // (realer Bug 08/2026: „Launcher-Icon ausblenden" + VPN-Pflicht/Schwärzen
            // → „Fehler beim Patchen des Manifests").
            val launcherClass = if (anyDexPatch || vaultCalc) {
                BinaryXml.launcherActivityClass(manifest)
            } else null
            var patchedManifest = manifest
            if (excludeFromRecents || vaultCalc) {
                patchedManifest = BinaryXml.setExcludeFromRecents(patchedManifest)
            }
            // Vault: Launcher-Icon zwingend entfernen (App nur noch über
            // VaultCalculator erreichbar). Entweder Icon-Remove oder Vault aktiv.
            if (removeLauncherIcon || vaultCalc) {
                patchedManifest = BinaryXml.removeCategoryLauncher(patchedManifest)
            }
            if (requireVpn) {
                // Der VPN-Guard ruft ConnectivityManager-APIs auf, die
                // ACCESS_NETWORK_STATE brauchen (sonst SecurityException —
                // real getroffen 08/2026 auf FOSS Browser).
                patchedManifest = BinaryXml.addPermission(
                    patchedManifest, "android.permission.ACCESS_NETWORK_STATE"
                )
            }
            // Tracker abschalten: VOR dem Klon-Rename (Katalognamen sind absolut, Paketnamen ohne Belang)
            if (trackerGroups.isNotEmpty()) {
                patchedManifest = TrackerDisable.apply(patchedManifest, trackerGroups)
            }
            // Berechtigungen entfernen: Namen sind absolut (android.permission.*), Klon-Rename egal
            if (stripGroups.isNotEmpty()) {
                patchedManifest = PermissionStrip.apply(patchedManifest, stripGroups)
            }
            // App-Cloning: Paketnamen im Manifest tauschen (package + Komponenten)
            if (cloneNewPkg != null) {
                val oldPkg = BinaryXml.packageName(patchedManifest)
                if (oldPkg != null && oldPkg != cloneNewPkg) {
                    patchedManifest = BinaryXml.renamePackage(patchedManifest, oldPkg, cloneNewPkg)
                }
                // Label auf den sprechenden Namen setzen (z.B. "test" bei test.stealth),
                // sonst heißen Original + Klon gleich (realer User-Fall 08/2026:
                // "zwei Apps die FOSS Browser heißen").
                if (cloneLabel != null && !cloneLabel.isBlank()) {
                    patchedManifest = BinaryXml.setApplicationLabel(patchedManifest, cloneLabel)
                }
            }
            if (patchedManifest.contentEquals(manifest) && !anyDexPatch) {
                return null // nichts gepatcht
            }
            val replacements = HashMap<String, ByteArray>()
            if (anyDexPatch) {
                if (launcherClass == null && !spoofOptions.anyEnabled && cloneNewPkg == null) return null
                report(StealthPhase.DEX_PATCH, 0)
                // Ein Lauf fuer alle Optionen (FLAG_SECURE + VPN-Guard + Spoof + Clone
                // koennen kombiniert werden, ohne dass sich die Laeufe ueberschreiben)
                val patchedDex = DexPatcher.patchAll(
                    originalApk, workDir, launcherClass ?: "",
                    secure = secureSnapshot, vpn = requireVpn, spoof = spoofOptions,
                    cloneNewPkg = cloneNewPkg,
                    onProgress = { done, total ->
                        report(StealthPhase.DEX_PATCH, done * 100 / total)
                    }
                )
                if (patchedDex.isEmpty()) return null
                replacements.putAll(patchedDex)
            }
            val repacked = File(workDir, "repacked.apk")
            report(StealthPhase.REPACK, 0)
            ApkModifier.repackWithReplacements(
                originalApk, patchedManifest, replacements, repacked,
                onProgress = { done, total -> report(StealthPhase.REPACK, done * 100 / total) }
            )
            val signed = File(workDir, "signed.apk")
            report(StealthPhase.SIGN, null)
            ApkSigner.sign(appContext, repacked, signed)
            // Shell (cp) muss das signierte APK lesen koennen
            signed.setReadable(true, false)
            signed
        } catch (e: Exception) {
            // NICHT stumm schlucken — die Ursache muss ins Logcat, sonst sieht der
            // User nur „Fehler beim Patchen des Manifests" ohne Details
            // (realer Diagnose-Fall 08/2026).
            ErrorLog.e(TAG, "buildStealthApk fehlgeschlagen (efr=$excludeFromRecents " +
                "icon=$removeLauncherIcon secure=$secureSnapshot vpn=$requireVpn vault=$vaultCalc)", e)
            null
        }
    }

    /**
     * Wie [buildStealthApk], aber für App-Bundles: patcht/signiert NUR die base-APK,
     * danach werden alle Split-APKs (Ressourcen/native Libs — KEIN Dex/Manifest-
     * Rewrite nötig) repackt und mit DERSELBEN Signatur neu signiert. Beim Cloning
     * wird in den Split-Manifesten zusätzlich der Paketname auf [cloneNewPkg]
     * umgeschrieben — sonst passt der Split nicht zum umbenannten base (Installer
     * matcht Splits über Paketname + Signatur).
     * @return signierte Dateien (base zuerst, dann Splits) oder null bei Fehler.
     */
    fun buildStealthApkWithSplits(
        originalApk: File,
        workDir: File,
        splits: List<File> = emptyList(),
        excludeFromRecents: Boolean,
        removeLauncherIcon: Boolean,
        secureSnapshot: Boolean = false,
        requireVpn: Boolean = false,
        vaultCalc: Boolean = false,
        spoofOptions: DeviceSpoofOptions = DeviceSpoofOptions(),
        cloneNewPkg: String? = null,
        cloneLabel: String? = null,
        trackerGroups: Set<TrackerGroup> = emptySet(),
        stripGroups: Set<StripGroup> = emptySet()
    ): List<File>? {
        lastError = null
        // Klon + Split mit eigenem Code (Dynamic Feature): der Dex-Rename laeuft nur ueber die base-APK,
        // der Split wuerde auf nicht mehr vorhandene Klassen des Originals zeigen -> lieber ablehnen.
        if (cloneNewPkg != null) {
            splits.firstOrNull { CloneRewriter.apkHasDex(it) }?.let {
                lastError = "Klon nicht moeglich: Split ${it.name.removePrefix("original_split_")} enthaelt eigenen Code " +
                    "(Dynamic Feature), der nicht umbenannt werden kann."
                return null
            }
        }
        val signedBase = buildStealthApk(
            originalApk, workDir,
            excludeFromRecents, removeLauncherIcon, secureSnapshot, requireVpn,
            vaultCalc, spoofOptions, cloneNewPkg, cloneLabel, trackerGroups, stripGroups
        ) ?: return null
        val result = ArrayList<File>()
        result.add(signedBase)
        for (i in splits.indices) {
            val split = splits[i]
            try {
                var m = ApkModifier.extractManifest(split)
                if (cloneNewPkg != null) m = CloneRewriter.renameSplitManifest(m, cloneNewPkg)
                val repackedSplit = File(workDir, "repacked_split_$i.apk")
                report(StealthPhase.REPACK, 0)
                ApkModifier.repackWithReplacements(
                    split, m, HashMap(), repackedSplit,
                    onProgress = { done, total -> report(StealthPhase.REPACK, done * 100 / total) }
                )
                // Name = Original-Split-Name (z.B. "split_config.en.apk") — der
                // Installer nutzt ihn als SPLIT_NAME in der Install-Session.
                val splitName = split.name.removePrefix("original_split_")
                val signedSplit = File(workDir, splitName)
                report(StealthPhase.SIGN, null)
                ApkSigner.sign(appContext, repackedSplit, signedSplit)
                signedSplit.setReadable(true, false)
                result.add(signedSplit)
            } catch (e: Exception) {
                ErrorLog.e(TAG, "buildStealthApkWithSplits: Split $i fehlgeschlagen", e)
                return null
            }
        }
        return result
    }

    /**
     * Installiert eine APK aus dem App-Cache via Stdin-Transfer + pm install -r.
     * Der Transfer ist CHUNKED (kein readBytes() der ganzen APK im App-Heap,
     * echte Prozente). Bei Signaturkonflikt (Original mit anderer Signatur
     * installiert) wird bei [allowAutoUninstall] automatisch deinstalliert und
     * neu installiert — der Nutzer muss die App nicht manuell entfernen.
     */
    fun installApk(apk: File, packageName: String, allowAutoUninstall: Boolean = false): InstallResult =
        installApk(listOf(apk), packageName, allowAutoUninstall)

    /**
     * Installiert MEHRERE APKs (base + Splits) über eine PackageInstaller-Session
     * (`pm install-create` + `install-write` + `install-commit`) — für App-Bundles
     * wie Google Maps zwingend. ⚠️ `pm install-multiple` existiert auf modernen
     * Androids NICHT mehr (live verifiziert: "Unknown command: install-multiple");
     * die Session-Commands sind der offizielle Weg. Alle Dateien werden chunked
     * nach /data/local/tmp geschrieben (Gesamt-Prozente), dann in EINER Session
     * übergeben (base zuerst, dann Splits; SPLIT_NAME = "base.apk" bzw.
     * Original-Split-Name).
     */
    fun installApk(apks: List<File>, packageName: String, allowAutoUninstall: Boolean = false): InstallResult {
        if (apks.isEmpty()) return InstallResult(false, "Keine APK-Dateien")
        if (!safeTmpName(packageName)) return InstallResult(false, "Ungültiger Paketname: $packageName")
        val tmpApks = ArrayList<String>(apks.size)
        val splitNames = ArrayList<String>(apks.size)
        val sizes = ArrayList<Long>(apks.size)
        var totalSize = 0L
        for ((i, apk) in apks.withIndex()) {
            val tmp = if (i == 0) "$TMP_PREFIX${packageName}_signed.apk"
            else "$TMP_PREFIX${packageName}_split_$i.apk"
            backend.exec("rm", "-f", tmp)
            tmpApks.add(tmp)
            splitNames.add(if (i == 0) "base.apk" else apk.name)
            sizes.add(apk.length())
            totalSize += apk.length()
        }
        // Binaerdaten chunked ueber Stdin nach /data/local/tmp schreiben (App-Cache
        // ist fuer die Shell nicht lesbar: /data/user/0/<pkg> ist drwx------)
        var written = 0L
        var writeOk = true
        try {
            for ((i, apk) in apks.withIndex()) {
                val tmp = tmpApks[i]
                FileInputStream(apk).use { fis ->
                    val buf = ByteArray(INSTALL_CHUNK)
                    var first = true
                    while (true) {
                        val n = fis.read(buf)
                        if (n < 0) break
                        val payload = if (n == buf.size) buf else buf.copyOf(n)
                        val cmd = if (first) "cat > $tmp" else "cat >> $tmp"
                        first = false
                        val r = backend.execWithStdin(payload, "sh", "-c", cmd)
                        if (r.exitCode != 0) {
                            writeOk = false
                            break
                        }
                        written += n
                        if (totalSize > 0) {
                            report(StealthPhase.TRANSFER, (written * 100 / totalSize).toInt().coerceIn(0, 100))
                        }
                    }
                }
                if (!writeOk) break
            }
        } catch (e: Exception) {
            ErrorLog.e(TAG, "installApk: Transfer fehlgeschlagen", e)
            writeOk = false
        }
        if (!writeOk) {
            tmpApks.forEach { backend.exec("rm", "-f", it) }
            return InstallResult(false, "Transfer fehlgeschlagen")
        }
        report(StealthPhase.INSTALL, null)
        var res = installSession(tmpApks, splitNames, sizes, totalSize, replace = true)
        var out = res.stdout + "\n" + res.stderr

        // INSTALL_FAILED_USER_RESTRICTED ist KEIN Signaturkonflikt, sondern eine
        // Geraete-/MDM-Restriktion (z.B. "USB-Installation" deaktiviert). Ein
        // destruktives Auto-Uninstall wuerde die Original-App ohne Wiederherstellungs-
        // moeglichkeit entfernen -> stattdessen Fehlermeldung an den User.
        if (out.contains("INSTALL_FAILED_USER_RESTRICTED", ignoreCase = true)) {
            tmpApks.forEach { backend.exec("rm", "-f", it) }
            return InstallResult(
                false,
                "Installation vom Geraet blockiert (INSTALL_FAILED_USER_RESTRICTED): " +
                    "'USB-Installation' in den Entwickleroptionen bzw. MDM-Restriktion pruefen."
            )
        }

        // Signaturkonflikt -> Original deinstallieren und neu installieren
        if (allowAutoUninstall && isSignatureConflict(out)) {
            val uninstall = backend.exec("pm", "uninstall", packageName)
            if (uninstall.exitCode == 0) {
                res = installSession(tmpApks, splitNames, sizes, totalSize, replace = false)
                out = res.stdout + "\n" + res.stderr
            } else {
                tmpApks.forEach { backend.exec("rm", "-f", it) }
                return InstallResult(
                    false,
                    "Deinstallation des Originals fehlgeschlagen: ${uninstall.stdout.take(80)}"
                )
            }
        }
        tmpApks.forEach { backend.exec("rm", "-f", it) }

        if (res.exitCode != 0 || out.contains("Failure", ignoreCase = true)) {
            val msg = when {
                isSignatureConflict(out) ->
                    "Signaturkonflikt: Die App ist mit einer anderen Signatur installiert."
                out.contains("not installed", ignoreCase = true) ||
                    out.contains("does not exist", ignoreCase = true) ->
                    "App ist nicht installiert."
                else -> "Install fehlgeschlagen: ${out.take(120)}"
            }
            return InstallResult(false, msg)
        }
        report(StealthPhase.INSTALL, 100)
        return InstallResult(true, "Success")
    }

    /**
     * Fuehrt eine PackageInstaller-Session aus: create -> write (alle APKs) -> commit.
     * @return Ergebnis des install-commit (bzw. des fehlgeschlagenen Schritts).
     */
    private fun installSession(
        tmpApks: List<String>,
        splitNames: List<String>,
        sizes: List<Long>,
        totalSize: Long,
        replace: Boolean
    ): ShellResult {
        val createArgs = mutableListOf("pm", "install-create")
        if (replace) createArgs.add("-r")
        createArgs.add("-S")
        createArgs.add(totalSize.toString())
        val created = backend.execTimeout(60L, *createArgs.toTypedArray())
        if (created.exitCode != 0) return created
        val sessionId = SESSION_RE.find(created.stdout)?.groupValues?.get(1)
            ?: return ShellResult(
                created.exitCode, created.stdout,
                created.stderr + "\nSession-ID nicht gefunden"
            )
        for (i in tmpApks.indices) {
            val w = backend.execTimeout(
                120L, "pm", "install-write",
                "-S", sizes[i].toString(), sessionId, splitNames[i], tmpApks[i]
            )
            if (w.exitCode != 0) {
                backend.exec("pm", "install-abandon", sessionId)
                return w
            }
        }
        return backend.execTimeout(180L, "pm", "install-commit", sessionId)
    }

    private fun isSignatureConflict(out: String): Boolean =
        out.contains("UPDATE_INCOMPATIBLE", ignoreCase = true) ||
            out.contains("signatures do not match", ignoreCase = true)

    /** Original-APK(s) zurueckspielen (Rollback) — deinstalliert die Stealth-Version automatisch. */
    fun restore(originalApk: File, packageName: String): InstallResult =
        restore(listOf(originalApk), packageName)

    /** Rollback für App-Bundles: base + Splits zurueckspielen. */
    fun restore(originalApks: List<File>, packageName: String): InstallResult {
        report(StealthPhase.RESTORE, null)
        return installApk(originalApks, packageName, allowAutoUninstall = true)
    }

    data class InstallResult(val success: Boolean, val message: String)
}
