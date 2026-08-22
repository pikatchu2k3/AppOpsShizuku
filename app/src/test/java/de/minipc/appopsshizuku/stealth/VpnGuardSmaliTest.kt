package de.minipc.appopsshizuku.stealth

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * VPN-Guard-Roundtrip: patchSmaliVpnGuard auf eine Beispiel-Activity-Smali
 * anwenden und sicherstellen, dass (a) der Guard-Aufruf nach super.onCreate
 * eingefuegt wird und (b) die Assemblierung fehlerfrei durchlaeuft (Register/
 * Label-Syntax). Zusaetzlich: VpnGuard-Smali selber assemblieren.
 */
class VpnGuardSmaliTest {

    private val activitySmali = """
        .class public Lcom/example/FakeActivity;
        .super Landroid/app/Activity;

        .method protected onCreate(Landroid/os/Bundle;)V
            .locals 2

            invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V

            const/4 v0, 0x1

            invoke-virtual {p0, v0}, Lcom/example/FakeActivity;->setVisible(Z)V

            return-void
        .end method
    """.trimIndent()

    @Test
    fun vpnGuardWirdNachSuperOnCreateEingefuegt() {
        val f = File.createTempFile("fake_activity", ".smali")
        f.writeText(activitySmali)
        try {
            val ok = DexPatcher.patchSmaliVpnGuard(f)
            assertTrue("patchSmaliVpnGuard muss true liefern", ok)
            val patched = f.readText()
            // Guard-Aufruf vorhanden
            assertTrue("Guard-Aufruf fehlt", patched.contains("Lde/minipc/vpnguard/VpnGuard;->guard"))
            // Muss NACH invoke-super kommen (nicht davor)
            val superIdx = patched.indexOf("invoke-super")
            val guardIdx = patched.indexOf("VpnGuard;->guard")
            assertTrue("Guard muss nach invoke-super kommen", guardIdx > superIdx)
            // Original-Code bleibt erhalten
            assertTrue("Original-Code fehlt", patched.contains("setVisible(Z)V"))
            // Kein doppelter Guard bei erneutem Aufruf
            val ok2 = DexPatcher.patchSmaliVpnGuard(f)
            assertTrue("zweiter Patch ok", ok2)
            val count = Regex("VpnGuard;->guard").findAll(f.readText()).count()
            assertTrue("Guard darf nur einmal eingefuegt werden, war: $count", count == 1)
        } finally {
            f.delete()
        }
    }

    @Test
    fun vpnGuardSmaliAssembliert() {
        // Die injizierte Helferklasse muss fehlerfrei assemblieren
        val dir = createTempDir()
        try {
            val guardFile = File(dir, "de/minipc/vpnguard/VpnGuard.smali")
            guardFile.parentFile.mkdirs()
            // VPN_GUARD_SMALI ist private const — hier manuell nachbauen (gleicher Inhalt)
            guardFile.writeText("""
                .class public Lde/minipc/vpnguard/VpnGuard;
                .super Ljava/lang/Object;

                .method public static guard(Landroid/app/Activity;)V
                    .registers 8

                    const/4 v0, 0x0

                    const-string v1, "connectivity"
                    invoke-virtual {p0, v1}, Landroid/app/Activity;->getSystemService(Ljava/lang/String;)Ljava/lang/Object;
                    move-result-object v1
                    if-eqz v1, :no_vpn

                    check-cast v1, Landroid/net/ConnectivityManager;

                    invoke-virtual {v1}, Landroid/net/ConnectivityManager;->getActiveNetwork()Landroid/net/Network;
                    move-result-object v2
                    if-eqz v2, :no_vpn

                    invoke-virtual {v1, v2}, Landroid/net/ConnectivityManager;->getNetworkCapabilities(Landroid/net/Network;)Landroid/net/NetworkCapabilities;
                    move-result-object v3
                    if-eqz v3, :no_vpn

                    const/4 v4, 0x4
                    invoke-virtual {v3, v4}, Landroid/net/NetworkCapabilities;->hasTransport(I)Z
                    move-result v5
                    if-nez v5, :vpn_ok

                    :no_vpn
                    invoke-virtual {p0}, Landroid/app/Activity;->finish()V

                    :vpn_ok
                    return-void
                .end method
            """.trimIndent())
            val outDex = File(dir, "guard.dex")
            val assembled = DexPatcherTestHelper.assemble(dir, outDex)
            assertTrue("VpnGuard-Smali muss assemblieren", assembled)
            assertTrue("Dex muss existieren", outDex.exists() && outDex.length() > 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** Kleiner Helfer: greift auf die interne assemble-Methode von DexPatcher zu. */
object DexPatcherTestHelper {
    fun assemble(smaliDir: File, outDex: File): Boolean {
        return try {
            val method = DexPatcher.javaClass.getDeclaredMethod("assemble", File::class.java, File::class.java)
            method.isAccessible = true
            method.invoke(DexPatcher, smaliDir, outDex) as Boolean
        } catch (e: Exception) {
            false
        }
    }
}
