package de.minipc.appopsshizuku.stealth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Device-Spoofing-Roundtrip:
 * 1. patchSmali ersetzt Build-Zugriffe / Settings.Secure.getString /
 *    TelephonyManager-Calls in einem Beispiel-Smali-Text.
 * 2. Die injizierte DeviceSpoof.smali-Helferklasse assembliert fehlerfrei.
 */
class DeviceSpoofSmaliTest {

    private val exampleSmali = """
        .class public Lcom/example/FakeApp;
        .super Landroid/app/Activity;

        .method public static getInfo()V
            .locals 3

            sget-object v0, Landroid/os/Build;->MODEL:Ljava/lang/String;
            sget-object v1, Landroid/os/Build;->MANUFACTURER:Ljava/lang/String;
            sget-object v2, Landroid/os/Build;->FINGERPRINT:Ljava/lang/String;

            return-void
        .end method

        .method public getAndroidId(Landroid/content/ContentResolver;)Ljava/lang/String;
            .locals 2

            const-string v0, "android_id"
            invoke-static {p1, v0}, Landroid/provider/Settings${'$'}Secure;->getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;
            move-result-object v1

            return-object v1
        .end method

        .method public getImei(Landroid/telephony/TelephonyManager;)Ljava/lang/String;
            .locals 1

            invoke-virtual {p1}, Landroid/telephony/TelephonyManager;->getImei()Ljava/lang/String;
            move-result-object v0

            return-object v0
        .end method

        .method public getSubscriber(Landroid/telephony/TelephonyManager;)Ljava/lang/String;
            .locals 1

            invoke-virtual {p1}, Landroid/telephony/TelephonyManager;->getSubscriberId()Ljava/lang/String;
            move-result-object v0

            return-object v0
        .end method
    """.trimIndent()

    @Test
    fun buildSpoofErsetztBuildFelder() {
        val options = DeviceSpoofOptions(spoofBuild = true)
        val out = DeviceSpoofPatch.patchSmali(exampleSmali, options)
        // Build-Zugriffe -> DeviceSpoof
        assertTrue("MODEL muss umgebogen werden", out.contains("Lde/minipc/spoof/DeviceSpoof;->MODEL"))
        assertTrue("MANUFACTURER muss umgebogen werden", out.contains("Lde/minipc/spoof/DeviceSpoof;->MANUFACTURER"))
        assertTrue("FINGERPRINT muss umgebogen werden", out.contains("Lde/minipc/spoof/DeviceSpoof;->FINGERPRINT"))
        assertFalse("Original-Build-Zugriff darf nicht mehr existieren", out.contains("Landroid/os/Build;->MODEL"))
        // Register bleiben erhalten
        assertTrue("Register v0 muss bleiben", out.contains("sget-object v0, Lde/minipc/spoof/DeviceSpoof;->MODEL"))
    }

    @Test
    fun androidIdSpoofErsetztSettingsSecure() {
        val options = DeviceSpoofOptions(spoofAndroidId = true)
        val out = DeviceSpoofPatch.patchSmali(exampleSmali, options)
        assertTrue("getString muss auf DeviceSpoof zeigen", out.contains("Lde/minipc/spoof/DeviceSpoof;->getString"))
        assertFalse("Original-Settings-Zugriff darf nicht mehr existieren", out.contains("Landroid/provider/Settings${'$'}Secure;->getString"))
    }

    @Test
    fun telephonySpoofErsetztImeiUndSubscriber() {
        val options = DeviceSpoofOptions(spoofTelephony = true)
        val out = DeviceSpoofPatch.patchSmali(exampleSmali, options)
        assertTrue("getImei muss auf telephony zeigen", out.contains("Lde/minipc/spoof/DeviceSpoof;->telephony"))
        assertTrue("getSubscriberId muss auf subscriberId zeigen", out.contains("Lde/minipc/spoof/DeviceSpoof;->subscriberId"))
        assertFalse("Original-Telephony-Zugriff darf nicht mehr existieren", out.contains("Landroid/telephony/TelephonyManager;->getImei"))
        assertFalse("Original-Subscriber-Zugriff darf nicht mehr existieren", out.contains("Landroid/telephony/TelephonyManager;->getSubscriberId"))
    }

    @Test
    fun keinSpoofLaesstTextUnveraendert() {
        val out = DeviceSpoofPatch.patchSmali(exampleSmali, DeviceSpoofOptions())
        assertTrue("ohne Optionen unveraendert", out == exampleSmali)
    }

    @Test
    fun deviceSpoofSmaliAssembliert() {
        // Die injizierte Helferklasse muss fehlerfrei assemblieren (Register/Labels)
        val dir = createTempDir()
        try {
            val spoofFile = File(dir, "de/minipc/spoof/DeviceSpoof.smali")
            spoofFile.parentFile.mkdirs()
            // Konstante per Reflection holen (private const val DEVICE_SPOOF_SMALI)
            val template = try {
                val f = DexPatcher.javaClass.getDeclaredField("DEVICE_SPOOF_SMALI")
                f.isAccessible = true
                f.get(null) as String
            } catch (e: Exception) {
                null
            }
            assertTrue("Template muss ladbar sein", template != null && template.isNotBlank())
            // Platzhalter durch Testwerte ersetzen (wie renderDeviceSpoofSmali)
            val rendered = template!!
                .replace("@@MODEL@@", "FakeModel")
                .replace("@@MANUFACTURER@@", "FakeManu")
                .replace("@@BRAND@@", "FakeBrand")
                .replace("@@DEVICE@@", "FakeDevice")
                .replace("@@PRODUCT@@", "FakeProduct")
                .replace("@@FINGERPRINT@@", "FakeFp")
                .replace("@@HARDWARE@@", "FakeHw")
                .replace("@@ANDROID_ID@@", "deadbeef-dead-beef-dead-beefdeadbeef")
                .replace("@@IMEI@@", "000000000000000")
                .replace("@@IMSI@@", "000000000000000")
            spoofFile.writeText(rendered)
            val outDex = File(dir, "spoof.dex")
            val assembled = DexPatcherTestHelper.assemble(dir, outDex)
            assertTrue("DeviceSpoof-Smali muss assemblieren", assembled)
            assertTrue("Dex muss existieren", outDex.exists() && outDex.length() > 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}
