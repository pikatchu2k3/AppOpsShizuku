package de.minipc.appopsshizuku.stealth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Klon-Haertung: Manifest-Umbenennung inkl. Autoritaeten, Label, Split-Manifeste, Code-Splits. */
class CloneHardeningTest {

    private val baseXml = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.test.clone">
            <application android:label="Original">
                <activity android:name=".MainActivity" android:exported="true"/>
                <provider android:name="com.test.clone.Files" android:authorities="com.test.clone.provider"
                    android:exported="false"/>
                <provider android:name=".Other" android:authorities="com.other.lib.provider;com.test.clone.other"
                    android:exported="false"/>
                <provider android:name=".Multi" android:authorities="com.test.clone.a;com.test.clone.b"
                    android:exported="false"/>
            </application>
        </manifest>
    """.trimIndent()

    private val splitXml = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.test.clone"
            split="config.de">
            <application android:hasCode="false"/>
        </manifest>
    """.trimIndent()

    @Test fun manifest_package_autoritaeten_label() {
        val m = BinaryXmlTestData.compile(baseXml)
        val renamed = BinaryXml.renamePackage(m, "com.test.clone", "com.test.clone.nxt")
        val labeled = BinaryXml.setApplicationLabel(renamed, "Original (Kopie)")
        assertEquals("com.test.clone.nxt", BinaryXml.packageName(labeled))
        val dump = BinaryXmlTestData.xmltree(labeled)
        assertTrue(dump, dump.contains("package=\"com.test.clone.nxt\""))
        // Autoritaeten mit oldPkg.-Praefix werden umgeschrieben ...
        assertTrue(dump, dump.contains("authorities(0x01010018)=\"com.test.clone.nxt.provider\""))
        assertTrue(dump, dump.contains("com.other.lib.provider;com.test.clone.nxt.other"))
        // Liste, in der BEIDE Autoritaeten dem Paket gehoeren: keine bleibt auf dem Original-Namen
        assertTrue(dump, dump.contains("\"com.test.clone.nxt.a;com.test.clone.nxt.b\""))
        // ... Fremdes bleibt, und auch ausgeschriebene Komponentennamen folgen dem Paket
        assertTrue(dump, dump.contains("com.other.lib.provider"))
        assertTrue(dump, dump.contains("\"com.test.clone.nxt.Files\""))
        assertFalse(dump, dump.contains("\"com.test.clone.provider\""))
        assertTrue(dump, dump.contains("label(0x01010001)=\"Original (Kopie)\""))
        // Relative Namen loest das System selbst gegen das neue Paket auf - nicht angefasst
        assertEquals(
            setOf("com.test.clone.nxt.MainActivity", "com.test.clone.nxt.Files", "com.test.clone.nxt.Other", "com.test.clone.nxt.Multi"),
            BinaryXml.components(labeled).map { it.name }.toSet()
        )
    }

    @Test fun split_manifest_traegt_den_neuen_paketnamen() {
        val split = BinaryXmlTestData.compile(splitXml)
        val renamed = CloneRewriter.renameSplitManifest(split, "com.test.clone.nxt")
        assertEquals("com.test.clone.nxt", BinaryXml.packageName(renamed))
        val dump = BinaryXmlTestData.xmltree(renamed)
        assertTrue(dump, dump.contains("package=\"com.test.clone.nxt\""))
        assertTrue("split-Name bleibt", dump.contains("split=\"config.de\""))
        // base und Split muessen exakt gleich heissen, sonst teilt der Installer sie nicht zu
        val base = BinaryXml.renamePackage(BinaryXmlTestData.compile(baseXml), "com.test.clone", "com.test.clone.nxt")
        assertEquals(BinaryXml.packageName(base), BinaryXml.packageName(renamed))
    }

    @Test fun split_manifest_idempotent_und_unveraendert_bei_gleichem_namen() {
        val split = BinaryXmlTestData.compile(splitXml)
        val once = CloneRewriter.renameSplitManifest(split, "com.test.clone.nxt")
        assertArrayEquals(once, CloneRewriter.renameSplitManifest(once, "com.test.clone.nxt"))
        assertArrayEquals(split, CloneRewriter.renameSplitManifest(split, "com.test.clone"))
        assertArrayEquals(ByteArray(3), CloneRewriter.renameSplitManifest(ByteArray(3), "a.b"))
    }

    @Test fun code_splits_werden_erkannt_config_splits_nicht() {
        fun apk(vararg entries: String): File = File.createTempFile("split", ".apk").also { f ->
            ZipOutputStream(f.outputStream()).use { z ->
                for (e in entries) { z.putNextEntry(ZipEntry(e)); z.write(1); z.closeEntry() }
            }
        }
        val config = apk("AndroidManifest.xml", "resources.arsc", "lib/arm64-v8a/libx.so")
        val feature = apk("AndroidManifest.xml", "classes.dex")
        val feature2 = apk("AndroidManifest.xml", "classes2.dex")
        val nested = apk("AndroidManifest.xml", "assets/classes.dex.txt", "res/classes3.dex")
        try {
            assertFalse(CloneRewriter.apkHasDex(config))
            assertTrue(CloneRewriter.apkHasDex(feature))
            assertTrue(CloneRewriter.apkHasDex(feature2))
            assertFalse(CloneRewriter.apkHasDex(nested))
        } finally {
            listOf(config, feature, feature2, nested).forEach { it.delete() }
        }
    }

    @Test fun startProbe_erkennt_absturz_nur_im_eigenen_prozess() {
        val pkg = "com.test.clone.nxt"
        val log = """
            10-04 12:00:00.100  1000  1000 I ActivityManager: Start proc 4242:$pkg/u0a123 for next-top-activity
            10-04 12:00:01.000  4242  4242 E AndroidRuntime: FATAL EXCEPTION: main
            10-04 12:00:01.001  4242  4242 E AndroidRuntime: Process: $pkg, PID: 4242
            10-04 12:00:01.002  4242  4242 E AndroidRuntime: java.lang.ClassNotFoundException: com.x.Y
            10-04 12:00:02.000  9999  9999 E AndroidRuntime: FATAL EXCEPTION: main
            10-04 12:00:02.001  9999  9999 E AndroidRuntime: Process: com.fremd.app, PID: 9999
        """.trimIndent()
        val r = StartProbe.analyze(log, pkg)
        assertTrue(r.started)
        assertTrue(r.crashed)
        assertEquals(setOf("FATAL EXCEPTION", "ClassNotFoundException"), r.hits.keys)
        assertTrue(r.hits.values.none { it.contains("fremd") })
    }

    @Test fun startProbe_ohne_treffer_und_ohne_prozess() {
        val pkg = "com.test.clone.nxt"
        val ok = StartProbe.analyze(
            "10-04 12:00:00.100  1000  1000 I ActivityManager: Start proc 77:$pkg/u0a1 for activity\n" +
                "10-04 12:00:01.000    77    77 I App: alles gut", pkg
        )
        assertTrue(ok.started); assertFalse(ok.crashed); assertTrue(ok.hits.isEmpty())
        val none = StartProbe.analyze("10-04 12:00:00.100  5  5 E X: SecurityException", pkg)
        assertFalse(none.started); assertFalse(none.crashed); assertTrue(none.hits.isEmpty())
    }

    @Test fun startProbe_nichtfatale_marker_sind_kein_absturz() {
        val pkg = "p.q"
        val r = StartProbe.analyze(
            "01-01 00:00:00.000 1 1 I ActivityManager: Start proc 5:p.q/u0a9 for x\n" +
                "01-01 00:00:01.000 5 5 W Foo: java.lang.SecurityException: nope\n" +
                "01-01 00:00:01.100 5 5 E Bar: java.lang.NoSuchMethodError: m\n" +
                "01-01 00:00:01.200 5 5 E Baz: java.lang.VerifyError: v", pkg
        )
        assertFalse(r.crashed)
        assertEquals(setOf("SecurityException", "NoSuchMethodError", "VerifyError"), r.hits.keys)
    }
}
