package de.minipc.appopsshizuku.stealth

import de.minipc.appopsshizuku.appops.PermissionStrip
import de.minipc.appopsshizuku.appops.StripGroup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Berechtigungen entfernen: genau die Rechte der gewaehlten Gruppen fliegen raus, alles andere bleibt,
 * aapt2 (derselbe Parser-Weg wie beim Installieren) akzeptiert das Ergebnis - auch zusammen mit dem
 * Klon-Rename und an echten App-Manifesten (FOSS Browser, Spooftest).
 */
class PermissionStripTest {

    private val xml = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.test.strip">
            <permission android:name="com.test.strip.permission.OWN" android:protectionLevel="signature"/>
            <uses-permission android:name="android.permission.INTERNET"/>
            <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
            <uses-permission android:name="com.google.android.gms.permission.AD_ID"/>
            <uses-permission android:name="android.permission.ACCESS_ADSERVICES_AD_ID"/>
            <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"/>
            <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
            <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
            <uses-permission-sdk-23 android:name="android.permission.READ_PHONE_STATE"/>
            <uses-permission android:name="android.permission.GET_ACCOUNTS" android:maxSdkVersion="22"/>
            <uses-permission android:name="android.permission.CAMERA"/>
            <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
                android:usesPermissionFlags="neverForLocation"/>
            <uses-permission android:name="com.test.strip.permission.OWN"/>
            <application android:label="S">
                <activity android:name=".MainActivity" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN"/>
                        <category android:name="android.intent.category.LAUNCHER"/>
                    </intent-filter>
                </activity>
                <service android:name=".Sync" android:exported="false"/>
            </application>
        </manifest>
    """.trimIndent()

    private val original: ByteArray by lazy { BinaryXmlTestData.compile(xml) }

    private val untouched = listOf(
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.CAMERA",
        "android.permission.NEARBY_WIFI_DEVICES",
        "com.test.strip.permission.OWN"
    )

    private fun perms(m: ByteArray) = BinaryXml.permissions(m).toSet()

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("manifests/$name")?.use { it.readBytes() }) {
            "Testressource $name fehlt"
        }

    @Test fun offline_entferntNurInternet() {
        val patched = PermissionStrip.apply(original, setOf(StripGroup.OFFLINE))
        assertEquals(perms(original) - "android.permission.INTERNET", perms(patched))
        val tree = BinaryXmlTestData.xmltree(patched)
        assertFalse(tree.contains("android.permission.INTERNET"))
        assertTrue(tree.contains("android.permission.ACCESS_NETWORK_STATE"))
    }

    @Test fun alleGruppen_entfernenGenauIhreRechte_restBleibt() {
        val all = StripGroup.values().toSet()
        val patched = PermissionStrip.apply(original, all)
        val after = perms(patched)
        assertEquals(untouched.toSet(), after)
        // Eigene <permission>-Definition, Komponenten und Launcher bleiben, aapt2 akzeptiert
        val tree = BinaryXmlTestData.xmltree(patched)
        assertTrue(tree.contains("E: permission"))
        assertEquals(BinaryXml.components(original), BinaryXml.components(patched))
        assertEquals("com.test.strip.MainActivity", BinaryXml.launcherActivityClass(patched)?.let {
            BinaryXml.resolveClassName(it, "com.test.strip")
        })
    }

    @Test fun usesPermissionSdk23_undMaxSdk_werdenErfasst() {
        val patched = PermissionStrip.apply(original, setOf(StripGroup.PHONE, StripGroup.ACCOUNTS))
        val after = perms(patched)
        assertFalse("android.permission.READ_PHONE_STATE" in after)
        assertFalse("android.permission.GET_ACCOUNTS" in after)
        BinaryXmlTestData.xmltree(patched)
    }

    @Test fun nichtsDeklariert_lieferExaktDasOriginal() {
        val m = BinaryXmlTestData.compile(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.test.none">
                 <uses-permission android:name="android.permission.CAMERA"/>
                 <application android:label="N"/>
               </manifest>"""
        )
        val groups = setOf(StripGroup.OFFLINE, StripGroup.LOCATION)
        assertTrue(PermissionStrip.present(m, groups).isEmpty())
        assertTrue(PermissionStrip.apply(m, groups) === m)
    }

    @Test fun idempotent() {
        val groups = setOf(StripGroup.OFFLINE, StripGroup.AD_ID, StripGroup.LOCATION)
        val once = PermissionStrip.apply(original, groups)
        assertArrayEquals(once, PermissionStrip.apply(once, groups))
    }

    @Test fun mitKlonRename_bleibtGueltig_undRechteBleibenWeg() {
        val stripped = PermissionStrip.apply(original, setOf(StripGroup.OFFLINE, StripGroup.APP_LIST))
        val cloned = BinaryXml.renamePackage(stripped, "com.test.strip", "com.test.strip.stealth")
        assertEquals("com.test.strip.stealth", BinaryXml.packageName(cloned))
        val after = perms(cloned)
        assertFalse("android.permission.INTERNET" in after)
        assertFalse("android.permission.QUERY_ALL_PACKAGES" in after)
        BinaryXmlTestData.xmltree(cloned)
    }

    @Test fun vergleich_meldetEntferntes_undNichtZugesagtesNachTargetSdk() {
        val groups = setOf(StripGroup.OFFLINE, StripGroup.AD_ID, StripGroup.APP_LIST)
        val patched = PermissionStrip.apply(original, groups)

        val old = PermissionStrip.compare(original, patched, groups, targetSdk = 29)
        assertTrue(old.ok)
        assertEquals(
            setOf(
                "android.permission.INTERNET", "com.google.android.gms.permission.AD_ID",
                "android.permission.ACCESS_ADSERVICES_AD_ID", "android.permission.QUERY_ALL_PACKAGES"
            ),
            old.removed.toSet()
        )
        assertEquals(setOf(StripGroup.AD_ID, StripGroup.APP_LIST), old.notPromised.toSet())

        assertEquals(listOf(StripGroup.AD_ID), PermissionStrip.compare(original, patched, groups, 32).notPromised)
        assertTrue(PermissionStrip.compare(original, patched, groups, 35).notPromised.isEmpty())
        // targetSdk unbekannt -> nichts behaupten
        assertTrue(PermissionStrip.compare(original, patched, groups, null).notPromised.isEmpty())
    }

    @Test fun vergleich_ohneEntfernung_istFehler() {
        val groups = setOf(StripGroup.OFFLINE)
        val report = PermissionStrip.compare(original, original, groups, 35)
        assertFalse(report.ok)
        assertEquals(listOf("android.permission.INTERNET"), report.stillPresent)
        assertTrue(report.removed.isEmpty())
    }

    @Test fun hinweisNurFuerTatsaechlichEntfernteGruppen() {
        // AD_ID gewaehlt, aber nicht deklariert -> kein "nicht zugesagt"-Hinweis fuer AD_ID
        val m = BinaryXmlTestData.compile(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.test.noad">
                 <uses-permission android:name="android.permission.INTERNET"/>
                 <application android:label="A"/>
               </manifest>"""
        )
        val groups = setOf(StripGroup.OFFLINE, StripGroup.AD_ID)
        val report = PermissionStrip.compare(m, PermissionStrip.apply(m, groups), groups, 28)
        assertTrue(report.ok)
        assertTrue(report.notPromised.isEmpty())
    }

    @Test fun echteManifeste_fossUndSpooftest_offlineBleibtFuerAapt2Gueltig() {
        for (name in listOf("foss_manifest.xml", "spooftest_manifest.xml")) {
            val m = resource(name)
            val before = perms(m)
            val patched = PermissionStrip.apply(m, StripGroup.values().toSet())
            val removedExpected = before intersect PermissionStrip.targets(StripGroup.values().toSet())
            assertEquals("$name: genau die Zielrechte fehlen", before - removedExpected, perms(patched))
            assertEquals("$name: Komponenten unveraendert", BinaryXml.components(m), BinaryXml.components(patched))
            BinaryXmlTestData.xmltree(patched)
        }
    }

    @Test fun kurzname() {
        assertEquals("INTERNET", PermissionStrip.shortName("android.permission.INTERNET"))
        assertEquals("AD_ID", PermissionStrip.shortName("com.google.android.gms.permission.AD_ID"))
    }
}
