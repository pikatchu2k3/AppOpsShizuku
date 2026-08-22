package de.minipc.appopsshizuku.stealth

import org.junit.Test
import java.io.File

/**
 * Reproduziert die komplette Pipeline: Original-APK -> baksmali -> patch ->
 * smali -> neues Dex -> baksmali zurueck. Zeigt, ob der Assembler die
 * Register umnummeriert (v13/v14 -> p0/p1).
 */
class PipelineRoundtripTest {

    @Test
    fun roundtrip() {
        val apk = File("/tmp/foss_original.apk")
        val work = File.createTempFile("pipe", "").apply { delete(); mkdirs() }
        try {
            // 1. Dex extrahieren
            val dexFile = File(work, "classes.dex")
            java.util.zip.ZipFile(apk).use { zf ->
                zf.getInputStream(zf.getEntry("classes.dex")).use { input ->
                    dexFile.outputStream().use { input.copyTo(it) }
                }
            }
            // 2. baksmali
            val smaliDir = File(work, "smali")
            disassemble(dexFile, smaliDir)
            val smaliFile = File(smaliDir, "de/baumann/browser/activity/BrowserActivity.smali")
            // 3. patch
            DexPatcher.patchSmali(smaliFile)
            println("=== GEPATCHTE Smali (onCreate-Anfang) ===")
            val lines = smaliFile.readLines()
            for (i in lines.indices) {
                if (lines[i].contains(".method public onCreate")) {
                    for (j in i until (i + 8)) println("  ${lines[j].trimStart()}")
                    break
                }
            }
            // 4. smali assembeln
            val outDex = File(work, "new_classes.dex")
            assemble(smaliDir, outDex)
            println("\n=== neues Dex: ${outDex.length()} B ===")
            // 5. baksmali zurueck
            val backDir = File(work, "back")
            disassemble(outDex, backDir)
            val backFile = File(backDir, "de/baumann/browser/activity/BrowserActivity.smali")
            println("=== ROUNDTRIP onCreate (assembliert + zurueck) ===")
            val bl = backFile.readLines()
            for (i in bl.indices) {
                if (bl[i].contains(".method public onCreate")) {
                    for (j in i until (i + 8)) println("  ${bl[j].trimStart()}")
                    break
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun disassemble(dexFile: File, outDir: File) {
        val cmd = Class.forName("org.jf.baksmali.DisassembleCommand")
            .getConstructor(List::class.java).newInstance(emptyList<Any>())
        setField(cmd, "inputList", listOf(dexFile.absolutePath))
        setField(cmd, "outputDir", outDir.absolutePath)
        setField(cmd, "apiLevel", 35)
        setField(cmd, "jobs", 1)
        cmd.javaClass.getMethod("run").invoke(cmd)
    }

    private fun assemble(smaliDir: File, outDex: File) {
        val cmd = Class.forName("org.jf.smali.AssembleCommand")
            .getConstructor(List::class.java).newInstance(emptyList<Any>())
        setField(cmd, "input", listOf(smaliDir.absolutePath))
        setField(cmd, "output", outDex.absolutePath)
        setField(cmd, "apiLevel", 35)
        setField(cmd, "jobs", 1)
        cmd.javaClass.getMethod("run").invoke(cmd)
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
}
