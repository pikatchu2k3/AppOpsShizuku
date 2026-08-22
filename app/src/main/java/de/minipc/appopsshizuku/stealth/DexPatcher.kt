package de.minipc.appopsshizuku.stealth

import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * Schwae... (FLAG_SECURE) per Dex-Patch in die Ziel-App einbauen (Qwen-Konzept):
 * 1. classes*.dex mit baksmali dekodieren
 * 2. Die Smali-Datei der Launcher-Activity patchen: In der ersten
 *    Lifecycle-Methode (onCreate/onStart/onResume) wird
 *    `getWindow().addFlags(0x2000)` (FLAG_SECURE) eingefuegt.
 *    -> Der Recents-Snapshot der App ist danach SCHWARZ (wie Banking-Apps).
 * 3. Mit smali neu assemblieren -> neues classes.dex
 *
 * Dependencies: org.smali:baksmali:2.5.2 + org.smali:smali:2.5.2 (jcommander-API
 * via Reflection gesetzt, da die Felder privat sind).
 */
object DexPatcher {

    private const val TAG = "DexPatcher"
    const val FLAG_SECURE = 0x2000
    private const val API_LEVEL = 35
    /**
     * Parallelitaet fuer baksmali/smali — ADAPTIV an den verfuegbaren Java-Heap
     * (Qwen-Analyse 08/2026): jobs>1 nur, wenn maxMemory() deutlich ueber 192MB
     * liegt, sonst OOM-Risiko (realer Fall 08/2026 im 192MB-Heap). Zusaetzlich
     * faengt assemble/disassemble OutOfMemoryError ab und faellt auf jobs=1
     * zurueck (doppeltes Sicherheitsnetz).
     */
    private val smaliJobs: Int
        get() {
            val maxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            return when {
                maxMb <= 220 -> 1
                maxMb <= 384 -> 1
                maxMb <= 512 -> 2
                else -> 2
            }
        }

    /**
     * Patchen des Launcher-Activity-Dex mit FLAG_SECURE- und/oder VPN-Guard
     * und/oder Device-Spoofing.
     * Ein EINZIGER baksmali/smali-Lauf, damit alle Patches auf demselben
     * Smali landen (zwei getrennte Laeufe wuerden sich gegenseitig
     * ueberschreiben — gleicher classes.dex-Key).
     * @return Map "classes*.dex" -> neue Dex-Bytes (nur gepatchte Dexe).
     *         Leer, wenn nichts gepatcht werden konnte.
     */
    fun patchAll(apk: File, workDir: File, launcherClass: String, secure: Boolean, vpn: Boolean, spoof: DeviceSpoofOptions = DeviceSpoofOptions(), cloneNewPkg: String? = null, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Map<String, ByteArray> {
        val smaliPath = launcherClass.replace('.', '/') + ".smali"
        val result = LinkedHashMap<String, ByteArray>()
        try {
            ZipFile(apk).use { zf ->
                val dexEntries = zf.entries().asSequence()
                    .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                    .toList()
                for ((dexIndex, entry) in dexEntries.withIndex()) {
                    onProgress(dexIndex + 1, dexEntries.size)
                    val work = File(workDir, "dexwork_${entry.name}")
                    work.mkdirs()
                    val dexFile = File(workDir, "raw_${entry.name}")
                    try {
                        zf.getInputStream(entry).use { input ->
                            dexFile.outputStream().use { input.copyTo(it) }
                        }
                        debug("DexPatcher: dex ${entry.name} (${dexFile.length()} B), suche $smaliPath")
                        if (!disassemble(dexFile, work)) {
                            debug("DexPatcher: baksmali FEHLGESCHLAGEN fuer ${entry.name}")
                            continue
                        }
                        val target = File(work, smaliPath)
                        val spoofFile = File(work, "de/minipc/spoof/DeviceSpoof.smali")
                        if (!target.exists()) {
                            debug("DexPatcher: $smaliPath nicht in ${entry.name} gefunden (smali-Dateien: ${work.walkTopDown().count { it.isFile }} )")
                            // Spoof-/Clone-Patches wirken auf ALLE Smali-Dateien — auch ohne
                            // Launcher-Activity in diesem Dex weiterverarbeiten.
                            if (!spoof.anyEnabled && cloneNewPkg == null) continue
                        }
                        debug("DexPatcher: Smali-Datei gefunden, patche...")
                        if (vpn) {
                            // VpnGuard-Helferklasse injizieren (einmal pro Lauf)
                            val guardFile = File(work, "de/minipc/vpnguard/VpnGuard.smali")
                            guardFile.parentFile?.mkdirs()
                            guardFile.writeText(VPN_GUARD_SMALI)
                            if (target.exists() && !patchSmaliVpnGuard(target)) {
                                debug("DexPatcher: patchSmaliVpnGuard FEHLGESCHLAGEN")
                                continue
                            }
                        }
                        if (secure && target.exists() && !patchSmali(target)) {
                            debug("DexPatcher: patchSmali (FLAG_SECURE) FEHLGESCHLAGEN (keine Lifecycle-Methode?)")
                            continue
                        }
                        if (spoof.anyEnabled) {
                            // DeviceSpoof-Helferklasse injizieren (einmal pro Lauf)
                            spoofFile.parentFile?.mkdirs()
                            spoofFile.writeText(renderDeviceSpoofSmali(spoof))
                            // Call-Site-Rewriting ueber ALLE Smali-Dateien (Build-Zugriffe
                            // koennen ueberall in der App stehen, nicht nur in der Launcher-Activity)
                            var patchedAny = false
                            work.walkTopDown()
                                .filter { it.isFile && it.extension == "smali" && it != spoofFile }
                                .forEach { file ->
                                    val before = file.readText()
                                    val after = DeviceSpoofPatch.patchSmali(before, spoof)
                                    if (after != before) {
                                        file.writeText(after)
                                        patchedAny = true
                                    }
                                }
                            debug("DexPatcher: DeviceSpoof-Patch angewandt (veraendert=$patchedAny)")
                            if (!patchedAny && !target.exists()) {
                                debug("DexPatcher: KEINE Smali-Datei gefunden — Spoof-Patch uebersprungen")
                                continue
                            }
                        }
                        if (cloneNewPkg != null) {
                            // App-Cloning: Paketnamen in ALLEN Smali-Dateien tauschen
                            // (Klassen-Referenzen + Strings) und Verzeichnisse verschieben.
                            val oldPkg = try {
                                val mf = ZipFile(apk).getEntry("AndroidManifest.xml")
                                    ?.let { ZipFile(apk).getInputStream(it).use { i -> i.readBytes() } }
                                if (mf != null) BinaryXml.packageName(mf) else null
                            } catch (e: Exception) { null }
                            if (oldPkg != null && oldPkg != cloneNewPkg) {
                                val changed = CloneRewriter.rewriteSmaliTree(work, oldPkg, cloneNewPkg)
                                debug("DexPatcher: Clone-Rewrite $oldPkg -> $cloneNewPkg ($changed Dateien)")
                            } else {
                                debug("DexPatcher: Clone-Rewrite uebersprungen (oldPkg=$oldPkg)")
                            }
                        }
                        val outDex = File(workDir, "new_${entry.name}")
                        if (!assemble(work, outDex)) {
                            debug("DexPatcher: smali-Assembly FEHLGESCHLAGEN")
                            continue
                        }
                        debug("DexPatcher: PATCH OK (secure=$secure vpn=$vpn spoof=${spoof.anyEnabled}), neues Dex ${outDex.length()} B")
                        result[entry.name] = outDex.readBytes()
                        outDex.delete()
                    } finally {
                        work.deleteRecursively()
                        dexFile.delete()
                    }
                }
            }
        } catch (e: Exception) {
            return emptyMap()
        }
        return result
    }

    /** Ersetzt die @@PLATZHALTER@@ im DeviceSpoof-Smali-Template durch die Fake-Werte. */
    private fun renderDeviceSpoofSmali(options: DeviceSpoofOptions): String {
        val model = options.fakeModel.ifBlank { android.os.Build.MODEL }.smaliEscape()
        val manufacturer = options.fakeManufacturer.ifBlank { android.os.Build.MANUFACTURER }.smaliEscape()
        val brand = options.fakeBrand.ifBlank { android.os.Build.BRAND }.smaliEscape()
        val device = options.fakeDevice.ifBlank { android.os.Build.DEVICE }.smaliEscape()
        // PRODUCT hat ein eigenes fakeProduct-Feld; ist es (wie bei Alt-Optionen)
        // leer, wird sinnvollerweise aus fakeDevice abgeleitet statt blind
        // fakeDevice zu zuweisen (alter Copy-Paste-Bug: PRODUCT == DEVICE).
        val product = options.fakeProduct
            .ifBlank { options.fakeDevice }
            .ifBlank { android.os.Build.PRODUCT }
            .smaliEscape()
        val fingerprint = options.fakeFingerprint.ifBlank { android.os.Build.FINGERPRINT }.smaliEscape()
        val hardware = android.os.Build.HARDWARE.smaliEscape()
        val androidId = options.fakeAndroidId
            .ifBlank { "deadbeef-dead-beef-dead-beefdeadbeef" }
            .smaliEscape()
        val imei = options.fakeImei
            .ifBlank { "000000000000000" }
            .smaliEscape()
        val imsi = (options.fakeImsi ?: options.fakeImei)
            .ifBlank { "000000000000000" }
            .smaliEscape()
        return DEVICE_SPOOF_SMALI
            .replace("@@MODEL@@", model)
            .replace("@@MANUFACTURER@@", manufacturer)
            .replace("@@BRAND@@", brand)
            .replace("@@DEVICE@@", device)
            .replace("@@PRODUCT@@", product)
            .replace("@@FINGERPRINT@@", fingerprint)
            .replace("@@HARDWARE@@", hardware)
            .replace("@@ANDROID_ID@@", androidId)
            .replace("@@IMEI@@", imei)
            .replace("@@IMSI@@", imsi)
    }

    /** Escaped einen String fuer die Verwendung in einer Smali-const-string-Anweisung. */
    private fun String.smaliEscape(): String {
        val sb = StringBuilder(length)
        for (c in this) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20 || c.code > 0x7E) {
                        sb.append("\\u").append(String.format("%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * FLAG_SECURE-Dex-Patch (Schwaerz-Modus). Delegiert an [patchAll].
     */
    fun patchForSecure(apk: File, workDir: File, launcherClass: String): Map<String, ByteArray> =
        patchAll(apk, workDir, launcherClass, secure = true, vpn = false)

    /**
     * VPN-Guard-Dex-Patch (VPN-Pflicht). Delegiert an [patchAll].
     */
    fun patchForVpnGuard(apk: File, workDir: File, launcherClass: String): Map<String, ByteArray> =
        patchAll(apk, workDir, launcherClass, secure = false, vpn = true)

    /**
     * Injizierte Helferklasse: prueft in der Activity, ob ein VPN-Netz aktiv ist
     * (TRANSPORT_VPN=4 via public API getActiveNetwork/getNetworkCapabilities/
     * hasTransport — bewusst NICHT NET_CAPABILITY_VPN=@hide und NICHT
     * getNetworkForUid=@hide, beide waeren hidden-API-Probleme) und ruft
     * finish() auf, wenn keins aktiv ist. Kein eigener VpnService noetig —
     * der Check greift bei JEDEM VPN (WireGuard, OpenVPN, Mullvad, ...).
     */
    private const val VPN_GUARD_SMALI = """.class public Lde/minipc/vpnguard/VpnGuard;
.super Ljava/lang/Object;

.method public static guard(Landroid/app/Activity;)V
    .registers 8

    # WICHTIG: static Methode, 1 Parameter -> p0 = v(R-1) = v7 bei .registers 8!
    # (smali legt Parameter in die HOECHSTEN Register). Lokal nutzbar: v0..v5.
    # .registers 6 waere p0=v5 -> move-result v5 wuerde p0 ueberschreiben
    # (VerifyError "tried to get class from non-reference register" — real
    # getroffen 08/2026, gleiche Falle wie beim FLAG_SECURE-Patch).
    const/4 v0, 0x0

    const-string v1, "connectivity"
    invoke-virtual {p0, v1}, Landroid/app/Activity;->getSystemService(Ljava/lang/String;)Ljava/lang/Object;
    move-result-object v1
    if-eqz v1, :no_vpn

    check-cast v1, Landroid/net/ConnectivityManager;

    # aktives Netz holen (public seit API 23)
    invoke-virtual {v1}, Landroid/net/ConnectivityManager;->getActiveNetwork()Landroid/net/Network;
    move-result-object v2
    if-eqz v2, :no_vpn

    # Capabilities (public seit API 21)
    invoke-virtual {v1, v2}, Landroid/net/ConnectivityManager;->getNetworkCapabilities(Landroid/net/Network;)Landroid/net/NetworkCapabilities;
    move-result-object v3
    if-eqz v3, :no_vpn

    # TRANSPORT_VPN = 4 (public Konstante, hartkodiert im Smali)
    const/4 v4, 0x4
    invoke-virtual {v3, v4}, Landroid/net/NetworkCapabilities;->hasTransport(I)Z
    move-result v5
    if-nez v5, :vpn_ok

    :no_vpn
    invoke-virtual {p0}, Landroid/app/Activity;->finish()V

    :vpn_ok
    return-void
.end method
"""

    /**
     * Injizierte Helferklasse fuer app-spezifisches Device-Spoofing:
     * liefert Fake-Werte fuer Build-Felder, Android-ID und IMEI/IMSI.
     * Die @@PLATZHALTER@@ werden beim Repacken durch renderDeviceSpoofSmali
     * mit den konfigurierten Fake-Werten (oder Original-Build-Werten) ersetzt.
     */
    private const val DEVICE_SPOOF_SMALI = """.class public Lde/minipc/spoof/DeviceSpoof;
.super Ljava/lang/Object;

.field public static MODEL:Ljava/lang/String;
.field public static MANUFACTURER:Ljava/lang/String;
.field public static BRAND:Ljava/lang/String;
.field public static DEVICE:Ljava/lang/String;
.field public static PRODUCT:Ljava/lang/String;
.field public static FINGERPRINT:Ljava/lang/String;
.field public static HARDWARE:Ljava/lang/String;

.field public static ANDROID_ID:Ljava/lang/String;
.field public static IMEI:Ljava/lang/String;
.field public static IMSI:Ljava/lang/String;

.method static constructor <clinit>()V
    .locals 1

    const-string v0, "@@MODEL@@"
    sput-object v0, Lde/minipc/spoof/DeviceSpoof;->MODEL:Ljava/lang/String;

    const-string v0, "@@MANUFACTURER@@"
    sput-object v0, Lde/minipc/spoof/DeviceSpoof;->MANUFACTURER:Ljava/lang/String;

    const-string v0, "@@BRAND@@"
    sput-object v0, Lde/minipc/spoof/DeviceSpoof;->BRAND:Ljava/lang/String;

    const-string v0, "@@DEVICE@@"
    sput-object v0, Lde/minipc/spoof/DeviceSpoof;->DEVICE:Ljava/lang/String;

    const-string v0, "@@PRODUCT@@"
    sput-object v0, Lde/minipc/spoof/DeviceSpoof;->PRODUCT:Ljava/lang/String;

    const-string v0, "@@FINGERPRINT@@"
    sput-object v0, Lde/minipc/spoof/DeviceSpoof;->FINGERPRINT:Ljava/lang/String;

    const-string v0, "@@HARDWARE@@"
    sput-object v0, Lde/minipc/spoof/DeviceSpoof;->HARDWARE:Ljava/lang/String;

    const-string v0, "@@ANDROID_ID@@"
    sput-object v0, Lde/minipc/spoof/DeviceSpoof;->ANDROID_ID:Ljava/lang/String;

    const-string v0, "@@IMEI@@"
    sput-object v0, Lde/minipc/spoof/DeviceSpoof;->IMEI:Ljava/lang/String;

    const-string v0, "@@IMSI@@"
    sput-object v0, Lde/minipc/spoof/DeviceSpoof;->IMSI:Ljava/lang/String;

    return-void
.end method

.method public static getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;
    .locals 2

    const-string v0, "android_id"
    invoke-static {p1, v0}, Landroid/text/TextUtils;->equals(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Z
    move-result v0

    if-eqz v0, :cond_original

    sget-object v1, Lde/minipc/spoof/DeviceSpoof;->ANDROID_ID:Ljava/lang/String;
    if-eqz v1, :cond_original
    invoke-virtual {v1}, Ljava/lang/String;->length()I
    move-result v0
    if-eqz v0, :cond_original

    return-object v1

    :cond_original
    invoke-static {p0, p1}, Landroid/provider/Settings${'$'}Secure;->getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0

    return-object v0
.end method

.method public static telephony(Landroid/telephony/TelephonyManager;)Ljava/lang/String;
    .locals 2

    sget-object v0, Lde/minipc/spoof/DeviceSpoof;->IMEI:Ljava/lang/String;
    if-eqz v0, :cond_empty
    invoke-virtual {v0}, Ljava/lang/String;->length()I
    move-result v1
    if-nez v1, :cond_empty

    return-object v0

    :cond_empty
    const-string v0, "000000000000000"
    return-object v0
.end method

.method public static telephonySlot(Landroid/telephony/TelephonyManager;I)Ljava/lang/String;
    .locals 2

    sget-object v0, Lde/minipc/spoof/DeviceSpoof;->IMEI:Ljava/lang/String;
    if-eqz v0, :cond_empty
    invoke-virtual {v0}, Ljava/lang/String;->length()I
    move-result v1
    if-nez v1, :cond_empty

    return-object v0

    :cond_empty
    const-string v0, "000000000000000"
    return-object v0
.end method

.method public static subscriberId(Landroid/telephony/TelephonyManager;)Ljava/lang/String;
    .locals 2

    sget-object v0, Lde/minipc/spoof/DeviceSpoof;->IMSI:Ljava/lang/String;
    if-eqz v0, :cond_empty
    invoke-virtual {v0}, Ljava/lang/String;->length()I
    move-result v1
    if-nez v1, :cond_empty

    return-object v0

    :cond_empty
    const-string v0, "000000000000000"
    return-object v0
.end method

.method public static setModel(Ljava/lang/String;)V
    .locals 0
    sput-object p0, Lde/minipc/spoof/DeviceSpoof;->MODEL:Ljava/lang/String;
    return-void
.end method

.method public static setManufacturer(Ljava/lang/String;)V
    .locals 0
    sput-object p0, Lde/minipc/spoof/DeviceSpoof;->MANUFACTURER:Ljava/lang/String;
    return-void
.end method

.method public static setBrand(Ljava/lang/String;)V
    .locals 0
    sput-object p0, Lde/minipc/spoof/DeviceSpoof;->BRAND:Ljava/lang/String;
    return-void
.end method

.method public static setDevice(Ljava/lang/String;)V
    .locals 0
    sput-object p0, Lde/minipc/spoof/DeviceSpoof;->DEVICE:Ljava/lang/String;
    return-void
.end method

.method public static setProduct(Ljava/lang/String;)V
    .locals 0
    sput-object p0, Lde/minipc/spoof/DeviceSpoof;->PRODUCT:Ljava/lang/String;
    return-void
.end method

.method public static setFingerprint(Ljava/lang/String;)V
    .locals 0
    sput-object p0, Lde/minipc/spoof/DeviceSpoof;->FINGERPRINT:Ljava/lang/String;
    return-void
.end method

.method public static setHardware(Ljava/lang/String;)V
    .locals 0
    sput-object p0, Lde/minipc/spoof/DeviceSpoof;->HARDWARE:Ljava/lang/String;
    return-void
.end method

.method public static setAndroidId(Ljava/lang/String;)V
    .locals 0
    sput-object p0, Lde/minipc/spoof/DeviceSpoof;->ANDROID_ID:Ljava/lang/String;
    return-void
.end method

.method public static setImei(Ljava/lang/String;)V
    .locals 0
    sput-object p0, Lde/minipc/spoof/DeviceSpoof;->IMEI:Ljava/lang/String;
    return-void
.end method

.method public static setImsi(Ljava/lang/String;)V
    .locals 0
    sput-object p0, Lde/minipc/spoof/DeviceSpoof;->IMSI:Ljava/lang/String;
    return-void
.end method
"""

    /**
     * Fuegt den VpnGuard-Aufruf direkt NACH super.onCreate() ein.
     * Braucht KEINE Register-Erhoehung (nur p0 wird benutzt).
     */
    internal fun patchSmaliVpnGuard(smaliFile: File): Boolean {
        val lines = smaliFile.readLines().toMutableList()
        var methodIdx = -1
        for (i in lines.indices) {
            val t = lines[i].trimStart()
            if (t.startsWith(".method ")) {
                val mname = t.removePrefix(".method")
                    .substringBefore('(').trim().substringAfterLast(' ')
                if (mname == "onCreate" || mname == "onStart" || mname == "onResume") {
                    methodIdx = i
                    break
                }
            }
        }
        if (methodIdx < 0) return false

        // Einfuegepunkt: erste invoke-super Zeile NACH dem Methoden-Header
        var insertAt = -1
        for (i in methodIdx until lines.size) {
            val t = lines[i].trimStart()
            if (t.startsWith(".end method")) break
            if (t.startsWith("invoke-super")) { insertAt = i + 1; break }
        }
        if (insertAt < 0) return false

        val guardCall = "    invoke-static {p0}, Lde/minipc/vpnguard/VpnGuard;->guard(Landroid/app/Activity;)V"
        // Nur einmael einfuegen (kein doppelter Guard bei erneutem Patch)
        if (lines.any { it.contains("VpnGuard;->guard") }) return true
        lines.add(insertAt, guardCall)
        smaliFile.writeText(lines.joinToString("\n"))
        return true
    }

    // ------------------------------------------------------------------
    // baksmali / smali (Reflection auf die jcommander-Commands)
    // ------------------------------------------------------------------

    private fun disassemble(dexFile: File, outDir: File): Boolean {
        val jobs = smaliJobs
        if (jobs > 1) {
            return try {
                disassembleInternal(dexFile, outDir, jobs)
            } catch (e: OutOfMemoryError) {
                debug("baksmali OOM bei jobs=$jobs, Fallback auf jobs=1")
                try {
                    disassembleInternal(dexFile, outDir, 1)
                } catch (e2: Exception) {
                    Log.e(TAG, "baksmali fehlgeschlagen: disassemble ${dexFile.absolutePath} (jobs=1, exit=-1)", e2)
                    false
                }
            } catch (e: Exception) {
                val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
                Log.e(TAG, "baksmali fehlgeschlagen: disassemble ${dexFile.absolutePath} (jobs=$jobs, exit=-1): ${cause?.javaClass?.simpleName}: ${cause?.message}", cause ?: e)
                false
            }
        }
        return try {
            disassembleInternal(dexFile, outDir, 1)
        } catch (e: OutOfMemoryError) {
            debug("baksmali OOM bei jobs=1")
            false
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
            Log.e(TAG, "baksmali fehlgeschlagen: disassemble ${dexFile.absolutePath} (jobs=1, exit=-1): ${cause?.javaClass?.simpleName}: ${cause?.message}", cause ?: e)
            false
        }
    }

    private fun disassembleInternal(dexFile: File, outDir: File, jobs: Int): Boolean {
        val cmd = Class.forName("org.jf.baksmali.DisassembleCommand")
            .getConstructor(List::class.java).newInstance(emptyList<Any>())
        setField(cmd, "inputList", listOf(dexFile.absolutePath))
        setField(cmd, "outputDir", outDir.absolutePath)
        setField(cmd, "apiLevel", API_LEVEL)
        setField(cmd, "jobs", jobs)
        val exit = invokeRun(cmd)
        if (exit != 0) {
            Log.e(TAG, "baksmali fehlgeschlagen: disassemble ${dexFile.absolutePath} -> ${outDir.absolutePath} (jobs=$jobs, exit=$exit)")
            return false
        }
        // Die Library schreibt Fehler nur auf stderr und run() kann trotzdem
        // "erfolgreich" enden -> Output-Artefakt verifizieren.
        if (outDir.walkTopDown().none { it.isFile }) {
            Log.e(TAG, "baksmali lieferte kein Ergebnis: disassemble ${dexFile.absolutePath} -> ${outDir.absolutePath} (jobs=$jobs, exit=$exit, keine Smali-Dateien erzeugt)")
            return false
        }
        return true
    }

    private fun assemble(smaliDir: File, outDex: File): Boolean {
        val jobs = smaliJobs
        if (jobs > 1) {
            return try {
                assembleInternal(smaliDir, outDex, jobs)
            } catch (e: OutOfMemoryError) {
                debug("smali OOM bei jobs=$jobs, Fallback auf jobs=1")
                try {
                    assembleInternal(smaliDir, outDex, 1)
                } catch (e2: OutOfMemoryError) {
                    debug("smali OOM auch bei jobs=1")
                    false
                } catch (e2: Exception) {
                    Log.e(TAG, "smali fehlgeschlagen: assemble ${smaliDir.absolutePath} (jobs=1, exit=-1)", e2)
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "smali fehlgeschlagen: assemble ${smaliDir.absolutePath} (jobs=$jobs, exit=-1): ${e.javaClass.simpleName}: ${e.message}", e)
                false
            }
        }
        return try {
            assembleInternal(smaliDir, outDex, 1)
        } catch (e: OutOfMemoryError) {
            debug("smali OOM bei jobs=1")
            false
        } catch (e: Exception) {
            Log.e(TAG, "smali fehlgeschlagen: assemble ${smaliDir.absolutePath} (jobs=1, exit=-1): ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    private fun assembleInternal(smaliDir: File, outDex: File, jobs: Int): Boolean {
        val cmd = Class.forName("org.jf.smali.AssembleCommand")
            .getConstructor(List::class.java).newInstance(emptyList<Any>())
        setField(cmd, "input", listOf(smaliDir.absolutePath))
        setField(cmd, "output", outDex.absolutePath)
        setField(cmd, "apiLevel", API_LEVEL)
        setField(cmd, "jobs", jobs)
        val exit = invokeRun(cmd)
        if (exit != 0) {
            Log.e(TAG, "smali fehlgeschlagen: assemble ${smaliDir.absolutePath} -> ${outDex.absolutePath} (jobs=$jobs, exit=$exit)")
            return false
        }
        if (!outDex.exists() || outDex.length() <= 0L) {
            Log.e(TAG, "smali lieferte kein Ergebnis: assemble ${smaliDir.absolutePath} -> ${outDex.absolutePath} (jobs=$jobs, exit=$exit, Dex fehlt/leer)")
            return false
        }
        return true
    }

    /**
     * Ruft run() per Reflection auf und wertet den Exit-Code aus:
     * void/null-Rueckgabe = 0, numerische Rueckgabe = Exit-Code.
     * Exceptions werden ent-wrapped weitergeworfen (InvocationTargetException ->
     * Cause), damit die OOM-Fallback-Wrapper oben greifen.
     */
    private fun invokeRun(cmd: Any): Int =
        try {
            when (val r = cmd.javaClass.getMethod("run").invoke(cmd)) {
                null -> 0
                is Number -> r.toInt()
                else -> 0
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }

    private fun setField(obj: Any, name: String, value: Any) {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                f.set(obj, value)
                return
            } catch (e: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException(name)
    }

    private fun debug(msg: String) {
        // System.out laeuft in der App ins Logcat, im JVM-Test auf stdout
        println("DEXPATCH: $msg")
    }

    // ------------------------------------------------------------------
    // Smali-Patch
    // ------------------------------------------------------------------

    /**
     * Fuegt FLAG_SECURE in die erste Lifecycle-Methode der Klasse ein.
     * Register-Sicher: .locals/.registers wird um 2 erhoeht, die NEUEN
     * (hoechsten) lokalen Register werden verwendet -> kein Konflikt mit
     * bestehendem Code.
     */
    internal fun patchSmali(smaliFile: File): Boolean {
        val lines = smaliFile.readLines().toMutableList()
        var methodIdx = -1
        var methodName = ""
        for (i in lines.indices) {
            val t = lines[i].trimStart()
            if (t.startsWith(".method ")) {
                // Exakter Methodenname statt Substring-Match: "lambda$onCreate$0",
                // "onCreateOptionsMenu", "onCreateView" etc. sind KEINE Lifecycle-
                // Callbacks (p0 = View/Menu, kein Activity) -> dort eingefuegtes
                // getWindow() wuerde beim Start einen VerifyError werfen (real
                // getroffen 08/2026: Verifier rejected ... lambda$onCreate$0).
                val mname = t.removePrefix(".method")
                    .substringBefore('(').trim().substringAfterLast(' ')
                if (mname == "onCreate" || mname == "onStart" || mname == "onResume") {
                    methodIdx = i
                    methodName = mname
                    break
                }
            }
        }
        if (methodIdx < 0) return false
        debug("DexPatcher: patche Methode $methodName (Zeile ${methodIdx + 1})")

        var regIdx = -1
        var isRegisters = false
        for (i in methodIdx until lines.size) {
            val t = lines[i].trimStart()
            if (t.startsWith(".locals ")) { regIdx = i; break }
            if (t.startsWith(".registers ")) { regIdx = i; isRegisters = true; break }
            if (t.startsWith(".end method")) break
        }
        if (regIdx < 0) return false

        // smali-Konvention: bei .registers R liegen die Parameter in den
        // HOECHSTEN Registern (p0 = v(R-P), p1 = v(R-P+1), ...). Erhoehen wir
        // R um 2, rutschen die Parameter nach oben (p0 = v(R+2-P)) und die
        // Register v(R-P) und v(R-P+1) werden zu freien lokalen Registern.
        // Genau DIESE sind unser neues Registerpaar.
        // P = Parameter aus der Signatur + 1 fuer this (bei Instanzmethoden).
        // Falsch waeren: v(R) (das ist p0 nach Erhoehung!) und v(R-1) (letztes
        // lokales Register, vom Original-Code belegt) — beide real getroffen
        // 08/2026 als VerifyError ("tried to get class from non-reference
        // register v13" bzw. "'this' argument Window not instance of
        // FragmentActivity").
        val isStatic = lines[methodIdx].contains(" static ")
        val sig = lines[methodIdx].substringAfter('(').substringBefore(')')
        val params = countRegisters(sig) + if (isStatic) 0 else 1
        val count = lines[regIdx].trim().substringAfter(' ').toIntOrNull() ?: return false
        lines[regIdx] = if (isRegisters) ".registers ${count + 2}" else ".locals ${count + 2}"

        // Bei .locals N sind vN, v(N+1) nach Erhoehung auf N+2 die neuen freien
        // Register. Bei .registers sind es v(count-params), v(count-params+1).
        val base = if (isRegisters) count - params else count
        val v0 = base
        val v1 = base + 1

        val code = listOf(
            "    invoke-virtual {p0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;",
            "    move-result-object v$v0",
            "    const/16 v$v1, 0x2000",
            "    invoke-virtual {v$v0, v$v1}, Landroid/view/Window;->addFlags(I)V"
        )
        lines.addAll(regIdx + 1, code)
        smaliFile.writeText(lines.joinToString("\n"))
        return true
    }

    /** Registeranzahl einer Methodensignatur (Parameterliste ohne Klammern). */
    private fun countRegisters(sig: String): Int {
        var count = 0
        var i = 0
        while (i < sig.length) {
            while (i < sig.length && sig[i] == '[') i++
            if (i >= sig.length) break
            when (sig[i]) {
                'J', 'D' -> count += 2
                'L' -> {
                    while (i < sig.length && sig[i] != ';') i++
                    count++
                }
                else -> count++
            }
            i++
        }
        return count
    }
}
