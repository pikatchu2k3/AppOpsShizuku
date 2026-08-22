package de.minipc.appopsshizuku.stealth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * App-Cloning-Tests:
 * 1. CloneRewriter.rewriteSmaliText — Klassen-Referenzen, Strings, Idempotenz
 * 2. CloneRewriter.rewriteSmaliTree — Verzeichnis-Verschiebung
 * 3. BinaryXml.renamePackage — Manifest-Roundtrip (package + Komponenten)
 */
class CloneRewriterTest {

    private val sampleSmali = """
        .class public Lcom/example/app/MainActivity;
        .super Landroid/app/Activity;

        .method public onCreate(Landroid/os/Bundle;)V
            .locals 2

            invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V

            const-string v0, "com.example.app"
            const-string v1, "com.example.app_preferences"
            const-string v2, "com.example.app.provider"

            new-instance v0, Lcom/example/app/Helper;
            invoke-direct {v0}, Lcom/example/app/Helper;-><init>()V

            return-void
        .end method
    """.trimIndent()

    @Test
    fun rewriteSmaliTextErsetztKlassenUndStrings() {
        val out = CloneRewriter.rewriteSmaliText(sampleSmali, "com.example.app", "com.example.app.stealth")
        // Klassen-Referenzen
        assertTrue("Klasse muss umbenannt werden", out.contains("Lcom/example/app/stealth/MainActivity;"))
        assertTrue("Helper-Referenz muss umbenannt werden", out.contains("Lcom/example/app/stealth/Helper;"))
        assertFalse("Alte Klasse darf nicht bleiben", out.contains("Lcom/example/app/MainActivity;"))
        // String-Konstanten
        assertTrue("Exakter Paketstring muss ersetzt werden", out.contains("\"com.example.app.stealth\""))
        assertTrue("Prefs-Präfix muss ersetzt werden", out.contains("com.example.app.stealth_preferences"))
        assertTrue("Authority-Präfix muss ersetzt werden", out.contains("com.example.app.stealth.provider"))
        // Original-Code bleibt
        assertTrue("onCreate muss bleiben", out.contains("invoke-super"))
    }

    @Test
    fun rewriteSmaliTextLaesstFremdPaketeUnangetastet() {
        val withForeign = sampleSmali + """
            const-string v3, "com.example.appx"
            const-string v4, "de.other.app"
        """.trimIndent()
        val out = CloneRewriter.rewriteSmaliText(withForeign, "com.example.app", "com.example.app.stealth")
        assertTrue("com.example.appx darf NICHT ersetzt werden", out.contains("com.example.appx"))
        assertTrue("de.other.app darf NICHT ersetzt werden", out.contains("de.other.app"))
        assertFalse("appx darf nicht zu app.stealthx werden", out.contains("com.example.app.stealthx"))
    }

    @Test
    fun rewriteSmaliTextIstIdempotent() {
        val once = CloneRewriter.rewriteSmaliText(sampleSmali, "com.example.app", "com.example.app.stealth")
        val twice = CloneRewriter.rewriteSmaliText(once, "com.example.app", "com.example.app.stealth")
        if (once != twice) {
            val a = once.lineSequence().toList()
            val b = twice.lineSequence().toList()
            val diff = a.zip(b).filter { it.first != it.second }.map { "1: ${it.first}\n2: ${it.second}" }
            println("DIFFS:\n${diff.joinToString("\n---\n")}")
        }
        assertTrue("Zweiter Lauf darf nichts ändern", once == twice)
        assertFalse("Kein doppeltes .stealth.stealth", twice.contains(".stealth.stealth"))
    }

    @Test
    fun rewriteSmaliTreeVerschiebtVerzeichnisse() {
        val dir = createTempDir()
        try {
            val src = File(dir, "com/example/app")
            src.mkdirs()
            File(src, "MainActivity.smali").writeText(sampleSmali)
            File(src, "Helper.smali").writeText(".class public Lcom/example/app/Helper;\n.super Ljava/lang/Object;\n")
            val changed = CloneRewriter.rewriteSmaliTree(dir, "com.example.app", "com.example.app.stealth")
            assertTrue("2 Dateien müssen geändert sein, war: $changed", changed == 2)
            val dst = File(dir, "com/example/app/stealth")
            assertTrue("MainActivity muss verschoben sein", File(dst, "MainActivity.smali").exists())
            assertTrue("Helper muss verschoben sein", File(dst, "Helper.smali").exists())
            assertFalse("Altes Verzeichnis muss leer/weg sein", File(src, "MainActivity.smali").exists())
            // Inhalt: Klassen-Referenz umbenannt
            val content = File(dst, "MainActivity.smali").readText()
            assertTrue(content.contains("Lcom/example/app/stealth/MainActivity;"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun renamePackageImManifest() {
        // Echte Manifest-Bytes mit Package de.minipc.spooftest erzeugen ist aufwändig —
        // stattdessen: Null-/Trivial-Fälle testen (gleicher Name, leere Daten).
        val empty = ByteArray(0)
        assertTrue("Leere Daten unverändert", BinaryXml.renamePackage(empty, "a.b", "a.b.stealth").isEmpty())
        val tiny = ByteArray(4)
        assertTrue("Kleine Daten unverändert", BinaryXml.renamePackage(tiny, "a.b", "a.b.stealth").contentEquals(tiny))
        // Gleicher Name -> unverändert (auch bei gültigen Daten)
        val sameName = ByteArray(40)
        assertTrue("Gleicher Name unverändert", BinaryXml.renamePackage(sameName, "a.b", "a.b").contentEquals(sameName))
    }

    @Test
    fun renamePackageEchtesManifest() {
        // Echtes UTF-16-Manifest (FOSS Browser) — Roundtrip: package tauschen,
        // Ergebnis muss weiterhin parsebar sein + neues Package liefern.
        val manifest = javaClass.classLoader
            ?.getResourceAsStream("manifests/foss_manifest.xml")
            ?.use { it.readBytes() }
        assertTrue("Manifest-Ressource muss laden", manifest != null && manifest.size > 1000)
        val oldPkg = BinaryXml.packageName(manifest!!)
        assertTrue("Package muss lesbar sein: $oldPkg", oldPkg != null)
        val newPkg = "${oldPkg}.stealth"
        val renamed = BinaryXml.renamePackage(manifest, oldPkg!!, newPkg)
        assertTrue("Ergebnis größer als Original", renamed.size > manifest.size)
        val renamedPkg = BinaryXml.packageName(renamed)
        // STRENGER CHECK: Der apksig-Parser (derselbe, den ApkSigner/Android zum
        // Installieren nutzen) muss das umbenannte Manifest akzeptieren.
        // Auf dem Emulator real getroffen: "Corrupt XML binary file" bei
        // fehlerhaftem StringPool — mein eigener Parser war toleranter!
        val apksigOk = try {
            val apkUtils = Class.forName("com.android.apksig.apk.ApkUtils")
            // getPackageNameFromBinaryAndroidManifest(ByteBuffer) — wirft ApkFormatException
            // bei korruptem XML; liefert den Paketnamen bei Erfolg.
            val method = apkUtils.getMethod(
                "getPackageNameFromBinaryAndroidManifest",
                java.nio.ByteBuffer::class.java
            )
            val result = method.invoke(null, java.nio.ByteBuffer.wrap(renamed)) as String
            println("DEBUG apksig liefert: $result")
            result.isNotEmpty()
        } catch (e: Exception) {
            println("DEBUG apksig-Fehler: ${e.cause?.message ?: e.message}")
            false
        }
        if (!apksigOk) {
            println("DEBUG renamed.size=${renamed.size} orig=${manifest.size}")
            println("DEBUG apksig-Parser LEHNT das Manifest ab — StringPool fehlerhaft!")
        }
        assertTrue("apksig-Parser muss das umbenannte Manifest akzeptieren", apksigOk)
        assertTrue("Neues Package muss lesbar sein: $renamedPkg", renamedPkg == newPkg)
        // Erneuter Lauf: idempotent (newPkg enthält oldPkg als Präfix, darf nicht doppeln)
        val renamed2 = BinaryXml.renamePackage(renamed, oldPkg, newPkg)
        val renamed2Pkg = BinaryXml.packageName(renamed2)
        assertTrue("Idempotent: $renamed2Pkg", renamed2Pkg == newPkg)
    }

    @Test
    fun renamePackageSpooftestManifest() {
        // Das Manifest der Spoof-Test-App — der Fall, der auf dem Emulator real
        // "Corrupt XML binary file" warf. Muss mit dem apksig-Parser roundtrippen.
        val manifest = javaClass.classLoader
            ?.getResourceAsStream("manifests/spooftest_manifest.xml")
            ?.use { it.readBytes() }
        assertTrue("Spooftest-Manifest muss laden", manifest != null && manifest.size > 1000)
        val oldPkg = BinaryXml.packageName(manifest!!)
        assertTrue("Package muss lesbar sein: $oldPkg", oldPkg != null)
        val newPkg = "${oldPkg}.stealth"
        val renamed = BinaryXml.renamePackage(manifest, oldPkg!!, newPkg)
        val renamedPkg = BinaryXml.packageName(renamed)
        assertTrue("Neues Package lesbar: $renamedPkg", renamedPkg == newPkg)
        val apksigOk = try {
            val apkUtils = Class.forName("com.android.apksig.apk.ApkUtils")
            val method = apkUtils.getMethod(
                "getPackageNameFromBinaryAndroidManifest",
                java.nio.ByteBuffer::class.java
            )
            val result = method.invoke(null, java.nio.ByteBuffer.wrap(renamed)) as String
            println("DEBUG spooftest apksig liefert: $result")
            result == newPkg
        } catch (e: Exception) {
            println("DEBUG spooftest apksig-Fehler: ${e.cause?.message ?: e.message}")
            false
        }
        assertTrue("apksig muss Spooftest-Klon akzeptieren", apksigOk)
        // Idempotenz
        val renamed2 = BinaryXml.renamePackage(renamed, oldPkg, newPkg)
        assertTrue("Idempotent: ${BinaryXml.packageName(renamed2)}", BinaryXml.packageName(renamed2) == newPkg)
    }

    @Test
    fun renamePackageSpooftestNativeParser() {
        // DER definitive Check: aapt2 (der native Android-Parser, identisch mit
        // ApkAssets.nativeOpenXml auf dem Gerät). Packt das umbenannte Manifest
        // in eine Mini-APK und parst sie — wirft bei korruptem XML.
        // Auf dem Emulator real getroffen: "Corrupt XML binary file" trotz
        // grünem apksig-JVM-Test — der native Parser ist strenger!
        val aapt2 = listOf(
            "/opt/android-sdk/build-tools/35.0.0/aapt2",
            "/opt/android-sdk/build-tools/34.0.0/aapt2"
        ).firstOrNull { File(it).exists() }
        assertTrue("aapt2 muss vorhanden sein", aapt2 != null)
        val manifest = javaClass.classLoader
            ?.getResourceAsStream("manifests/spooftest_manifest.xml")
            ?.use { it.readBytes() }
        assertTrue("Manifest muss laden", manifest != null && manifest.size > 1000)
        val oldPkg = BinaryXml.packageName(manifest!!)!!
        val newPkg = "${oldPkg}.stealth"
        val renamed = BinaryXml.renamePackage(manifest, oldPkg, newPkg)
        // Manifest + leere res/ in eine Mini-APK packen
        val dir = createTempDir()
        try {
            val apk = File(dir, "test.apk")
            java.util.zip.ZipOutputStream(apk.outputStream()).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
                zos.write(renamed)
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("res/"))
                zos.closeEntry()
            }
            val proc = ProcessBuilder(aapt2, "dump", "badging", apk.absolutePath)
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code != 0 || !out.contains("package:")) {
                println("DEBUG aapt2 STDOUT:\n$out")
            }
            assertTrue("aapt2 muss APK akzeptieren (exit=$code)", code == 0)
            assertTrue("aapt2 muss neues Package melden", out.contains("package: name='$newPkg'"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun setApplicationLabelEchtesManifest() {
        // User-Fall 08/2026: Klon mit eigenem Namen ("test.stealth") — das Label
        // muss auf "test" gesetzt werden, sonst heißen Original + Klon gleich.
        val aapt2 = listOf(
            "/opt/android-sdk/build-tools/35.0.0/aapt2",
            "/opt/android-sdk/build-tools/34.0.0/aapt2"
        ).firstOrNull { File(it).exists() }
        assertTrue("aapt2 muss vorhanden sein", aapt2 != null)
        val manifest = javaClass.classLoader
            ?.getResourceAsStream("manifests/foss_manifest.xml")
            ?.use { it.readBytes() }
        assertTrue("Manifest muss laden", manifest != null && manifest.size > 1000)
        val oldPkg = BinaryXml.packageName(manifest!!)!!
        // Erst Paket umbenennen, dann Label setzen (Pipeline-Reihenfolge)
        val renamed = BinaryXml.renamePackage(manifest, oldPkg, "${oldPkg}.stealth")
        val relabeled = BinaryXml.setApplicationLabel(renamed, "test")
        assertTrue("Label-Patch muss etwas ändern", !relabeled.contentEquals(renamed))
        val dir = createTempDir()
        try {
            val apk = File(dir, "test.apk")
            java.util.zip.ZipOutputStream(apk.outputStream()).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
                zos.write(relabeled)
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("res/"))
                zos.closeEntry()
            }
            val proc = ProcessBuilder(aapt2, "dump", "badging", apk.absolutePath)
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            val labelLine = out.lineSequence().firstOrNull { it.contains("label=") }
            if (code != 0 || !out.contains("label='test'")) {
                println("DEBUG aapt2 STDOUT:\n$out")
            }
            assertTrue("aapt2 muss APK akzeptieren (exit=$code)", code == 0)
            assertTrue(
                "Label muss 'test' sein, war: $labelLine",
                out.contains("label='test'")
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
