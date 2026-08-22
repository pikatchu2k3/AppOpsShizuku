package de.minipc.appopsshizuku.stealth

/**
 * App-spezifisches Device-Spoofing via Smali-Call-Site-Rewriting (Qwen-Konzept,
 * 08/2026): Die Ziel-App wird ohnehin neu signiert + gepatched. Statt
 * Framework-/System-Klassen neu zu schreiben (ClassLoader-Parent-Delegation
 * ignoriert eigene Kopien), wird eine eigene Helper-Klasse `DeviceSpoof.smali`
 * in die APK injiziert und die Aufrufstellen in der Ziel-App umgebogen.
 *
 * Verifiziert: `cmd appops set <pkg> android:ad_id ignore` existiert auf API 35
 * NICHT (Qwen-Behauptung widerlegt) — Advertising-ID-Blockade ist daher NICHT
 * Teil der Smali-Patches (siehe PROJEKTSTAND).
 */
data class DeviceSpoofOptions(
    val fakeModel: String = "",
    val fakeManufacturer: String = "",
    val fakeBrand: String = "",
    val fakeDevice: String = "",
    val fakeProduct: String = "",
    val fakeFingerprint: String = "",
    val fakeAndroidId: String = "",
    val fakeImei: String = "",
    val fakeImsi: String? = null,
    val spoofBuild: Boolean = false,
    val spoofAndroidId: Boolean = false,
    val spoofTelephony: Boolean = false
) {
    val anyEnabled: Boolean
        get() = spoofBuild || spoofAndroidId || spoofTelephony
}

object DeviceSpoofPatch {

    /**
     * Ersetzt in einem Smali-Text die Aufrufstellen auf System-Klassen durch
     * Aufrufe auf die injizierte DeviceSpoof-Helper-Klasse.
     * Nur der Text wird ersetzt — keine .locals/.registers-Zaehlung noetig,
     * weil die Register-Variablen beibehalten werden.
     */
    fun patchSmali(smali: String, options: DeviceSpoofOptions): String {
        if (!options.anyEnabled) return smali

        var out = smali

        if (options.spoofBuild) {
            // sget-object vX, Landroid/os/Build;->MODEL:Ljava/lang/String;
            // -> sget-object vX, Lde/minipc/spoof/DeviceSpoof;->MODEL:Ljava/lang/String;
            val buildRegex = Regex(
                """sget-object\s+([vp]\d+)\s*,\s*Landroid/os/Build;->(MODEL|MANUFACTURER|BRAND|DEVICE|PRODUCT|FINGERPRINT|HARDWARE):Ljava/lang/String;"""
            )
            out = buildRegex.replace(out) { m ->
                "sget-object ${m.groupValues[1]}, Lde/minipc/spoof/DeviceSpoof;->${m.groupValues[2]}:Ljava/lang/String;"
            }
        }

        if (options.spoofAndroidId) {
            // invoke-static {vX, vY}, Landroid/provider/Settings$Secure;->getString(...)
            // -> invoke-static {vX, vY}, Lde/minipc/spoof/DeviceSpoof;->getString(...)
            val androidIdRegex = Regex(
                """invoke-static\s*\{([vp]\d+)\s*,\s*([vp]\d+)\s*\}\s*,\s*Landroid/provider/Settings\${'$'}Secure;->getString\(Landroid/content/ContentResolver;Ljava/lang/String;\)Ljava/lang/String;"""
            )
            out = androidIdRegex.replace(out) { m ->
                "invoke-static {${m.groupValues[1]}, ${m.groupValues[2]}}, Lde/minipc/spoof/DeviceSpoof;->getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;"
            }
        }

        if (options.spoofTelephony) {
            // getDeviceId()/getImei() -> telephony() (IMEI-Fake)
            val imeiRegex = Regex(
                """invoke-virtual\s*\{([vp]\d+)\s*\}\s*,\s*Landroid/telephony/TelephonyManager;->(getDeviceId|getImei)\(\)Ljava/lang/String;"""
            )
            out = imeiRegex.replace(out) { m ->
                "invoke-static {${m.groupValues[1]}}, Lde/minipc/spoof/DeviceSpoof;->telephony(Landroid/telephony/TelephonyManager;)Ljava/lang/String;"
            }
            // getDeviceId(slot)/getImei(slot) -> telephonySlot() (Slot-Varianten,
            // API 23+: Dual-SIM-Geraete rufen die Version mit int-Parameter auf)
            val imeiSlotRegex = Regex(
                """invoke-virtual\s*\{([vp]\d+)\s*,\s*([vp]\d+)\s*\}\s*,\s*Landroid/telephony/TelephonyManager;->(getDeviceId|getImei)\(I\)Ljava/lang/String;"""
            )
            out = imeiSlotRegex.replace(out) { m ->
                "invoke-static {${m.groupValues[1]}, ${m.groupValues[2]}}, Lde/minipc/spoof/DeviceSpoof;->telephonySlot(Landroid/telephony/TelephonyManager;I)Ljava/lang/String;"
            }
            // getSubscriberId -> subscriberId() (IMSI-Fake)
            val imsiRegex = Regex(
                """invoke-virtual\s*\{([vp]\d+)\s*\}\s*,\s*Landroid/telephony/TelephonyManager;->getSubscriberId\(\)Ljava/lang/String;"""
            )
            out = imsiRegex.replace(out) { m ->
                "invoke-static {${m.groupValues[1]}}, Lde/minipc/spoof/DeviceSpoof;->subscriberId(Landroid/telephony/TelephonyManager;)Ljava/lang/String;"
            }
        }

        return out
    }
}
