package de.minipc.appopsshizuku.stealth

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Erzeugt ein echtes binäres AndroidManifest.xml (via aapt2 link) für Tests.
 * Die Manifest-Quelle ist ein Minimal-Manifest mit einer Activity.
 */
object BinaryXmlTestData {

    val MANIFEST_XML = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.test.addperm">
            <application android:label="AddPerm">
                <activity android:name=".MainActivity" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN"/>
                        <category android:name="android.intent.category.LAUNCHER"/>
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """.trimIndent()

    /** Baut die Mini-APK mit aapt2 und liest das AXML-Manifest heraus. */
    fun miniManifest(): ByteArray {
        val dir = createTempDir()
        try {
            val manifestFile = File(dir, "AndroidManifest.xml")
            manifestFile.writeText(MANIFEST_XML)
            val apk = File(dir, "mini.apk")
            val aapt2 = "/opt/android-sdk/build-tools/35.0.0/aapt2"
            val proc = ProcessBuilder(
                aapt2, "link",
                "-o", apk.absolutePath,
                "-I", "/opt/android-sdk/platforms/android-35/android.jar",
                "--manifest", manifestFile.absolutePath,
                "--min-sdk-version", "26",
                "--target-sdk-version", "35"
            ).redirectErrorStream(true).start()
            val err = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            check(apk.exists()) { "aapt2 link fehlgeschlagen: $err" }
            // Manifest aus der APK extrahieren (STORE-komprimiert? aapt2 nutzt DEFLATE -> ZipInputStream)
            val result = java.util.zip.ZipFile(apk).use { zf ->
                zf.getInputStream(zf.getEntry("AndroidManifest.xml")).readBytes()
            }
            return result
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Packt ein Manifest in eine Mini-APK (für aapt2 dump). */
    fun manifestToApk(manifest: ByteArray): File {
        val apk = File.createTempFile("manifest_test", ".apk")
        ZipOutputStream(apk.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(manifest)
            zos.closeEntry()
        }
        return apk
    }
}
