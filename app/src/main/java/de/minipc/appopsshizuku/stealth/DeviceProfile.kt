package de.minipc.appopsshizuku.stealth

import kotlin.random.Random

/**
 * Realistisches Geräteprofil für konsistentes Build-Spoofing.
 *
 * WICHTIG: Modell, Hersteller, Marke, Gerätecode und Fingerprint müssen
 * ZUSAMMENPASSEN — ein "FakePhone" mit "Google"-Fingerprint wäre sofort als
 * Fake erkennbar (Fingerprinting-Analysen prüfen Konsistenz). Deshalb werden
 * beim Zufalls-Button komplette Profile gewählt, nicht einzelne Werte.
 *
 * Fingerprint-Format (Android-Standard):
 *   <brand>/<product>/<device>:<version>/<build-id>/<incremental>:<build-type>/<keys>
 * Die Werte hier sind reale Build-Fingerprints bekannter Geräte (Stand 2024/25),
 * damit die Profile glaubwürdig sind.
 */
data class DeviceProfile(
    val model: String,
    val manufacturer: String,
    val brand: String,
    val device: String,
    val product: String,
    val fingerprint: String
) {
    /** Fingerprint in lesbarer Form für die UI (zu lang für ein Eingabefeld). */
    val shortFingerprint: String
        get() = fingerprint.split(":").first()
}

/**
 * Sammlung realer Geräteprofile. Auswahl per Zufall — liefert ein konsistentes
 * Set aus Model/Manufacturer/Fingerprint (User-Wunsch 08/2026: "Random-Werte
 * per Tastendruck").
 */
object DeviceProfiles {
    val profiles: List<DeviceProfile> = listOf(
        DeviceProfile(
            model = "Pixel 8", manufacturer = "Google", brand = "google",
            device = "shusky", product = "shusky",
            fingerprint = "google/shusky/shusky:14/UD1A.230803.041/10804434:user/release-keys"
        ),
        DeviceProfile(
            model = "Pixel 7", manufacturer = "Google", brand = "google",
            device = "cheetah", product = "cheetah",
            fingerprint = "google/cheetah/cheetah:13/TQ3A.230901.001/10179119:user/release-keys"
        ),
        DeviceProfile(
            model = "Pixel 6a", manufacturer = "Google", brand = "google",
            device = "bluejay", product = "bluejay",
            fingerprint = "google/bluejay/bluejay:13/TQ3A.230901.001/10179119:user/release-keys"
        ),
        DeviceProfile(
            model = "Galaxy S23", manufacturer = "samsung", brand = "samsung",
            device = "dm3q", product = "dm3qxx",
            fingerprint = "samsung/dm3qxx/dm3q:13/TP1A.220624.014/S918BXXS1AWA1:user/release-keys"
        ),
        DeviceProfile(
            model = "Galaxy A54 5G", manufacturer = "samsung", brand = "samsung",
            device = "a54x", product = "a54xxx",
            fingerprint = "samsung/a54xxx/a54x:13/TP1A.220624.014/A546BXXS1AWA1:user/release-keys"
        ),
        DeviceProfile(
            model = "OnePlus 11", manufacturer = "OnePlus", brand = "OnePlus",
            device = "PHB110", product = "PHB110",
            fingerprint = "OnePlus/PHB110/PHB110:13/TKQ1.221114.001/S.1f82b33-1-1:user/release-keys"
        ),
        DeviceProfile(
            model = "Xiaomi 13", manufacturer = "Xiaomi", brand = "Xiaomi",
            device = "fuxi", product = "fuxi",
            fingerprint = "Xiaomi/fuxi/fuxi:13/TKQ1.220829.002/V14.0.7.0.TMCCNXM:user/release-keys"
        ),
        DeviceProfile(
            model = "SM-A528B (Galaxy A52s)", manufacturer = "samsung", brand = "samsung",
            device = "a52sxq", product = "a52sxqeea",
            fingerprint = "samsung/a52sxqeea/a52sxq:12/SP1A.210812.016/A528BXXS1AVK1:user/release-keys"
        ),
        DeviceProfile(
            model = "Fairphone 5", manufacturer = "Fairphone", brand = "Fairphone",
            device = "FP5", product = "FP5",
            fingerprint = "Fairphone/FP5/FP5:13/TKQ1.221114.001/FP5.A.142-000-000:user/release-keys"
        ),
        DeviceProfile(
            model = "Moto G84", manufacturer = "motorola", brand = "motorola",
            device = "manaus", product = "manaus_g",
            fingerprint = "motorola/manaus_g/manaus:13/T1SQAS33.41-20-8/9e6c0:user/release-keys"
        ),
        DeviceProfile(
            model = "Nothing Phone (2)", manufacturer = "Nothing", brand = "Nothing",
            device = "Pong", product = "Pong",
            fingerprint = "Nothing/Pong/Pong:13/TKQ1.221114.001/Nothing-2.0.4-230706-1420:user/release-keys"
        ),
        DeviceProfile(
            model = "Pixel 8 Pro", manufacturer = "Google", brand = "google",
            device = "husky", product = "husky",
            fingerprint = "google/husky/husky:14/UD1A.230803.041/10804434:user/release-keys"
        )
    )

    /** Liefert ein zufälliges Profil (Seed = Systemzeit → variiert pro Klick). */
    fun random(): DeviceProfile = profiles[Random.nextInt(profiles.size)]
}
