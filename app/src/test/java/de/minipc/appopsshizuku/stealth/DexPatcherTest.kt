package de.minipc.appopsshizuku.stealth

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Testet den FLAG_SECURE-Dex-Patch auf dem Host gegen die echte FOSS-APK.
 * Ausgabe zeigt, an welcher Stelle der Patch ggf. scheitert.
 */
class DexPatcherTest {

    @Test
    fun patchFossDex_erzeugtNeuesDex() {
        val apk = File("/tmp/foss_original.apk")
        if (!apk.exists()) { println("SKIP: /tmp/foss_original.apk fehlt"); return }
        val work = File.createTempFile("dexwork", "").apply { delete(); mkdirs() }
        try {
            val result = DexPatcher.patchForSecure(
                apk, work, "de.baumann.browser.activity.BrowserActivity"
            )
            println("=== result keys: ${result.keys} ===")
            result.forEach { (name, bytes) ->
                println("$name -> ${bytes.size} B")
            }
            assertTrue("Dex-Patch muss ein neues classes.dex liefern", result.isNotEmpty())
        } finally {
            work.deleteRecursively()
        }
    }
}
