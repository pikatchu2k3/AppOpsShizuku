package de.minipc.appopsshizuku.stealth

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * addPermission-Test: ACCESS_NETWORK_STATE in ein Mini-Manifest einfuegen und
 * per aapt2 dump xmltree verifizieren (echter Android-Parser).
 */
class AddPermissionTest {

    private fun buildManifestApk(manifest: ByteArray): File =
        BinaryXmlTestData.manifestToApk(manifest)

    @Test
    fun addPermissionWirdVonAapt2Erkannt() {
        val manifest = BinaryXmlTestData.miniManifest()
        val patched = BinaryXml.addPermission(manifest, "android.permission.ACCESS_NETWORK_STATE")
        assertTrue("Manifest muss sich aendern", !patched.contentEquals(manifest))
        val apk = buildManifestApk(patched)
        try {
            val aapt2 = "/opt/android-sdk/build-tools/35.0.0/aapt2"
            val proc = ProcessBuilder(aapt2, "dump", "xmltree", apk.absolutePath, "--file", "AndroidManifest.xml")
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            assertTrue("aapt2 muss ACCESS_NETWORK_STATE zeigen, war:\n$out",
                out.contains("ACCESS_NETWORK_STATE"))
            assertTrue("uses-permission Element muss da sein", out.contains("uses-permission"))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun addPermissionIstIdempotent() {
        val manifest = BinaryXmlTestData.miniManifest()
        val once = BinaryXml.addPermission(manifest, "android.permission.ACCESS_NETWORK_STATE")
        val twice = BinaryXml.addPermission(once, "android.permission.ACCESS_NETWORK_STATE")
        // Interner Check: permissions() darf nur EINEN Eintrag liefern
        val perms = BinaryXml.permissions(twice)
        assertTrue("permissions() muss genau 1x ACCESS_NETWORK_STATE liefern, war: $perms",
            perms.count { it == "android.permission.ACCESS_NETWORK_STATE" } == 1)
        // aapt2 zaehlt: nur EIN uses-permission
        val apk = buildManifestApk(twice)
        try {
            val aapt2 = "/opt/android-sdk/build-tools/35.0.0/aapt2"
            val proc = ProcessBuilder(aapt2, "dump", "xmltree", apk.absolutePath, "--file", "AndroidManifest.xml")
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val count = Regex("E: uses-permission").findAll(out).count()
            assertTrue("nur 1 uses-permission-Element, war: $count", count == 1)
        } finally {
            apk.delete()
        }
    }
}
